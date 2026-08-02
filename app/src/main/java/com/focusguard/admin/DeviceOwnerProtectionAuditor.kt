package com.focusguard.admin

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserManager
import com.focusguard.security.AuthManager
import com.focusguard.security.DeviceOwnerMaintenanceGate
import com.focusguard.utils.PermissionUtils

/** Reads back the policies actually accepted by Android/OEM instead of trusting write calls. */
class DeviceOwnerProtectionAuditor(context: Context) {

    private val appContext = context.applicationContext
    private val dpm = appContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val userManager = appContext.getSystemService(Context.USER_SERVICE) as UserManager
    private val admin = FocusGuardDeviceAdminReceiver.getComponentName(appContext)

    fun inspect(): DeviceOwnerProtectionDiagnostics {
        val deviceAdminActive = runCatching { dpm.isAdminActive(admin) }.getOrDefault(false)
        val deviceOwnerActive = runCatching {
            dpm.isDeviceOwnerApp(appContext.packageName)
        }.getOrDefault(false)
        val adultFilterEnabled = AuthManager.isAdultFilterConfigured(appContext)
        val manager = DeviceOwnerManager.getInstance(appContext)
        val blockingProtectionArmed = manager.isBlockingProtectionArmed()
        val adultContentProtectionArmed = manager.isAdultContentProtectionArmed()
        val protectionArmed = blockingProtectionArmed || adultContentProtectionArmed
        val accessibilityServiceEnabled =
            PermissionUtils.isAccessibilityServiceEnabled(appContext)
        val usageAccessEnabled = PermissionUtils.isUsageAccessEnabled(appContext)
        val batteryOptimizationExempt =
            PermissionUtils.isBatteryOptimizationIgnored(appContext)

        if (!deviceOwnerActive) {
            return DeviceOwnerProtectionDiagnostics(
                deviceAdminActive = deviceAdminActive,
                deviceOwnerActive = false,
                maintenanceActive = false,
                protectionArmed = protectionArmed,
                blockingProtectionArmed = false,
                adultContentProtectionArmed = adultContentProtectionArmed,
                uninstallBlocked = false,
                appsControlBlocked = false,
                userControlDisabled = null,
                factoryResetBlocked = false,
                safeBootBlocked = false,
                addUserBlocked = false,
                removeUserBlocked = false,
                userSwitchBlocked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) false else null,
                privateProfileCreationBlocked = if (
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM
                ) false else null,
                dateTimeChangesBlocked = false,
                grantAdminBlocked = null,
                notificationPermissionLocked = if (
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ) false else null,
                accessibilityServiceEnabled = accessibilityServiceEnabled,
                usageAccessEnabled = usageAccessEnabled,
                batteryOptimizationExempt = batteryOptimizationExempt,
                automaticTimeEnabled = null,
                automaticTimeZoneEnabled = null,
                adultFilterEnabled = adultFilterEnabled,
                adultDnsEnforced = if (adultContentProtectionArmed) false else null,
                privateDnsChangesBlocked = if (adultContentProtectionArmed) false else null,
                vpnConfigurationBlocked = if (adultContentProtectionArmed) false else null
            )
        }

        val maintenanceActive = DeviceOwnerMaintenanceGate.isTemporarilyUnlocked(appContext)

        return DeviceOwnerProtectionDiagnostics(
            deviceAdminActive = deviceAdminActive,
            deviceOwnerActive = true,
            maintenanceActive = maintenanceActive,
            protectionArmed = protectionArmed,
            blockingProtectionArmed = blockingProtectionArmed,
            adultContentProtectionArmed = adultContentProtectionArmed,
            uninstallBlocked = runCatching {
                dpm.isUninstallBlocked(admin, appContext.packageName)
            }.getOrDefault(false),
            appsControlBlocked = restrictionState(UserManager.DISALLOW_APPS_CONTROL),
            userControlDisabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching {
                    appContext.packageName in dpm.getUserControlDisabledPackages(admin)
                }.getOrDefault(false)
            } else {
                null
            },
            factoryResetBlocked = restrictionState(UserManager.DISALLOW_FACTORY_RESET),
            safeBootBlocked = restrictionState(UserManager.DISALLOW_SAFE_BOOT),
            addUserBlocked = restrictionState(UserManager.DISALLOW_ADD_USER),
            removeUserBlocked = restrictionState(UserManager.DISALLOW_REMOVE_USER),
            userSwitchBlocked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                restrictionState(UserManager.DISALLOW_USER_SWITCH)
            } else {
                null
            },
            privateProfileCreationBlocked = if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM
            ) {
                restrictionState(UserManager.DISALLOW_ADD_PRIVATE_PROFILE)
            } else {
                null
            },
            dateTimeChangesBlocked = restrictionState(
                UserManager.DISALLOW_CONFIG_DATE_TIME
            ),
            grantAdminBlocked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                restrictionState(UserManager.DISALLOW_GRANT_ADMIN)
            } else {
                null
            },
            notificationPermissionLocked = if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            ) {
                runCatching {
                    dpm.getPermissionGrantState(
                        admin,
                        appContext.packageName,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) == DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED &&
                        appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                        PackageManager.PERMISSION_GRANTED
                }.getOrDefault(false)
            } else {
                null
            },
            accessibilityServiceEnabled = accessibilityServiceEnabled,
            usageAccessEnabled = usageAccessEnabled,
            batteryOptimizationExempt = batteryOptimizationExempt,
            automaticTimeEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching { dpm.getAutoTimeEnabled(admin) }.getOrDefault(false)
            } else {
                null
            },
            automaticTimeZoneEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching { dpm.getAutoTimeZoneEnabled(admin) }.getOrDefault(false)
            } else {
                null
            },
            adultFilterEnabled = adultFilterEnabled,
            adultDnsEnforced = if (
                adultContentProtectionArmed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            ) {
                runCatching {
                    dpm.getGlobalPrivateDnsMode(admin) ==
                        DevicePolicyManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME &&
                        dpm.getGlobalPrivateDnsHost(admin) == DeviceOwnerManager.ADULT_DNS_HOST
                }.getOrDefault(false)
            } else {
                null
            },
            privateDnsChangesBlocked = if (
                adultContentProtectionArmed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            ) {
                restrictionState(UserManager.DISALLOW_CONFIG_PRIVATE_DNS)
            } else {
                null
            },
            vpnConfigurationBlocked = if (adultContentProtectionArmed) {
                restrictionState(UserManager.DISALLOW_CONFIG_VPN)
            } else {
                null
            }
        )
    }

    private fun restrictionState(key: String): Boolean = runCatching {
        userManager.hasUserRestriction(key)
    }.getOrDefault(false)
}
