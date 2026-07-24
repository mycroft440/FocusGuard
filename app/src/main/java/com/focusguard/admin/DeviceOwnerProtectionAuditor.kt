package com.focusguard.admin

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.UserManager
import com.focusguard.security.DeviceOwnerMaintenanceGate

/** Reads back the policies actually accepted by Android/OEM instead of trusting write calls. */
class DeviceOwnerProtectionAuditor(context: Context) {

    private val appContext = context.applicationContext
    private val dpm = appContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val admin = FocusGuardDeviceAdminReceiver.getComponentName(appContext)

    fun inspect(): DeviceOwnerProtectionDiagnostics {
        val deviceAdminActive = runCatching { dpm.isAdminActive(admin) }.getOrDefault(false)
        val deviceOwnerActive = runCatching {
            dpm.isDeviceOwnerApp(appContext.packageName)
        }.getOrDefault(false)

        if (!deviceOwnerActive) {
            return DeviceOwnerProtectionDiagnostics(
                deviceAdminActive = deviceAdminActive,
                deviceOwnerActive = false,
                maintenanceActive = false,
                uninstallBlocked = false,
                userControlDisabled = null,
                factoryResetBlocked = false,
                safeBootBlocked = false,
                dateTimeChangesBlocked = false,
                grantAdminBlocked = null,
                automaticTimeEnabled = null,
                automaticTimeZoneEnabled = null
            )
        }

        val maintenanceActive = DeviceOwnerMaintenanceGate.isTemporarilyUnlocked(appContext)
        val restrictions = runCatching { dpm.getUserRestrictions(admin) }.getOrDefault(Bundle())

        return DeviceOwnerProtectionDiagnostics(
            deviceAdminActive = deviceAdminActive,
            deviceOwnerActive = true,
            maintenanceActive = maintenanceActive,
            uninstallBlocked = runCatching {
                dpm.isUninstallBlocked(admin, appContext.packageName)
            }.getOrNull(),
            userControlDisabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching {
                    appContext.packageName in dpm.getUserControlDisabledPackages(admin)
                }.getOrNull()
            } else {
                null
            },
            factoryResetBlocked = restrictions.policyState(UserManager.DISALLOW_FACTORY_RESET),
            safeBootBlocked = restrictions.policyState(UserManager.DISALLOW_SAFE_BOOT),
            dateTimeChangesBlocked = restrictions.policyState(
                UserManager.DISALLOW_CONFIG_DATE_TIME
            ),
            grantAdminBlocked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                restrictions.policyState(UserManager.DISALLOW_GRANT_ADMIN)
            } else {
                null
            },
            automaticTimeEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching { dpm.getAutoTimeEnabled(admin) }.getOrNull()
            } else {
                null
            },
            automaticTimeZoneEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching { dpm.getAutoTimeZoneEnabled(admin) }.getOrNull()
            } else {
                null
            }
        )
    }

    private fun Bundle.policyState(key: String): Boolean = getBoolean(key, false)
}
