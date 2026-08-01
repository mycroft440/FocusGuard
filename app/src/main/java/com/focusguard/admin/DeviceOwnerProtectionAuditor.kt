package com.focusguard.admin

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.UserManager
import com.focusguard.security.AuthManager
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
        val adultFilterEnabled = AuthManager.isAdultFilterConfigured(appContext)

        if (!deviceOwnerActive) {
            return DeviceOwnerProtectionDiagnostics(
                deviceAdminActive = deviceAdminActive,
                deviceOwnerActive = false,
                maintenanceActive = false,
                uninstallBlocked = false,
                appsControlBlocked = false,
                userControlDisabled = null,
                factoryResetBlocked = false,
                safeBootBlocked = false,
                dateTimeChangesBlocked = false,
                grantAdminBlocked = null,
                automaticTimeEnabled = null,
                automaticTimeZoneEnabled = null,
                adultFilterEnabled = adultFilterEnabled,
                adultDnsEnforced = if (adultFilterEnabled) false else null,
                privateDnsChangesBlocked = if (adultFilterEnabled) false else null,
                vpnConfigurationBlocked = if (adultFilterEnabled) false else null
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
            }.getOrDefault(false),
            appsControlBlocked = restrictions.policyState(UserManager.DISALLOW_APPS_CONTROL),
            userControlDisabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching {
                    appContext.packageName in dpm.getUserControlDisabledPackages(admin)
                }.getOrDefault(false)
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
                adultFilterEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
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
                adultFilterEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            ) {
                restrictions.policyState(UserManager.DISALLOW_CONFIG_PRIVATE_DNS)
            } else {
                null
            },
            vpnConfigurationBlocked = if (adultFilterEnabled) {
                restrictions.policyState(UserManager.DISALLOW_CONFIG_VPN)
            } else {
                null
            }
        )
    }

    private fun Bundle.policyState(key: String): Boolean = getBoolean(key, false)
}
