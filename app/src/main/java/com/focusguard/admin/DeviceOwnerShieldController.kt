package com.focusguard.admin

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Build
import android.util.Log
import com.focusguard.focusmode.FocusModeStore
import com.focusguard.security.AuthenticatedRemovalWindow
import com.focusguard.security.DeviceOwnerMaintenanceGate
import com.focusguard.utils.FocusGuardLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Applies and removes the persistent Device Owner protection shield. */
@Singleton
class DeviceOwnerShieldController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val access: DeviceOwnerPolicyAccess,
    private val appController: DeviceOwnerAppController,
    private val webController: DeviceOwnerWebPolicyController
) {
    fun enforceBlockingPolicies(): Boolean {
        if (!access.isDeviceOwnerActive()) return false
        return try {
            check(enforceAppControlProtection()) {
                "Android não confirmou o bloqueio de desinstalação do FocusGuard"
            }
            access.setBlockingProtectionArmed(true)
            applyNuclearShield()
            Log.d(TAG, "Políticas de sessão aplicadas e confirmadas")
            true
        } catch (error: Exception) {
            FocusGuardLogger.logError(
                "DeviceOwner",
                "Falha ao aplicar políticas de sessão",
                error
            )
            false
        }
    }

    fun clearBlockingPolicies() {
        if (!access.isDeviceOwnerActive()) return
        try {
            DeviceOwnerPolicyCatalog.activeBlockRestrictionsForSdk(Build.VERSION.SDK_INT)
                .forEach { restriction ->
                    access.dpm.clearUserRestriction(access.componentName, restriction)
                }
            access.setBlockingProtectionArmed(false)
            Log.d(TAG, "Políticas de sessão removidas")
        } catch (error: Exception) {
            FocusGuardLogger.logError(
                "DeviceOwner",
                "Falha ao remover políticas de sessão",
                error
            )
        }
    }

    fun applyDirectBootShield() {
        if (!access.isDeviceOwnerActive()) return

        val interruptedMaintenance = DeviceOwnerMaintenanceGate.hasPersistedWindow(context)
        val interruptedArmedMaintenance = interruptedMaintenance &&
            DeviceOwnerMaintenanceGate.wasProtectionArmedWhenOpened(context)
        if (interruptedMaintenance) DeviceOwnerMaintenanceGate.revoke(context)

        val restoreAdultDns = DeviceOwnerManager.shouldRestoreAdultDnsAtDirectBoot(
            adultContentProtectionArmed = access.isAdultContentProtectionArmed(),
            pornographyCategoryActive = access.isPornographyCategoryActive()
        )
        val restoreProtection = restoreAdultDns ||
            DeviceOwnerManager.shouldRestoreActiveBlockAtDirectBoot(
                blockingProtectionArmed = isArmoredProtectionArmed(),
                interruptedMaintenance = interruptedArmedMaintenance
            )
        if (restoreProtection) {
            enforceTrustedAutomaticTime()
            setRestrictions(DeviceOwnerPolicyCatalog.alwaysOnRestrictions, enabled = true)
            enforceAppControlProtection()
            DeviceOwnerPolicyCatalog.activeBlockRestrictionsForSdk(Build.VERSION.SDK_INT)
                .forEach { restriction ->
                    access.applyPolicySafely("direct_boot_add:$restriction") {
                        access.dpm.addUserRestriction(access.componentName, restriction)
                    }
                }
        } else {
            setRestrictions(DeviceOwnerPolicyCatalog.alwaysOnRestrictions, enabled = false)
            setRestrictions(
                DeviceOwnerPolicyCatalog.activeBlockRestrictionsForSdk(Build.VERSION.SDK_INT),
                enabled = false
            )
            relaxAppControlProtection()
        }

        if (restoreAdultDns) webController.enforceAdultDns()
        Log.d(TAG, "Nuclear Shield restaurado no Direct Boot")
    }

    fun applyNuclearShield() {
        access.migrateToDeviceProtectedStorage()
        if (!access.isDeviceOwnerActive()) return

        val adultProtectionRequired = webController.isAdultDnsProtectionRequired()
        access.setAdultContentProtectionArmed(adultProtectionRequired)
        val protectionArmed = access.isBlockingProtectionArmed() || adultProtectionRequired
        val maintenanceActive = DeviceOwnerMaintenanceGate.isTemporarilyUnlocked(context)

        when {
            maintenanceActive -> {
                if (protectionArmed) {
                    enforceTrustedAutomaticTime()
                    setRestrictions(DeviceOwnerPolicyCatalog.alwaysOnRestrictions, enabled = true)
                } else {
                    setRestrictions(DeviceOwnerPolicyCatalog.alwaysOnRestrictions, enabled = false)
                }
                setRestrictions(
                    DeviceOwnerPolicyCatalog.activeBlockRestrictionsForSdk(
                        Build.VERSION.SDK_INT
                    ),
                    enabled = false
                )
                relaxAppControlProtection()
                webController.clearAdultContentRestrictions()
                Log.d(TAG, "Nuclear Shield em modo de manutenção temporária")
            }
            protectionArmed -> {
                enforceTrustedAutomaticTime()
                setRestrictions(DeviceOwnerPolicyCatalog.alwaysOnRestrictions, enabled = true)
                setRestrictions(
                    DeviceOwnerPolicyCatalog.activeBlockRestrictionsForSdk(
                        Build.VERSION.SDK_INT
                    ),
                    enabled = true
                )
                enforceAppControlProtection()
                if (adultProtectionRequired) {
                    if (!webController.enforceAdultDns()) {
                        FocusGuardLogger.log(
                            "DeviceOwner",
                            "Filtro de pornografia ativo, mas o Android não confirmou a " +
                                "política DNS"
                        )
                    }
                } else {
                    webController.clearAdultContentRestrictions()
                }
                Log.d(TAG, "Nuclear Shield completo aplicado")
            }
            else -> {
                setRestrictions(DeviceOwnerPolicyCatalog.alwaysOnRestrictions, enabled = false)
                setRestrictions(
                    DeviceOwnerPolicyCatalog.activeBlockRestrictionsForSdk(
                        Build.VERSION.SDK_INT
                    ),
                    enabled = false
                )
                relaxAppControlProtection()
                webController.clearAdultContentRestrictions()
                Log.d(TAG, "Device Owner pronto; nenhuma proteção está armada")
            }
        }
    }

    fun revokeNuclearShield() {
        if (!access.isDeviceOwnerActive() || !isMaintenanceActive()) return
        clearOwnedPoliciesForRemoval()
        Log.d(TAG, "Nuclear Shield revogado para remoção legítima")
    }

    @Suppress("DEPRECATION")
    suspend fun releaseRemovalProtectionForUninstall(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (access.isDeviceOwnerActive()) {
                val removalAuthorized = AuthenticatedRemovalWindow.isActive(context)
                if (!removalAuthorized && !isMaintenanceActive()) return@withContext false
                clearOwnedPoliciesForRemoval()
                access.dpm.clearDeviceOwnerApp(context.packageName)
                DeviceOwnerMaintenanceGate.revoke(context)
            }

            if (access.isDeviceAdminActive()) {
                access.dpm.removeActiveAdmin(access.componentName)
            }

            awaitAdministrativeRolesReleased()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            FocusGuardLogger.logError(
                "DeviceOwner",
                "Falha ao liberar funções administrativas para desinstalação",
                error
            )
            false
        }
    }

    internal fun clearOwnedPoliciesForRemoval() {
        DeviceOwnerPolicyCatalog.allRestrictionsForCleanupForSdk(Build.VERSION.SDK_INT)
            .forEach { restriction ->
                access.applyPolicySafely("clear:$restriction") {
                    access.dpm.clearUserRestriction(access.componentName, restriction)
                }
            }

        relaxAppControlProtection()

        val suspendedPackages = appController.managedSuspendedApps() +
            FocusModeStore.readSession(context)?.blockedPackages.orEmpty()
        if (suspendedPackages.isNotEmpty()) {
            access.applyPolicySafely("unsuspend_all_for_removal") {
                access.dpm.setPackagesSuspended(
                    access.componentName,
                    suspendedPackages.toTypedArray(),
                    false
                )
            }
        }
        appController.clearManagedSuspendedApps()
        webController.clearManagedBrowserRestrictionsForRemoval()

        access.applyPolicySafely("clear_lock_task_packages") {
            access.dpm.setLockTaskPackages(access.componentName, emptyArray<String>())
        }

        access.setPornographyCategoryActive(false)
        access.setBlockingProtectionArmed(false)
        access.setAdultContentProtectionArmed(false)
        webController.restorePrivateDnsAfterCategory()
    }

    private fun setRestrictions(restrictions: Collection<String>, enabled: Boolean) {
        restrictions.forEach { restriction ->
            access.applyPolicySafely("${if (enabled) "add" else "clear"}:$restriction") {
                if (enabled) {
                    access.dpm.addUserRestriction(access.componentName, restriction)
                } else {
                    access.dpm.clearUserRestriction(access.componentName, restriction)
                }
            }
        }
    }

    private fun enforceTrustedAutomaticTime() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        access.applyPolicySafely("auto_time") {
            access.dpm.setAutoTimeEnabled(access.componentName, true)
        }
        access.applyPolicySafely("auto_timezone") {
            access.dpm.setAutoTimeZoneEnabled(access.componentName, true)
        }
    }

    private fun enforceAppControlProtection(): Boolean {
        val legacyRestrictionsCleared = clearLegacyGlobalAppControlRestrictions()
        val uninstallPolicyApplied = access.applyPolicySafely("uninstall_blocked") {
            access.dpm.setUninstallBlocked(
                access.componentName,
                context.packageName,
                true
            )
        }
        val userControlPolicyApplied = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            access.applyPolicySafely("user_control_disabled") {
                access.dpm.setUserControlDisabledPackages(
                    access.componentName,
                    listOf(context.packageName)
                )
            }
        } else {
            true
        }
        enforceRuntimePermissionProtection()
        val uninstallPolicyVerified = runCatching {
            access.dpm.isUninstallBlocked(access.componentName, context.packageName)
        }.onFailure { error ->
            FocusGuardLogger.logError(
                "DeviceOwner",
                "Falha ao verificar a política uninstall_blocked",
                error
            )
        }.getOrDefault(false)
        val userControlPolicyVerified = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                context.packageName in
                    access.dpm.getUserControlDisabledPackages(access.componentName)
            }.onFailure { error ->
                FocusGuardLogger.logError(
                    "DeviceOwner",
                    "Falha ao verificar a política user_control_disabled",
                    error
                )
            }.getOrDefault(false)
        } else {
            true
        }
        return legacyRestrictionsCleared &&
            uninstallPolicyApplied &&
            uninstallPolicyVerified &&
            userControlPolicyApplied &&
            userControlPolicyVerified
    }

    private fun relaxAppControlProtection() {
        clearLegacyGlobalAppControlRestrictions()
        access.applyPolicySafely("uninstall_allowed") {
            access.dpm.setUninstallBlocked(
                access.componentName,
                context.packageName,
                false
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            access.applyPolicySafely("user_control_enabled") {
                access.dpm.setUserControlDisabledPackages(
                    access.componentName,
                    emptyList()
                )
            }
        }
        relaxRuntimePermissionProtection()
    }

    private fun clearLegacyGlobalAppControlRestrictions(): Boolean {
        val restrictions =
            DeviceOwnerPolicyCatalog.legacyGlobalAppControlRestrictionsForSdk(
                Build.VERSION.SDK_INT
            )
        val operationsSucceeded = restrictions.map { restriction ->
            access.applyPolicySafely("clear:$restriction") {
                access.dpm.clearUserRestriction(access.componentName, restriction)
            }
        }.all { it }
        val verified = runCatching {
            val current = access.dpm.getUserRestrictions(access.componentName)
            restrictions.none { restriction -> current.getBoolean(restriction, false) }
        }.onFailure { error ->
            FocusGuardLogger.logError(
                "DeviceOwner",
                "Falha ao verificar a remoção das restrições globais legadas",
                error
            )
        }.getOrDefault(false)
        if (!verified) {
            FocusGuardLogger.log(
                "DeviceOwner",
                "O Android ainda reporta uma restrição global legada de controle de apps"
            )
        }
        return operationsSucceeded && verified
    }

    private fun enforceRuntimePermissionProtection() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        access.applyPolicySafely("grant_notifications") {
            access.dpm.setPermissionGrantState(
                access.componentName,
                context.packageName,
                Manifest.permission.POST_NOTIFICATIONS,
                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
            )
        }
    }

    private fun relaxRuntimePermissionProtection() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        access.applyPolicySafely("release_notifications") {
            access.dpm.setPermissionGrantState(
                access.componentName,
                context.packageName,
                Manifest.permission.POST_NOTIFICATIONS,
                DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT
            )
        }
    }

    private suspend fun awaitAdministrativeRolesReleased(): Boolean {
        repeat(ADMIN_ROLE_RELEASE_POLL_ATTEMPTS) {
            if (!access.isDeviceOwnerActive() && !access.isDeviceAdminActive()) return true
            delay(ADMIN_ROLE_RELEASE_POLL_INTERVAL_MILLIS)
        }
        return !access.isDeviceOwnerActive() && !access.isDeviceAdminActive()
    }

    private fun isArmoredProtectionArmed(): Boolean =
        access.isBlockingProtectionArmed() || access.isAdultContentProtectionArmed()

    private fun isMaintenanceActive(): Boolean = access.isDeviceOwnerActive() &&
        DeviceOwnerMaintenanceGate.isTemporarilyUnlocked(context)

    private companion object {
        const val TAG = "FocusGuardAdmin"
        const val ADMIN_ROLE_RELEASE_POLL_ATTEMPTS = 50
        const val ADMIN_ROLE_RELEASE_POLL_INTERVAL_MILLIS = 100L
    }
}
