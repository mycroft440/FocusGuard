package com.focusguard.admin

import android.Manifest
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import com.focusguard.R
import com.focusguard.data.PredefinedWebsites
import com.focusguard.security.ArmoredProtectionPolicy
import com.focusguard.security.AuthManager
import com.focusguard.security.DeviceOwnerMaintenanceGate
import com.focusguard.utils.FocusGuardLogger
import com.focusguard.utils.WebsiteBlocker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Manager for Device Owner Mode functionality.
 * Handles app blocking and device policy enforcement.
 */
class DeviceOwnerManager private constructor(private val context: Context) {

    private val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val componentName = FocusGuardDeviceAdminReceiver.getComponentName(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val websitePolicyMutex = Mutex()
    private val suspendedAppsMutex = Mutex()
    // The suspended-package inventory is credential protected and is never needed
    // before the first unlock. Keeping it lazy prevents Direct Boot from touching it.
    private val suspendedAppsPreferences by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        context.getSharedPreferences(SUSPENDED_APPS_PREFERENCES, Context.MODE_PRIVATE)
    }
    private val policyStateContext = runCatching {
        context.createDeviceProtectedStorageContext()
    }.getOrDefault(context)
    private val policyStatePreferences = policyStateContext.getSharedPreferences(
        POLICY_STATE_PREFERENCES,
        Context.MODE_PRIVATE
    )
    private var lastWebsitePolicySignature: String? = null

    companion object {
        @Volatile
        private var instance: DeviceOwnerManager? = null

        fun getInstance(context: Context): DeviceOwnerManager {
            return instance ?: synchronized(this) {
                instance ?: DeviceOwnerManager(context.applicationContext).also { instance = it }
            }
        }

        private const val MAX_MANAGED_URLS = 1_000
        private const val ACTION_DEVICE_ADMIN_SETTINGS = "android.settings.DEVICE_ADMIN_SETTINGS"
        private const val SUSPENDED_APPS_PREFERENCES = "focusguard_suspended_apps"
        private const val MANAGED_SUSPENDED_APPS_KEY = "managed_packages"
        private const val POLICY_STATE_PREFERENCES = "focusguard_device_owner_policy_state"
        private const val POLICY_STATE_STORAGE_VERSION_KEY = "device_protected_storage_version"
        private const val POLICY_STATE_STORAGE_VERSION = 1
        private const val BLOCKING_PROTECTION_ARMED_KEY = "blocking_protection_armed"
        private const val ADULT_CONTENT_PROTECTION_ARMED_KEY =
            "adult_content_protection_armed"
        private const val PORNOGRAPHY_CATEGORY_ACTIVE_KEY = "pornography_category_active"
        private const val PREVIOUS_PRIVATE_DNS_CAPTURED_KEY = "previous_private_dns_captured"
        private const val PREVIOUS_PRIVATE_DNS_MODE_KEY = "previous_private_dns_mode"
        private const val PREVIOUS_PRIVATE_DNS_HOST_KEY = "previous_private_dns_host"
        internal const val ADULT_DNS_HOST = "family-filter-dns.cleanbrowsing.org"
        private val CHROME_MANAGED_PACKAGES = setOf(
            "com.android.chrome",
            "com.chrome.beta",
            "com.chrome.dev",
            "com.chrome.canary"
        )
        private val EDGE_MANAGED_PACKAGES = setOf(
            "com.microsoft.emmx",
            "com.microsoft.emmx.beta",
            "com.microsoft.emmx.dev",
            "com.microsoft.emmx.canary"
        )
        private val SACRED_WHITELIST = setOf(
            "com.android.systemui",
            "com.android.settings",
            "com.android.phone",
            "com.android.server.telecom",
            "com.google.android.packageinstaller",
            "com.android.packageinstaller",
            "com.google.android.gms",
            "com.android.vending"
        )

        private val PHONE_LOCK_TASK_PACKAGES = listOf(
            "com.android.dialer",
            "com.google.android.dialer",
            "com.android.phone",
            "com.android.server.telecom",
            "com.samsung.android.dialer",
            "com.samsung.android.incallui",
            "com.miui.dialer",
            "com.coloros.dialer",
            "com.oplus.dialer"
        )

        /** Restrictions that remain active even during the ten-minute maintenance window. */
        private val ALWAYS_ON_RESTRICTIONS = listOf(
            UserManager.DISALLOW_FACTORY_RESET,
            UserManager.DISALLOW_SAFE_BOOT,
            UserManager.DISALLOW_CONFIG_DATE_TIME
        )

        /**
         * User/profile isolation used while protection is armed.
         *
         * Deliberately excluded by product choice: debugging/ADB, USB controls, unknown-source
         * controls and installation restrictions.
         */
        internal fun activeBlockRestrictionsForSdk(sdkInt: Int): List<String> = buildList {
            add(UserManager.DISALLOW_ADD_USER)
            add(UserManager.DISALLOW_REMOVE_USER)
            if (sdkInt >= Build.VERSION_CODES.P) {
                add(UserManager.DISALLOW_USER_SWITCH)
            }
            if (sdkInt >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                add(UserManager.DISALLOW_ADD_PRIVATE_PROFILE)
            }
        }

        internal fun appControlRestrictionsForSdk(sdkInt: Int): List<String> = buildList {
            add(UserManager.DISALLOW_UNINSTALL_APPS)
            add(UserManager.DISALLOW_APPS_CONTROL)
            if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                add(UserManager.DISALLOW_GRANT_ADMIN)
            }
        }

        internal fun adultContentRestrictionsForSdk(sdkInt: Int): List<String> = buildList {
            add(UserManager.DISALLOW_CONFIG_VPN)
            if (sdkInt >= Build.VERSION_CODES.Q) {
                add(UserManager.DISALLOW_CONFIG_PRIVATE_DNS)
            }
        }

        internal fun allShieldRestrictionsForSdk(sdkInt: Int): List<String> =
            ALWAYS_ON_RESTRICTIONS +
                appControlRestrictionsForSdk(sdkInt) +
                adultContentRestrictionsForSdk(sdkInt) +
                activeBlockRestrictionsForSdk(sdkInt)

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

        internal fun buildManagedBrowserRestrictions(
            existing: Bundle,
            managedFilters: List<String>,
            privateModePolicy: String,
            requireSystemDns: Boolean
        ): Bundle = Bundle(existing).apply {
            remove("URLBlocklist")
            remove("IncognitoModeAvailability")
            remove("InPrivateModeAvailability")
            remove("DnsOverHttpsMode")
            remove("DnsOverHttpsTemplates")
            if (requireSystemDns) {
                putString("DnsOverHttpsMode", "off")
            }
            if (managedFilters.isNotEmpty()) {
                putStringArray("URLBlocklist", managedFilters.toTypedArray())
                putInt(privateModePolicy, 1)
            }
        }
    }

    /** Check if Device Owner Mode is active. */
    fun isDeviceOwnerActive(): Boolean {
        return try {
            dpm.isDeviceOwnerApp(context.packageName)
        } catch (_: Exception) {
            false
        }
    }

    /** Check if Device Admin is active. */
    fun isDeviceAdminActive(): Boolean {
        return try {
            dpm.isAdminActive(componentName)
        } catch (_: Exception) {
            false
        }
    }

    fun isMaintenanceActive(): Boolean =
        isDeviceOwnerActive() && DeviceOwnerMaintenanceGate.isTemporarilyUnlocked(context)

    /** True from the first effective blocked target until enforcement confirms it ended. */
    fun isBlockingProtectionArmed(): Boolean =
        policyStatePreferences.getBoolean(BLOCKING_PROTECTION_ARMED_KEY, false)

    fun isArmoredProtectionArmed(): Boolean =
        isBlockingProtectionArmed() || isAdultContentProtectionArmed()

    fun protectionPhase(): ArmoredProtectionPolicy.Phase = ArmoredProtectionPolicy.phase(
        deviceOwnerActive = isDeviceOwnerActive(),
        protectionArmed = isArmoredProtectionArmed(),
        maintenanceActive = isMaintenanceActive()
    )

    internal fun usesDeviceProtectedPolicyState(): Boolean =
        policyStateContext.isDeviceProtectedStorage

    fun maintenanceRemainingMillis(): Long =
        if (isDeviceOwnerActive()) DeviceOwnerMaintenanceGate.remainingMillis(context) else 0L

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

    /**
     * Builds the system-owned confirmation screen for legacy Device Admin.
     * The foreground Activity must launch this intent so its result and lifecycle
     * stay tied to the permission screen instead of opening in a detached task.
     */
    fun createDeviceAdminActivationIntent(): Intent {
        return Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName)
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                context.getString(R.string.permission_device_admin_desc)
            )
        }
    }

    /** Opens the manufacturer's Device Admin list when the direct screen is unavailable. */
    fun openDeviceAdminSettings(hostContext: Context): Boolean {
        val candidates = listOf(
            Intent(ACTION_DEVICE_ADMIN_SETTINGS),
            Intent(Settings.ACTION_SECURITY_SETTINGS),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            },
            Intent(Settings.ACTION_SETTINGS)
        )

        candidates.forEach { candidate ->
            val launched = runCatching {
                if (hostContext !is Activity) {
                    candidate.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                hostContext.startActivity(candidate)
                true
            }.onFailure { error ->
                FocusGuardLogger.logError(
                    "DeviceOwner",
                    "Falha ao abrir ${candidate.action}",
                    error
                )
            }.getOrDefault(false)

            if (launched) return true
        }

        return false
    }

    /**
     * Update the suspended apps list to match exactly the provided list.
     * Apps in the list will be suspended; apps NOT in the list will be unsuspended.
     */
    suspend fun syncSuspendedApps(
        allAppsInSessions: List<String>,
        appsToBlockNow: List<String>,
        allowedSystemApps: Set<String> = emptySet()
    ) {
        if (!isDeviceOwnerActive()) return

        suspendedAppsMutex.withLock {
            try {
                val myPkg = context.packageName
                val pm = context.packageManager
                val previouslyManaged = managedSuspendedApps()
                val filteredToBlock = appsToBlockNow.distinct().filter { pkg ->
                    if (pkg == myPkg || pkg == "com.focusguard") return@filter false
                    if (SACRED_WHITELIST.contains(pkg)) return@filter false
                    try {
                        val appInfo = pm.getApplicationInfo(pkg, 0)
                        val isSystemApp =
                            (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                        !isSystemApp || pkg in allowedSystemApps
                    } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
                        false
                    }
                }
                val desired = filteredToBlock.toSet()
                val managedAfterSync = previouslyManaged.toMutableSet()
                val appsToUnblock = (allAppsInSessions + previouslyManaged)
                    .distinct()
                    .filter { it !in desired }

                if (appsToUnblock.isNotEmpty()) {
                    val failed = dpm.setPackagesSuspended(
                        componentName,
                        appsToUnblock.toTypedArray(),
                        false
                    ).toSet()
                    managedAfterSync.removeAll(appsToUnblock.toSet() - failed)
                    Log.d(
                        "FocusGuardAdmin",
                        "Apps desbloqueados diferencialmente: " +
                            "${appsToUnblock.size - failed.size}"
                    )
                }

                if (filteredToBlock.isNotEmpty()) {
                    val failed = dpm.setPackagesSuspended(
                        componentName,
                        filteredToBlock.toTypedArray(),
                        true
                    ).toSet()
                    managedAfterSync.addAll(filteredToBlock.toSet() - failed)
                    Log.d(
                        "FocusGuardAdmin",
                        "Apps suspensos: ${filteredToBlock.size - failed.size}"
                    )
                }
                saveManagedSuspendedApps(managedAfterSync)
            } catch (error: Exception) {
                Log.e("FocusGuardAdmin", "Falha na sincronização diferencial de apps", error)
                throw error
            }
        }
    }

    /** Unblock applications. */
    fun unblockApps(packageNames: List<String>) {
        if (!isDeviceOwnerActive() || packageNames.isEmpty()) return

        scope.launch {
            suspendedAppsMutex.withLock {
                try {
                    val failed = dpm.setPackagesSuspended(
                        componentName,
                        packageNames.toTypedArray(),
                        false
                    ).toSet()
                    val remaining = managedSuspendedApps().toMutableSet()
                    remaining.removeAll(packageNames.toSet() - failed)
                    saveManagedSuspendedApps(remaining)
                } catch (error: Exception) {
                    FocusGuardLogger.logError("Admin", "Erro ao desbloquear apps", error)
                }
            }
        }
    }

    private fun managedSuspendedApps(): Set<String> {
        return suspendedAppsPreferences
            .getStringSet(MANAGED_SUSPENDED_APPS_KEY, emptySet())
            .orEmpty()
            .toSet()
    }

    private fun saveManagedSuspendedApps(packageNames: Set<String>) {
        suspendedAppsPreferences.edit()
            .putStringSet(MANAGED_SUSPENDED_APPS_KEY, packageNames)
            .apply()
    }

    /** Lock the device. */
    fun lockDevice() {
        if (!isDeviceAdminActive()) return
        try {
            dpm.lockNow()
        } catch (e: Exception) {
            FocusGuardLogger.log("Admin", "Erro ao travar dispositivo: ${e.message}")
        }
    }

    /** Configura o Lock Task para o Pomodoro rigoroso. */
    fun prepareStrictPomodoroLockTaskPackages() {
        if (!isDeviceOwnerActive()) return
        try {
            val pm = context.packageManager
            val allowedPackages = (listOf(context.packageName) + PHONE_LOCK_TASK_PACKAGES)
                .distinct()
                .filter { packageName ->
                    packageName == context.packageName || runCatching {
                        pm.getPackageInfo(packageName, 0)
                        true
                    }.getOrDefault(false)
                }

            dpm.setLockTaskPackages(componentName, allowedPackages.toTypedArray())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                dpm.setLockTaskFeatures(componentName, DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
            }
            Log.d("FocusGuardAdmin", "Lock Task rigoroso preparado: $allowedPackages")
        } catch (e: Exception) {
            FocusGuardLogger.logError("DeviceOwner", "Falha ao preparar Lock Task rigoroso", e)
        }
    }

    /** Mantém somente o FocusGuard no allowlist após o fim do Pomodoro rigoroso. */
    fun clearStrictPomodoroLockTaskPackages() {
        if (!isDeviceOwnerActive()) return
        try {
            dpm.setLockTaskPackages(componentName, arrayOf(context.packageName))
            Log.d("FocusGuardAdmin", "Lock Task rigoroso limpo")
        } catch (e: Exception) {
            FocusGuardLogger.logError("DeviceOwner", "Falha ao limpar Lock Task rigoroso", e)
        }
    }

    /** Get Device Owner status information. */
    fun getStatusInfo(): String {
        val isAdmin = isDeviceAdminActive()
        val isOwner = isDeviceOwnerActive()
        val maintenance = maintenanceRemainingMillis()

        return buildString {
            appendLine("Device Admin Ativo: $isAdmin")
            appendLine("Device Owner Ativo: $isOwner")
            appendLine("Manutenção restante: ${maintenance / 1_000}s")
        }
    }

    /** Enforce strict device policies during an active block session. */
    fun enforceBlockingPolicies() {
        if (!isDeviceOwnerActive()) return
        try {
            setBlockingProtectionArmed(true)
            applyNuclearShield()
            Log.d("FocusGuardAdmin", "Políticas de sessão aplicadas")
        } catch (e: Exception) {
            FocusGuardLogger.logError("DeviceOwner", "Falha ao aplicar políticas de sessão", e)
        }
    }

    /** Clear strict device policies when block session ends. */
    fun clearBlockingPolicies() {
        if (!isDeviceOwnerActive()) return
        try {
            activeBlockRestrictionsForSdk(Build.VERSION.SDK_INT).forEach {
                dpm.clearUserRestriction(componentName, it)
            }
            setBlockingProtectionArmed(false)
            Log.d("FocusGuardAdmin", "Políticas de sessão removidas")
        } catch (e: Exception) {
            FocusGuardLogger.logError("DeviceOwner", "Falha ao remover políticas de sessão", e)
        }
    }

    /**
     * Restores only policies whose state is available in device-encrypted storage.
     * This method is safe during Direct Boot and deliberately avoids Room, encrypted
     * preferences and every other credential-encrypted dependency.
     */
    fun applyDirectBootShield() {
        if (!isDeviceOwnerActive()) return

        val interruptedMaintenance = DeviceOwnerMaintenanceGate.hasPersistedWindow(context)
        val interruptedArmedMaintenance = interruptedMaintenance &&
            DeviceOwnerMaintenanceGate.wasProtectionArmedWhenOpened(context)
        if (interruptedMaintenance) {
            // Maintenance never survives a reboot. The existing Direct-Boot flags remain the
            // source of truth; an idle maintenance reboot must not manufacture a new block.
            DeviceOwnerMaintenanceGate.revoke(context)
        }

        enforceTrustedAutomaticTime()
        ALWAYS_ON_RESTRICTIONS.forEach { restriction ->
            applyPolicySafely("direct_boot_add:$restriction") {
                dpm.addUserRestriction(componentName, restriction)
            }
        }
        enforceAppControlProtection()

        if (shouldRestoreActiveBlockAtDirectBoot(
                blockingProtectionArmed = isArmoredProtectionArmed(),
                interruptedMaintenance = interruptedArmedMaintenance
            )
        ) {
            activeBlockRestrictionsForSdk(Build.VERSION.SDK_INT).forEach { restriction ->
                applyPolicySafely("direct_boot_add:$restriction") {
                    dpm.addUserRestriction(componentName, restriction)
                }
            }
        }

        if (shouldRestoreAdultDnsAtDirectBoot(
                adultContentProtectionArmed = isAdultContentProtectionArmed(),
                pornographyCategoryActive = isPornographyCategoryActive()
            )
        ) {
            enforceAdultDns()
        }

        Log.d("FocusGuardAdmin", "Nuclear Shield restaurado no Direct Boot")
    }

    /**
     * Applies the persistent Device Owner shield or its reduced maintenance state.
     * Factory reset, safe boot and manual date/time changes remain blocked during maintenance.
     */
    fun applyNuclearShield() {
        migratePolicyStateToDeviceProtectedStorage()
        if (!isDeviceOwnerActive()) return

        val adultProtectionRequired = isAdultDnsProtectionRequired()
        setAdultContentProtectionArmed(adultProtectionRequired)
        val protectionArmed = isBlockingProtectionArmed() || adultProtectionRequired
        val maintenanceActive = DeviceOwnerMaintenanceGate.isTemporarilyUnlocked(context)

        when {
            maintenanceActive -> {
                if (protectionArmed) {
                    enforceTrustedAutomaticTime()
                    setRestrictions(ALWAYS_ON_RESTRICTIONS, enabled = true)
                } else {
                    setRestrictions(ALWAYS_ON_RESTRICTIONS, enabled = false)
                }
                setRestrictions(
                    activeBlockRestrictionsForSdk(Build.VERSION.SDK_INT),
                    enabled = false
                )
                relaxAppControlProtection()
                relaxAdultContentForMaintenance()
                Log.d("FocusGuardAdmin", "Nuclear Shield em modo de manutenção temporária")
            }
            protectionArmed -> {
                enforceTrustedAutomaticTime()
                setRestrictions(ALWAYS_ON_RESTRICTIONS, enabled = true)
                setRestrictions(
                    activeBlockRestrictionsForSdk(Build.VERSION.SDK_INT),
                    enabled = true
                )
                enforceAppControlProtection()
                if (adultProtectionRequired) {
                    if (!enforceAdultDns()) {
                        FocusGuardLogger.log(
                            "DeviceOwner",
                            "Filtro de pornografia ativo, mas o Android não confirmou a política DNS"
                        )
                    }
                } else {
                    clearAdultContentRestrictions()
                }
                Log.d("FocusGuardAdmin", "Nuclear Shield completo aplicado")
            }
            else -> {
                setRestrictions(ALWAYS_ON_RESTRICTIONS, enabled = false)
                setRestrictions(
                    activeBlockRestrictionsForSdk(Build.VERSION.SDK_INT),
                    enabled = false
                )
                relaxAppControlProtection()
                clearAdultContentRestrictions()
                Log.d("FocusGuardAdmin", "Device Owner pronto; nenhuma proteção está armada")
            }
        }
    }

    private fun setRestrictions(restrictions: Collection<String>, enabled: Boolean) {
        restrictions.forEach { restriction ->
            applyPolicySafely("${if (enabled) "add" else "clear"}:$restriction") {
                if (enabled) {
                    dpm.addUserRestriction(componentName, restriction)
                } else {
                    dpm.clearUserRestriction(componentName, restriction)
                }
            }
        }
    }

    private fun enforceTrustedAutomaticTime() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        applyPolicySafely("auto_time") { dpm.setAutoTimeEnabled(componentName, true) }
        applyPolicySafely("auto_timezone") { dpm.setAutoTimeZoneEnabled(componentName, true) }
    }

    private fun enforceAppControlProtection() {
        appControlRestrictionsForSdk(Build.VERSION.SDK_INT).forEach { restriction ->
            applyPolicySafely("add:$restriction") {
                dpm.addUserRestriction(componentName, restriction)
            }
        }
        applyPolicySafely("uninstall_blocked") {
            dpm.setUninstallBlocked(componentName, context.packageName, true)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            applyPolicySafely("user_control_disabled") {
                dpm.setUserControlDisabledPackages(componentName, listOf(context.packageName))
            }
        }
        enforceRuntimePermissionProtection()
    }

    private fun relaxAppControlProtection() {
        appControlRestrictionsForSdk(Build.VERSION.SDK_INT).forEach { restriction ->
            applyPolicySafely("clear:$restriction") {
                dpm.clearUserRestriction(componentName, restriction)
            }
        }
        applyPolicySafely("uninstall_allowed") {
            dpm.setUninstallBlocked(componentName, context.packageName, false)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            applyPolicySafely("user_control_enabled") {
                dpm.setUserControlDisabledPackages(componentName, emptyList())
            }
        }
        relaxRuntimePermissionProtection()
    }

    private fun enforceRuntimePermissionProtection() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        applyPolicySafely("grant_notifications") {
            dpm.setPermissionGrantState(
                componentName,
                context.packageName,
                Manifest.permission.POST_NOTIFICATIONS,
                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
            )
        }
    }

    private fun relaxRuntimePermissionProtection() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        applyPolicySafely("release_notifications") {
            dpm.setPermissionGrantState(
                componentName,
                context.packageName,
                Manifest.permission.POST_NOTIFICATIONS,
                DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT
            )
        }
    }

    private fun relaxAdultContentForMaintenance() {
        clearAdultContentRestrictions()
    }

    private fun clearAdultContentRestrictions() {
        adultContentRestrictionsForSdk(Build.VERSION.SDK_INT).forEach { restriction ->
            applyPolicySafely("clear:$restriction") {
                dpm.clearUserRestriction(componentName, restriction)
            }
        }
    }

    /** Clears every FocusGuard Device Owner policy before a legitimate renounce. */
    fun revokeNuclearShield() {
        if (!isDeviceOwnerActive() || !isMaintenanceActive()) return

        allShieldRestrictionsForSdk(Build.VERSION.SDK_INT).forEach { restriction ->
            applyPolicySafely("clear:$restriction") {
                dpm.clearUserRestriction(componentName, restriction)
            }
        }
        applyPolicySafely("uninstall_allowed") {
            dpm.setUninstallBlocked(componentName, context.packageName, false)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            applyPolicySafely("user_control_enabled") {
                dpm.setUserControlDisabledPackages(componentName, emptyList())
            }
        }
        relaxRuntimePermissionProtection()
        setBlockingProtectionArmed(false)
        setAdultContentProtectionArmed(false)
        Log.d("FocusGuardAdmin", "Nuclear Shield revogado para remoção legítima")
    }

    private fun setBlockingProtectionArmed(armed: Boolean) {
        val saved = policyStatePreferences.edit()
            .putBoolean(BLOCKING_PROTECTION_ARMED_KEY, armed)
            .commit()
        if (!saved) {
            FocusGuardLogger.log(
                "DeviceOwner",
                "Não foi possível persistir o estado da proteção de bloqueio"
            )
        }
    }

    fun isAdultContentProtectionArmed(): Boolean =
        policyStatePreferences.getBoolean(ADULT_CONTENT_PROTECTION_ARMED_KEY, false)

    private fun setAdultContentProtectionArmed(armed: Boolean) {
        val saved = policyStatePreferences.edit()
            .putBoolean(ADULT_CONTENT_PROTECTION_ARMED_KEY, armed)
            .commit()
        if (!saved) {
            FocusGuardLogger.log(
                "DeviceOwner",
                "Não foi possível persistir o estado da proteção de conteúdo adulto"
            )
        }
    }

    /** Copies the non-sensitive policy flags written by versions before Direct Boot support. */
    private fun migratePolicyStateToDeviceProtectedStorage() {
        if (!policyStateContext.isDeviceProtectedStorage || !isUserUnlocked()) return
        if (policyStatePreferences.getInt(
                POLICY_STATE_STORAGE_VERSION_KEY,
                0
            ) >= POLICY_STATE_STORAGE_VERSION
        ) return

        val legacyPreferences = context.getSharedPreferences(
            POLICY_STATE_PREFERENCES,
            Context.MODE_PRIVATE
        )
        val editor = policyStatePreferences.edit()
        listOf(
            BLOCKING_PROTECTION_ARMED_KEY,
            ADULT_CONTENT_PROTECTION_ARMED_KEY,
            PORNOGRAPHY_CATEGORY_ACTIVE_KEY,
            PREVIOUS_PRIVATE_DNS_CAPTURED_KEY
        ).forEach { key ->
            if (!policyStatePreferences.contains(key) && legacyPreferences.contains(key)) {
                editor.putBoolean(key, legacyPreferences.getBoolean(key, false))
            }
        }
        if (!policyStatePreferences.contains(PREVIOUS_PRIVATE_DNS_MODE_KEY) &&
            legacyPreferences.contains(PREVIOUS_PRIVATE_DNS_MODE_KEY)
        ) {
            editor.putInt(
                PREVIOUS_PRIVATE_DNS_MODE_KEY,
                legacyPreferences.getInt(PREVIOUS_PRIVATE_DNS_MODE_KEY, 0)
            )
        }
        if (!policyStatePreferences.contains(PREVIOUS_PRIVATE_DNS_HOST_KEY) &&
            legacyPreferences.contains(PREVIOUS_PRIVATE_DNS_HOST_KEY)
        ) {
            editor.putString(
                PREVIOUS_PRIVATE_DNS_HOST_KEY,
                legacyPreferences.getString(PREVIOUS_PRIVATE_DNS_HOST_KEY, null)
            )
        }
        val saved = editor
            .putInt(POLICY_STATE_STORAGE_VERSION_KEY, POLICY_STATE_STORAGE_VERSION)
            .commit()
        if (!saved) {
            FocusGuardLogger.log(
                "DeviceOwner",
                "Não foi possível migrar o estado de política para o Direct Boot"
            )
        }
    }

    private fun isUserUnlocked(): Boolean = runCatching {
        (context.getSystemService(Context.USER_SERVICE) as UserManager).isUserUnlocked
    }.getOrDefault(true)

    private inline fun applyPolicySafely(name: String, operation: () -> Unit) {
        runCatching(operation).onFailure { error ->
            FocusGuardLogger.logError("DeviceOwner", "Falha na política $name", error)
        }
    }

    /**
     * Aplica bloqueio preventivo no renderer dos navegadores gerenciáveis.
     *
     * `URLBlocklist` é a camada mais forte disponível sem VPN para Chrome e
     * Edge em um aparelho Device Owner. A navegação privada também é suspensa
     * enquanto há regras ativas, evitando uma superfície de bypass. Os demais
     * navegadores continuam protegidos pelo AccessibilityService.
     */
    suspend fun enforceWebsiteRestrictions(domains: List<String>) {
        if (!isDeviceOwnerActive()) {
            invalidateWebsitePolicyCache()
            return
        }
        val configuredRules = if (AuthManager.isAdultFilterConfigured(context)) {
            domains + PredefinedWebsites.PORNOGRAPHY_RULE
        } else {
            domains
        }
        val normalizedRules = WebsiteBlocker.normalizeRules(configuredRules).sorted()
        val allManagedFilters = WebsiteBlocker.managedBrowserFiltersFor(normalizedRules).toList()
        val managedFilters = allManagedFilters.take(MAX_MANAGED_URLS)
        if (managedFilters.size < allManagedFilters.size) {
            Log.w(
                "FocusGuardNuclear",
                "URLBlocklist limitada aos primeiros $MAX_MANAGED_URLS filtros"
            )
        }
        applyWebsiteRestrictions(
            domains = managedFilters,
            requireSystemDns = isAdultDnsProtectionRequired()
        )
    }

    /** Remove somente as políticas de navegador controladas pelo FocusGuard. */
    suspend fun clearWebsiteRestrictions() {
        if (!isDeviceOwnerActive()) {
            invalidateWebsitePolicyCache()
            return
        }
        applyWebsiteRestrictions(
            domains = emptyList(),
            requireSystemDns = isAdultDnsProtectionRequired()
        )
    }

    /** Força nova sincronização após instalação, remoção ou atualização do navegador. */
    suspend fun invalidateWebsitePolicyCache() {
        websitePolicyMutex.withLock {
            lastWebsitePolicySignature = null
        }
    }

    private suspend fun applyWebsiteRestrictions(
        domains: List<String>,
        requireSystemDns: Boolean
    ) {
        websitePolicyMutex.withLock {
            withContext(Dispatchers.IO) {
                val chromePackages = CHROME_MANAGED_PACKAGES.filter(::isPackageInstalled)
                val edgePackages = EDGE_MANAGED_PACKAGES.filter(::isPackageInstalled)
                val targets = (chromePackages + edgePackages).sorted()
                val managedFilters = domains.map { domain ->
                    if (':' in domain) "[$domain]" else domain
                }
                val signature = managedFilters.joinToString("\u0000") +
                    "|" + targets.joinToString("\u0000") +
                    "|systemDns=$requireSystemDns"
                if (signature == lastWebsitePolicySignature) return@withContext

                var allWritesSucceeded = true
                targets.forEach { packageName ->
                    val privateModePolicy = if (packageName in EDGE_MANAGED_PACKAGES) {
                        "InPrivateModeAvailability"
                    } else {
                        "IncognitoModeAvailability"
                    }

                    try {
                        val restrictions = buildManagedBrowserRestrictions(
                            existing = dpm.getApplicationRestrictions(
                                componentName,
                                packageName
                            ),
                            managedFilters = managedFilters,
                            privateModePolicy = privateModePolicy,
                            requireSystemDns = requireSystemDns
                        )
                        dpm.setApplicationRestrictions(componentName, packageName, restrictions)
                        val stored = dpm.getApplicationRestrictions(componentName, packageName)
                        val storedDomains = stored.getStringArray("URLBlocklist")?.toList().orEmpty()
                        val dnsPolicyVerified = if (requireSystemDns) {
                            stored.getString("DnsOverHttpsMode") == "off" &&
                                !stored.containsKey("DnsOverHttpsTemplates")
                        } else {
                            !stored.containsKey("DnsOverHttpsMode") &&
                                !stored.containsKey("DnsOverHttpsTemplates")
                        }
                        val verified = if (managedFilters.isEmpty()) {
                            !stored.containsKey("URLBlocklist") &&
                                !stored.containsKey("IncognitoModeAvailability") &&
                                !stored.containsKey("InPrivateModeAvailability") &&
                                dnsPolicyVerified
                        } else {
                            storedDomains == managedFilters &&
                                stored.getInt(privateModePolicy, -1) == 1 &&
                                dnsPolicyVerified
                        }
                        if (!verified) {
                            allWritesSucceeded = false
                            FocusGuardLogger.log(
                                "DeviceOwner",
                                "O navegador $packageName não confirmou a política de URLs"
                            )
                        }
                    } catch (error: android.os.TransactionTooLargeException) {
                        allWritesSucceeded = false
                        FocusGuardLogger.logError(
                            "DeviceOwner",
                            "URLBlocklist excedeu o limite Binder em $packageName",
                            error
                        )
                    } catch (error: Exception) {
                        allWritesSucceeded = false
                        FocusGuardLogger.logError(
                            "DeviceOwner",
                            "Falha ao aplicar URLBlocklist em $packageName",
                            error
                        )
                    }
                }

                if (allWritesSucceeded) {
                    lastWebsitePolicySignature = signature
                    Log.d(
                        "FocusGuardNuclear",
                        "URLBlocklist sincronizada: ${managedFilters.size} filtros em " +
                            "${targets.size} navegadores"
                    )
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun isPackageInstalled(packageName: String): Boolean {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    packageName,
                    android.content.pm.PackageManager.PackageInfoFlags.of(0L)
                )
            } else {
                context.packageManager.getPackageInfo(packageName, 0)
            }
        }.isSuccess
    }

    /** Enforce and lock Global Private DNS using CleanBrowsing Family Filter. */
    fun enforceAdultDns(): Boolean {
        if (!isDeviceOwnerActive()) return false
        setAdultContentProtectionArmed(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val alreadyConfigured =
                    dpm.getGlobalPrivateDnsMode(componentName) ==
                        DevicePolicyManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME &&
                        dpm.getGlobalPrivateDnsHost(componentName) == ADULT_DNS_HOST
                if (!alreadyConfigured) {
                    val result = dpm.setGlobalPrivateDnsModeSpecifiedHost(
                        componentName,
                        ADULT_DNS_HOST
                    )
                    if (result != DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR) {
                        FocusGuardLogger.log(
                            "DeviceOwner",
                            "Android recusou o DNS adulto (código=$result)"
                        )
                        return false
                    }
                }

                dpm.addUserRestriction(componentName, UserManager.DISALLOW_CONFIG_PRIVATE_DNS)
                dpm.addUserRestriction(componentName, UserManager.DISALLOW_CONFIG_VPN)
                val restrictions = dpm.getUserRestrictions(componentName)
                val verified =
                    dpm.getGlobalPrivateDnsMode(componentName) ==
                        DevicePolicyManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME &&
                        dpm.getGlobalPrivateDnsHost(componentName) == ADULT_DNS_HOST &&
                        restrictions.getBoolean(UserManager.DISALLOW_CONFIG_PRIVATE_DNS, false) &&
                        restrictions.getBoolean(UserManager.DISALLOW_CONFIG_VPN, false)
                if (verified) {
                    Log.d(
                        "FocusGuardAdmin",
                        "DNS adulto aplicado; alterações de DNS e VPN bloqueadas"
                    )
                } else {
                    FocusGuardLogger.log(
                        "DeviceOwner",
                        "Android não confirmou o DNS adulto ou as proteções anti-bypass"
                    )
                }
                return verified
            } catch (e: Exception) {
                FocusGuardLogger.logError("DeviceOwner", "Erro ao aplicar DNS", e)
            }
        } else {
            FocusGuardLogger.log("DeviceOwner", "DNS Global não suportado nesta versão do Android")
        }
        return false
    }

    /**
     * Mantém o DNS familiar somente enquanto a categoria Pornografia estiver
     * em bloqueio efetivo. A escolha global de filtro adulto continua tendo
     * precedência e permanece ativa 24/7.
     */
    fun setPornographyCategoryActive(active: Boolean) {
        val wasActive = isPornographyCategoryActive()
        if (active) {
            capturePrivateDnsBeforeCategoryIfNeeded()
            policyStatePreferences.edit()
                .putBoolean(PORNOGRAPHY_CATEGORY_ACTIVE_KEY, true)
                .commit()
            setAdultContentProtectionArmed(true)
            if (isDeviceOwnerActive() && !isMaintenanceActive()) {
                enforceAdultDns()
            }
            return
        }

        policyStatePreferences.edit()
            .putBoolean(PORNOGRAPHY_CATEGORY_ACTIVE_KEY, false)
            .commit()
        if (wasActive && isDeviceOwnerActive() &&
            !AuthManager.isAdultFilterConfigured(context)
        ) {
            setAdultContentProtectionArmed(false)
            restorePrivateDnsAfterCategory()
        } else {
            setAdultContentProtectionArmed(AuthManager.isAdultFilterConfigured(context))
            clearCapturedPrivateDnsState()
        }
    }

    private fun isPornographyCategoryActive(): Boolean {
        return policyStatePreferences.getBoolean(PORNOGRAPHY_CATEGORY_ACTIVE_KEY, false)
    }

    private fun isAdultDnsProtectionRequired(): Boolean {
        return requiresAdultDns(
            globalAdultFilterEnabled = AuthManager.isAdultFilterConfigured(context),
            pornographyCategoryActive = isPornographyCategoryActive()
        )
    }

    private fun capturePrivateDnsBeforeCategoryIfNeeded() {
        if (!isDeviceOwnerActive() || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            AuthManager.isAdultFilterConfigured(context) ||
            policyStatePreferences.getBoolean(PREVIOUS_PRIVATE_DNS_CAPTURED_KEY, false)
        ) return

        runCatching {
            val previousMode = dpm.getGlobalPrivateDnsMode(componentName)
            val previousHost = if (
                previousMode == DevicePolicyManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME
            ) {
                dpm.getGlobalPrivateDnsHost(componentName).orEmpty()
            } else {
                ""
            }
            policyStatePreferences.edit()
                .putBoolean(PREVIOUS_PRIVATE_DNS_CAPTURED_KEY, true)
                .putInt(PREVIOUS_PRIVATE_DNS_MODE_KEY, previousMode)
                .putString(PREVIOUS_PRIVATE_DNS_HOST_KEY, previousHost)
                .commit()
        }.onFailure { error ->
            FocusGuardLogger.logError(
                "DeviceOwner",
                "Falha ao guardar DNS anterior à categoria Pornografia",
                error
            )
        }
    }

    private fun restorePrivateDnsAfterCategory() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            clearAdultContentRestrictions()
            clearCapturedPrivateDnsState()
            return
        }

        runCatching {
            clearAdultContentRestrictions()
            val captured = policyStatePreferences.getBoolean(
                PREVIOUS_PRIVATE_DNS_CAPTURED_KEY,
                false
            )
            val previousMode = policyStatePreferences.getInt(
                PREVIOUS_PRIVATE_DNS_MODE_KEY,
                DevicePolicyManager.PRIVATE_DNS_MODE_OPPORTUNISTIC
            )
            val previousHost = policyStatePreferences.getString(
                PREVIOUS_PRIVATE_DNS_HOST_KEY,
                ""
            ).orEmpty()
            if (captured &&
                previousMode == DevicePolicyManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME &&
                previousHost.isNotBlank()
            ) {
                val result = dpm.setGlobalPrivateDnsModeSpecifiedHost(
                    componentName,
                    previousHost
                )
                if (result != DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR) {
                    dpm.setGlobalPrivateDnsModeOpportunistic(componentName)
                }
            } else {
                dpm.setGlobalPrivateDnsModeOpportunistic(componentName)
            }
            clearCapturedPrivateDnsState()
            Log.d("FocusGuardAdmin", "DNS anterior restaurado após a categoria Pornografia")
        }.onFailure { error ->
            FocusGuardLogger.logError(
                "DeviceOwner",
                "Falha ao restaurar DNS após a categoria Pornografia",
                error
            )
        }
    }

    private fun clearCapturedPrivateDnsState() {
        policyStatePreferences.edit()
            .remove(PREVIOUS_PRIVATE_DNS_CAPTURED_KEY)
            .remove(PREVIOUS_PRIVATE_DNS_MODE_KEY)
            .remove(PREVIOUS_PRIVATE_DNS_HOST_KEY)
            .apply()
    }

    /** Clear Global Private DNS to Opportunistic Mode. */
    fun clearAdultDns() {
        if (!isDeviceOwnerActive()) return
        if (isPornographyCategoryActive()) {
            enforceAdultDns()
            return
        }
        setAdultContentProtectionArmed(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                clearAdultContentRestrictions()
                dpm.setGlobalPrivateDnsModeOpportunistic(componentName)
                Log.d("FocusGuardAdmin", "DNS Global removido via DPM")
            } catch (e: Exception) {
                FocusGuardLogger.logError("DeviceOwner", "Erro ao remover DNS", e)
            }
        }
    }

    /** Renounce Device Owner privileges only from an authenticated maintenance window. */
    @Suppress("DEPRECATION")
    fun renounceDeviceOwner() {
        if (!isDeviceOwnerActive()) return
        if (!ArmoredProtectionPolicy.canRenounceDeviceOwner(protectionPhase())) {
            Toast.makeText(
                context,
                context.getString(R.string.device_owner_maintenance_required_to_revoke),
                Toast.LENGTH_LONG
            ).show()
            return
        }

        try {
            clearBlockingPolicies()
            revokeNuclearShield()
            setPornographyCategoryActive(false)
            if (AuthManager.isAdultFilterConfigured(context)) {
                clearAdultDns()
            }
            dpm.clearDeviceOwnerApp(context.packageName)
            DeviceOwnerMaintenanceGate.revoke(context)
            Toast.makeText(
                context,
                context.getString(R.string.acesso_device_owner_revogado),
                Toast.LENGTH_SHORT
            ).show()
        } catch (e: Exception) {
            Toast.makeText(
                context,
                context.getString(R.string.falha_ao_revogar_device_owner_e_message),
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
