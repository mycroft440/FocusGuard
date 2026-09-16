package com.focusguard.security

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.focusguard.admin.DeviceOwnerManager

enum class PermissionRevocationStep {
    NOTIFICATIONS,
    BATTERY_OPTIMIZATION,
    USAGE_ACCESS,
    ACCESSIBILITY
}

/**
 * Coordinates the user-requested removal of FocusGuard's Android access.
 *
 * Runtime/special-access permissions such as Usage Access and Accessibility
 * cannot all be revoked silently by an app. The administrative role is released
 * directly after authentication, while the remaining entries are handed back to
 * Android Settings so the user stays in control of each system-owned toggle.
 */
object PermissionRevocationFlow {

    fun manualSteps(
        state: ProtectionPermissionState,
        sdkInt: Int = Build.VERSION.SDK_INT
    ): List<PermissionRevocationStep> = buildList {
        if (sdkInt >= Build.VERSION_CODES.TIRAMISU && state.notifications) {
            add(PermissionRevocationStep.NOTIFICATIONS)
        }
        if (state.batteryOptimization) {
            add(PermissionRevocationStep.BATTERY_OPTIMIZATION)
        }
        if (state.usageAccess) {
            add(PermissionRevocationStep.USAGE_ACCESS)
        }
        if (state.accessibility) {
            add(PermissionRevocationStep.ACCESSIBILITY)
        }
    }

    fun hasRevocablePermissions(
        state: ProtectionPermissionState,
        sdkInt: Int = Build.VERSION.SDK_INT
    ): Boolean = state.deviceAdmin || manualSteps(state, sdkInt).isNotEmpty()

    /**
     * Reuses the existing full administrative-release routine so Device Owner
     * policies are cleaned up before the Android role itself is removed.
     */
    suspend fun revokeAdministrativeAccess(context: Context): Boolean {
        val manager = DeviceOwnerManager.getInstance(context.applicationContext)
        if (!manager.isDeviceOwnerActive() && !manager.isDeviceAdminActive()) return true
        return manager.releaseRemovalProtectionForDevelopmentExit()
    }

    fun settingsIntent(
        context: Context,
        step: PermissionRevocationStep
    ): Intent {
        val primary = when (step) {
            PermissionRevocationStep.NOTIFICATIONS ->
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                }

            PermissionRevocationStep.BATTERY_OPTIMIZATION ->
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

            PermissionRevocationStep.USAGE_ACCESS ->
                Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

            PermissionRevocationStep.ACCESSIBILITY ->
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        }

        return if (primary.resolveActivity(context.packageManager) != null) {
            primary
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        }
    }
}
