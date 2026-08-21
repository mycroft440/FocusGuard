package com.focusguard.admin

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.focusguard.R
import com.focusguard.security.ArmoredProtectionPolicy
import com.focusguard.security.AuthManager
import com.focusguard.security.DeactivationCredentialManager
import com.focusguard.security.DeviceOwnerMaintenanceGate
import com.focusguard.utils.FocusGuardLogger
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Stable façade over focused Device Owner policy collaborators. */
@Singleton
class DeviceOwnerManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val deactivationCredentialManager: Lazy<DeactivationCredentialManager>,
    private val access: DeviceOwnerPolicyAccess,
    private val appController: DeviceOwnerAppController,
    private val webController: DeviceOwnerWebPolicyController,
    private val shieldController: DeviceOwnerShieldController
) {
    enum class RenounceOutcome {
        NOT_ACTIVE,
        MAINTENANCE_REQUIRED,
        REVOKED,
        FAILED
    }

    data class RenounceResult(
        val outcome: RenounceOutcome,
        val failureReason: String? = null
    )

    fun isDeviceOwnerActive(): Boolean = access.isDeviceOwnerActive()

    fun isDeviceAdminActive(): Boolean = access.isDeviceAdminActive()

    fun isMaintenanceActive(): Boolean = isDeviceOwnerActive() &&
        DeviceOwnerMaintenanceGate.isTemporarilyUnlocked(context)

    fun isBlockingProtectionArmed(): Boolean = access.isBlockingProtectionArmed()

    fun isAdultContentProtectionArmed(): Boolean =
        access.isAdultContentProtectionArmed()

    fun isArmoredProtectionArmed(): Boolean =
        isBlockingProtectionArmed() || isAdultContentProtectionArmed()

    fun protectionPhase(): ArmoredProtectionPolicy.Phase = ArmoredProtectionPolicy.phase(
        deviceOwnerActive = isDeviceOwnerActive(),
        protectionArmed = isArmoredProtectionArmed(),
        maintenanceActive = isMaintenanceActive()
    )

    internal fun usesDeviceProtectedPolicyState(): Boolean =
        access.usesDeviceProtectedPolicyState()

    fun maintenanceRemainingMillis(): Long = if (isDeviceOwnerActive()) {
        DeviceOwnerMaintenanceGate.remainingMillis(context)
    } else {
        0L
    }

    fun requestMaintenanceWithCredential(
        credential: String
    ): DeviceOwnerMaintenanceGate.UnlockResult {
        if (!isDeviceOwnerActive()) {
            return DeviceOwnerMaintenanceGate.UnlockResult.INVALID_CREDENTIAL
        }
        val phase = protectionPhase()
        if (!ArmoredProtectionPolicy.canOpenMaintenanceWithCredential(phase)) {
            return if (phase == ArmoredProtectionPolicy.Phase.ARMED) {
                DeviceOwnerMaintenanceGate.UnlockResult.ACTIVE_BLOCK_REQUIRES_MONTHLY_WINDOW
            } else {
                DeviceOwnerMaintenanceGate.UnlockResult.INVALID_CREDENTIAL
            }
        }
        val result = DeviceOwnerMaintenanceGate.requestWithCredential(
            context = context,
            credential = credential,
            credentialManager = deactivationCredentialManager.get(),
            protectionArmed = false
        )
        if (result == DeviceOwnerMaintenanceGate.UnlockResult.UNLOCKED) {
            applyNuclearShield()
        }
        return result
    }

    fun requestMonthlyMaintenance(): DeviceOwnerMaintenanceGate.UnlockResult {
        if (!isDeviceOwnerActive()) {
            return DeviceOwnerMaintenanceGate.UnlockResult.OUTSIDE_MONTHLY_WINDOW
        }
        val result = DeviceOwnerMaintenanceGate.requestMonthlyWindow(
            context = context,
            protectionArmed = isArmoredProtectionArmed()
        )
        if (result == DeviceOwnerMaintenanceGate.UnlockResult.UNLOCKED) {
            applyNuclearShield()
        }
        return result
    }

    fun endMaintenanceNow() {
        DeviceOwnerMaintenanceGate.revoke(context)
        applyNuclearShield()
    }

    fun createDeviceAdminActivationIntent(): Intent =
        Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, access.componentName)
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                context.getString(R.string.permission_device_admin_desc)
            )
        }

    suspend fun syncSuspendedApps(
        allAppsInSessions: List<String>,
        appsToBlockNow: List<String>,
        allowedSystemApps: Set<String> = emptySet()
    ) = appController.syncSuspendedApps(
        allAppsInSessions,
        appsToBlockNow,
        allowedSystemApps
    )

    fun unblockApps(packageNames: List<String>) = appController.unblockApps(packageNames)

    fun lockDevice() = appController.lockDevice()

    fun prepareStrictPomodoroLockTaskPackages() =
        appController.prepareStrictPomodoroLockTaskPackages()

    fun clearStrictPomodoroLockTaskPackages() =
        appController.clearStrictPomodoroLockTaskPackages()

    fun prepareFocusModeLockTaskPackages(
        allowedPackages: Collection<String>
    ): Boolean = appController.prepareFocusModeLockTaskPackages(allowedPackages)

    fun clearFocusModeLockTaskPackages() = appController.clearFocusModeLockTaskPackages()

    fun isPackageSuspendedByFocusMode(packageName: String): Boolean =
        appController.isPackageSuspendedByFocusMode(packageName)

    fun isFocusModeLockTaskPermitted(): Boolean =
        appController.isFocusModeLockTaskPermitted()

    fun isFocusModeSystemLockdownSupported(): Boolean =
        supportsStrictFocusModeLockdown(android.os.Build.VERSION.SDK_INT)

    fun isFocusModeSystemLockdownConfirmed(): Boolean =
        appController.isFocusModeSystemLockdownConfirmed()

    fun applyFocusModeAtDirectBoot(): Boolean = appController.applyFocusModeAtDirectBoot()

    fun getStatusInfo(): String = buildString {
        appendLine("Device Admin Ativo: ${isDeviceAdminActive()}")
        appendLine("Device Owner Ativo: ${isDeviceOwnerActive()}")
        appendLine("Manutenção restante: ${maintenanceRemainingMillis() / 1_000}s")
    }

    fun enforceBlockingPolicies(): Boolean = shieldController.enforceBlockingPolicies()

    fun clearBlockingPolicies() = shieldController.clearBlockingPolicies()

    fun applyDirectBootShield() = shieldController.applyDirectBootShield()

    fun applyNuclearShield() = shieldController.applyNuclearShield()

    fun revokeNuclearShield() = shieldController.revokeNuclearShield()

    suspend fun releaseRemovalProtectionForUninstall(): Boolean =
        shieldController.releaseRemovalProtectionForUninstall()

    suspend fun enforceWebsiteRestrictions(domains: List<String>) =
        webController.enforceWebsiteRestrictions(domains)

    suspend fun clearWebsiteRestrictions() = webController.clearWebsiteRestrictions()

    suspend fun invalidateWebsitePolicyCache() =
        webController.invalidateWebsitePolicyCache()

    fun enforceAdultDns(): Boolean = webController.enforceAdultDns()

    fun setPornographyCategoryActive(active: Boolean) =
        webController.setPornographyCategoryActive(active)

    fun clearAdultDns() = webController.clearAdultDns()

    @Suppress("DEPRECATION")
    fun renounceDeviceOwner(): RenounceResult {
        if (!isDeviceOwnerActive()) return RenounceResult(RenounceOutcome.NOT_ACTIVE)
        if (!ArmoredProtectionPolicy.canRenounceDeviceOwner(protectionPhase())) {
            return RenounceResult(RenounceOutcome.MAINTENANCE_REQUIRED)
        }

        return try {
            clearBlockingPolicies()
            revokeNuclearShield()
            setPornographyCategoryActive(false)
            if (AuthManager.isAdultFilterConfigured(context)) clearAdultDns()
            access.dpm.clearDeviceOwnerApp(context.packageName)
            DeviceOwnerMaintenanceGate.revoke(context)
            RenounceResult(RenounceOutcome.REVOKED)
        } catch (error: Exception) {
            FocusGuardLogger.logError(
                "DeviceOwner",
                "Falha ao revogar Device Owner",
                error
            )
            RenounceResult(
                outcome = RenounceOutcome.FAILED,
                failureReason = error.message ?: error.javaClass.simpleName
            )
        }
    }

    companion object {
        internal const val ADULT_DNS_HOST = DeviceOwnerPolicyCatalog.ADULT_DNS_HOST

        internal fun activeBlockRestrictionsForSdk(sdkInt: Int): List<String> =
            DeviceOwnerPolicyCatalog.activeBlockRestrictionsForSdk(sdkInt)

        internal fun legacyGlobalAppControlRestrictionsForSdk(
            sdkInt: Int
        ): List<String> =
            DeviceOwnerPolicyCatalog.legacyGlobalAppControlRestrictionsForSdk(sdkInt)

        internal fun adultContentRestrictionsForSdk(sdkInt: Int): List<String> =
            DeviceOwnerPolicyCatalog.adultContentRestrictionsForSdk(sdkInt)

        internal fun allShieldRestrictionsForSdk(sdkInt: Int): List<String> =
            DeviceOwnerPolicyCatalog.allShieldRestrictionsForSdk(sdkInt)

        internal fun allRestrictionsForCleanupForSdk(sdkInt: Int): List<String> =
            DeviceOwnerPolicyCatalog.allRestrictionsForCleanupForSdk(sdkInt)

        internal fun requiresAdultDns(
            globalAdultFilterEnabled: Boolean,
            pornographyCategoryActive: Boolean
        ): Boolean = globalAdultFilterEnabled || pornographyCategoryActive

        internal fun shouldRestoreActiveBlockAtDirectBoot(
            blockingProtectionArmed: Boolean,
            interruptedMaintenance: Boolean
        ): Boolean = blockingProtectionArmed || interruptedMaintenance

        internal fun shouldRestoreAdultDnsAtDirectBoot(
            adultContentProtectionArmed: Boolean,
            pornographyCategoryActive: Boolean
        ): Boolean = adultContentProtectionArmed || pornographyCategoryActive

        internal fun supportsStrictFocusModeLockdown(sdkInt: Int): Boolean =
            DeviceOwnerPolicyCatalog.supportsStrictFocusModeLockdown(sdkInt)

        internal fun lockTaskFeaturesKeepOnlyGlobalActions(features: Int): Boolean =
            DeviceOwnerPolicyCatalog.lockTaskFeaturesKeepOnlyGlobalActions(features)

        internal fun buildManagedBrowserRestrictions(
            existing: Bundle,
            managedFilters: List<String>,
            privateModePolicy: String,
            requireSystemDns: Boolean
        ): Bundle = DeviceOwnerPolicyCatalog.buildManagedBrowserRestrictions(
            existing,
            managedFilters,
            privateModePolicy,
            requireSystemDns
        )
    }
}
