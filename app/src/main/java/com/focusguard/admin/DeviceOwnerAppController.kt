package com.focusguard.admin

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserManager
import android.util.Log
import com.focusguard.focusmode.FocusModeStore
import com.focusguard.utils.FocusGuardLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Package suspension, device locking, and Lock Task policy operations. */
@Singleton
class DeviceOwnerAppController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val access: DeviceOwnerPolicyAccess
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val suspendedAppsMutex = Mutex()
    private val suspendedAppsPreferences by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        context.getSharedPreferences(SUSPENDED_APPS_PREFERENCES, Context.MODE_PRIVATE)
    }

    suspend fun syncSuspendedApps(
        allAppsInSessions: List<String>,
        appsToBlockNow: List<String>,
        allowedSystemApps: Set<String>
    ) {
        if (!access.isDeviceOwnerActive()) return

        suspendedAppsMutex.withLock {
            try {
                val myPackage = context.packageName
                val previouslyManaged = managedSuspendedApps()
                val filteredToBlock = appsToBlockNow.distinct().filter { packageName ->
                    if (packageName == myPackage || packageName == "com.focusguard") {
                        return@filter false
                    }
                    if (packageName in DeviceOwnerPolicyCatalog.sacredWhitelist) {
                        return@filter false
                    }
                    try {
                        val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
                        val isSystemApp =
                            (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                        !isSystemApp || packageName in allowedSystemApps
                    } catch (_: PackageManager.NameNotFoundException) {
                        false
                    }
                }
                val desired = filteredToBlock.toSet()
                val managedAfterSync = previouslyManaged.toMutableSet()
                val appsToUnblock = (allAppsInSessions + previouslyManaged)
                    .distinct()
                    .filter { it !in desired }

                if (appsToUnblock.isNotEmpty()) {
                    val failed = access.dpm.setPackagesSuspended(
                        access.componentName,
                        appsToUnblock.toTypedArray(),
                        false
                    ).toSet()
                    managedAfterSync.removeAll(appsToUnblock.toSet() - failed)
                    Log.d(
                        TAG,
                        "Apps desbloqueados diferencialmente: " +
                            "${appsToUnblock.size - failed.size}"
                    )
                }

                if (filteredToBlock.isNotEmpty()) {
                    val failed = access.dpm.setPackagesSuspended(
                        access.componentName,
                        filteredToBlock.toTypedArray(),
                        true
                    ).toSet()
                    managedAfterSync.addAll(filteredToBlock.toSet() - failed)
                    Log.d(TAG, "Apps suspensos: ${filteredToBlock.size - failed.size}")
                }
                saveManagedSuspendedApps(managedAfterSync)
            } catch (error: Exception) {
                Log.e(TAG, "Falha na sincronização diferencial de apps", error)
                throw error
            }
        }
    }

    fun unblockApps(packageNames: List<String>) {
        if (!access.isDeviceOwnerActive() || packageNames.isEmpty()) return

        scope.launch {
            suspendedAppsMutex.withLock {
                try {
                    val failed = access.dpm.setPackagesSuspended(
                        access.componentName,
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

    fun lockDevice() {
        if (!access.isDeviceAdminActive()) return
        try {
            access.dpm.lockNow()
        } catch (error: Exception) {
            FocusGuardLogger.log("Admin", "Erro ao travar dispositivo: ${error.message}")
        }
    }

    fun prepareStrictPomodoroLockTaskPackages() {
        if (!access.isDeviceOwnerActive()) return
        try {
            val allowedPackages = (
                listOf(context.packageName) + DeviceOwnerPolicyCatalog.phoneLockTaskPackages
                ).distinct().filter { packageName ->
                packageName == context.packageName || isPackageInstalled(packageName)
            }
            access.dpm.setLockTaskPackages(
                access.componentName,
                allowedPackages.toTypedArray()
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                access.dpm.setLockTaskFeatures(
                    access.componentName,
                    DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS
                )
            }
            Log.d(TAG, "Lock Task rigoroso preparado: $allowedPackages")
        } catch (error: Exception) {
            FocusGuardLogger.logError(
                "DeviceOwner",
                "Falha ao preparar Lock Task rigoroso",
                error
            )
        }
    }

    fun clearStrictPomodoroLockTaskPackages() {
        if (!access.isDeviceOwnerActive()) return
        try {
            access.dpm.setLockTaskPackages(
                access.componentName,
                arrayOf(context.packageName)
            )
            Log.d(TAG, "Lock Task rigoroso limpo")
        } catch (error: Exception) {
            FocusGuardLogger.logError(
                "DeviceOwner",
                "Falha ao limpar Lock Task rigoroso",
                error
            )
        }
    }

    fun prepareFocusModeLockTaskPackages(allowedPackages: Collection<String>): Boolean {
        if (!access.isDeviceOwnerActive() ||
            !DeviceOwnerPolicyCatalog.supportsStrictFocusModeLockdown(Build.VERSION.SDK_INT)
        ) {
            return false
        }
        return try {
            val installedAllowlist = (allowedPackages + context.packageName)
                .asSequence()
                .filter(String::isNotBlank)
                .distinct()
                .filter { it == context.packageName || isPackageInstalled(it) }
                .toList()
            access.dpm.setLockTaskPackages(
                access.componentName,
                installedAllowlist.toTypedArray()
            )
            access.dpm.setLockTaskFeatures(
                access.componentName,
                DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS
            )
            access.dpm.addUserRestriction(
                access.componentName,
                UserManager.DISALLOW_SAFE_BOOT
            )
            isFocusModeSystemLockdownConfirmed().also { confirmed ->
                if (confirmed) Log.d(TAG, "Modo Foco preparado: $installedAllowlist")
            }
        } catch (error: Exception) {
            FocusGuardLogger.logError(
                "FocusMode",
                "Falha ao configurar a lista do Lock Task",
                error
            )
            false
        }
    }

    fun clearFocusModeLockTaskPackages() {
        if (!access.isDeviceOwnerActive()) return
        runCatching {
            access.dpm.setLockTaskPackages(access.componentName, emptyArray<String>())
        }.onFailure { error ->
            FocusGuardLogger.logError(
                "FocusMode",
                "Falha ao encerrar o Lock Task do Modo Foco",
                error
            )
        }
    }

    fun isPackageSuspendedByFocusMode(packageName: String): Boolean {
        if (!access.isDeviceOwnerActive() || packageName.isBlank()) return false
        return runCatching {
            access.dpm.isPackageSuspended(access.componentName, packageName)
        }.getOrDefault(false)
    }

    fun isFocusModeLockTaskPermitted(): Boolean =
        access.isDeviceOwnerActive() && runCatching {
            access.dpm.isLockTaskPermitted(context.packageName)
        }.getOrDefault(false)

    fun isFocusModeSystemLockdownConfirmed(): Boolean {
        if (!access.isDeviceOwnerActive() ||
            !DeviceOwnerPolicyCatalog.supportsStrictFocusModeLockdown(Build.VERSION.SDK_INT)
        ) {
            return false
        }
        return runCatching {
            access.dpm.isLockTaskPermitted(context.packageName) &&
                DeviceOwnerPolicyCatalog.lockTaskFeaturesKeepOnlyGlobalActions(
                    access.dpm.getLockTaskFeatures(access.componentName)
                ) &&
                access.dpm.getUserRestrictions(access.componentName).getBoolean(
                    UserManager.DISALLOW_SAFE_BOOT,
                    false
                )
        }.onFailure { error ->
            FocusGuardLogger.logError(
                "FocusMode",
                "Falha ao confirmar o bloqueio de sistema do Modo Foco",
                error
            )
        }.getOrDefault(false)
    }

    fun applyFocusModeAtDirectBoot(): Boolean {
        if (!access.isDeviceOwnerActive()) return false
        val session = FocusModeStore.readSession(context) ?: return false
        if (!session.isActive()) return false

        return try {
            check(prepareFocusModeLockTaskPackages(session.allowedPackages))
            val candidates = session.blockedPackages
                .filterNot(DeviceOwnerPolicyCatalog.sacredWhitelist::contains)
                .filter(::isPackageInstalled)
            if (candidates.isNotEmpty()) {
                access.dpm.setPackagesSuspended(
                    access.componentName,
                    candidates.toTypedArray(),
                    true
                )
            }
            true
        } catch (error: Exception) {
            FocusGuardLogger.logError(
                "FocusMode",
                "Falha ao restaurar o Modo Foco no Direct Boot",
                error
            )
            false
        }
    }

    internal fun managedSuspendedApps(): Set<String> = suspendedAppsPreferences
        .getStringSet(MANAGED_SUSPENDED_APPS_KEY, emptySet())
        .orEmpty()
        .toSet()

    internal fun clearManagedSuspendedApps() {
        saveManagedSuspendedApps(emptySet())
    }

    @Suppress("DEPRECATION")
    internal fun isPackageInstalled(packageName: String): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(0L)
            )
        } else {
            context.packageManager.getPackageInfo(packageName, 0)
        }
    }.isSuccess

    private fun saveManagedSuspendedApps(packageNames: Set<String>) {
        suspendedAppsPreferences.edit()
            .putStringSet(MANAGED_SUSPENDED_APPS_KEY, packageNames)
            .apply()
    }

    private companion object {
        const val TAG = "FocusGuardAdmin"
        const val SUSPENDED_APPS_PREFERENCES = "focusguard_suspended_apps"
        const val MANAGED_SUSPENDED_APPS_KEY = "managed_packages"
    }
}
