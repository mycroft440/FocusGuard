package com.focusguard.utils

import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.text.TextUtils

enum class EssentialPermission {
    ACCESSIBILITY,
    USAGE_ACCESS
}
/**
 * Utility class for permission checks.
 */
object PermissionUtils {

    /**
     * Check if the FocusGuard Accessibility Service is enabled.
     */
    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val expectedService = ComponentName(context.packageName, "com.focusguard.service.BlockingAccessibilityService")
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)

        return splitter.any { service ->
            ComponentName.unflattenFromString(service)?.equals(expectedService) == true
        }
    }

    /**
     * Check if Usage Access permission is granted.
     * Uses compatible API for both API 21+ and API 29+.
     */
    fun isUsageAccessEnabled(context: Context): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    context.applicationInfo.uid,
                    context.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    context.applicationInfo.uid,
                    context.packageName
                )
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Check if the app is exempt from battery optimizations.
     */
    fun isBatteryOptimizationIgnored(context: Context): Boolean {
        return try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            powerManager.isIgnoringBatteryOptimizations(context.packageName)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Legacy capability check retained for read-only usage/Insights warnings.
     * It must never authorize block configuration; that decision belongs to
     * ProtectionPermissionGate, which requires the complete protection set.
     */
    fun hasEssentialPermissions(
        accessibilityEnabled: Boolean,
        usageAccessEnabled: Boolean
    ): Boolean = missingEssentialPermissions(
        accessibilityEnabled = accessibilityEnabled,
        usageAccessEnabled = usageAccessEnabled
    ).isEmpty()

    /**
     * Returns only the essential permissions that are still missing, in the
     * same order used by the setup flow.
     */
    fun missingEssentialPermissions(
        accessibilityEnabled: Boolean,
        usageAccessEnabled: Boolean
    ): List<EssentialPermission> = buildList {
        if (!accessibilityEnabled) add(EssentialPermission.ACCESSIBILITY)
        if (!usageAccessEnabled) add(EssentialPermission.USAGE_ACCESS)
    }
}
