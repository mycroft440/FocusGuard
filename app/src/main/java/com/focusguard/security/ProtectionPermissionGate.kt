package com.focusguard.security

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.focusguard.admin.DeviceOwnerManager
import com.focusguard.utils.PermissionUtils

enum class ProtectionPermission {
    SELF_PROTECTION_CONSENT,
    ACCESSIBILITY,
    USAGE_ACCESS,
    NOTIFICATIONS,
    BATTERY_OPTIMIZATION,
    DEVICE_ADMIN
}

data class ProtectionPermissionState(
    val selfProtectionConsent: Boolean,
    val accessibility: Boolean,
    val usageAccess: Boolean,
    val notifications: Boolean,
    val batteryOptimization: Boolean,
    val deviceAdmin: Boolean
) {
    val missingPermissions: List<ProtectionPermission>
        get() = buildList {
            if (!selfProtectionConsent) add(ProtectionPermission.SELF_PROTECTION_CONSENT)
            if (!accessibility) add(ProtectionPermission.ACCESSIBILITY)
            if (!usageAccess) add(ProtectionPermission.USAGE_ACCESS)
            if (!notifications) add(ProtectionPermission.NOTIFICATIONS)
            if (!batteryOptimization) add(ProtectionPermission.BATTERY_OPTIMIZATION)
            if (!deviceAdmin) add(ProtectionPermission.DEVICE_ADMIN)
        }

    val isReady: Boolean
        get() = missingPermissions.isEmpty()
}

/**
 * Single source of truth for deciding whether a new block may be configured.
 * Every value is read directly from Android so an app update, process restart,
 * or permission change cannot leave a stale cached decision behind.
 */
object ProtectionPermissionGate {
    fun read(context: Context): ProtectionPermissionState {
        val appContext = context.applicationContext
        val deviceOwnerManager = DeviceOwnerManager.getInstance(appContext)
        return ProtectionPermissionState(
            selfProtectionConsent = SelfProtectionConsent.hasAccepted(appContext),
            accessibility = PermissionUtils.isAccessibilityServiceEnabled(appContext),
            usageAccess = PermissionUtils.isUsageAccessEnabled(appContext),
            notifications = isNotificationPermissionGranted(appContext),
            batteryOptimization = PermissionUtils.isBatteryOptimizationIgnored(appContext),
            deviceAdmin = deviceOwnerManager.isDeviceAdminActive() ||
                deviceOwnerManager.isDeviceOwnerActive()
        )
    }

    private fun isNotificationPermissionGranted(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }
}
