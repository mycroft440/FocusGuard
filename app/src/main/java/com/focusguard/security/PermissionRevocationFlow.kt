package com.focusguard.security

import android.app.admin.DevicePolicyManager
import android.content.Context
import com.focusguard.admin.FocusGuardDeviceAdminReceiver
import com.focusguard.service.BlockingAccessibilityService
import com.focusguard.utils.PermissionUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

data class PermissionRevocationResult(
    val accessibilityWasActive: Boolean,
    val accessibilityRevoked: Boolean,
    val deviceAdminWasActive: Boolean,
    val deviceAdminRevoked: Boolean
) {
    val hadRequestedAccess: Boolean
        get() = accessibilityWasActive || deviceAdminWasActive

    val allRequestedAccessRevoked: Boolean
        get() = (!accessibilityWasActive || accessibilityRevoked) &&
            (!deviceAdminWasActive || deviceAdminRevoked)
}

/**
 * Revokes only the two accesses explicitly managed by the Settings action:
 * FocusGuard Accessibility and legacy Device Admin.
 *
 * Device Owner is intentionally never changed here.
 */
object PermissionRevocationFlow {
    private const val REVOCATION_POLL_ATTEMPTS = 50
    private const val REVOCATION_POLL_INTERVAL_MILLIS = 100L

    internal fun isLegacyDeviceAdminActive(
        deviceAdminActive: Boolean,
        deviceOwnerActive: Boolean
    ): Boolean = deviceAdminActive && !deviceOwnerActive

    fun hasRequestedAccess(context: Context): Boolean {
        val appContext = context.applicationContext
        return PermissionUtils.isAccessibilityServiceEnabled(appContext) ||
            isLegacyDeviceAdminActive(appContext)
    }

    suspend fun revokeRequestedAccess(context: Context): PermissionRevocationResult {
        val appContext = context.applicationContext
        val accessibilityWasActive = PermissionUtils.isAccessibilityServiceEnabled(appContext)
        val deviceAdminWasActive = isLegacyDeviceAdminActive(appContext)

        if (!accessibilityWasActive && !deviceAdminWasActive) {
            return PermissionRevocationResult(
                accessibilityWasActive = false,
                accessibilityRevoked = true,
                deviceAdminWasActive = false,
                deviceAdminRevoked = true
            )
        }

        var accessibilityRevoked = !accessibilityWasActive
        var deviceAdminRevoked = !deviceAdminWasActive

        try {
            if (accessibilityWasActive) {
                AuthenticatedRemovalWindow.open(appContext)
                runCatching {
                    appContext.sendBroadcast(
                        BlockingAccessibilityService.createDevelopmentRelinquishIntent(appContext)
                    )
                }
            }

            if (deviceAdminWasActive) {
                deviceAdminRevoked = revokeLegacyDeviceAdmin(appContext)
            }

            if (accessibilityWasActive) {
                accessibilityRevoked = awaitAccessibilityDisabled(appContext)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            if (accessibilityWasActive) {
                AuthenticatedRemovalWindow.close(appContext)
            }
        }

        return PermissionRevocationResult(
            accessibilityWasActive = accessibilityWasActive,
            accessibilityRevoked = accessibilityRevoked,
            deviceAdminWasActive = deviceAdminWasActive,
            deviceAdminRevoked = deviceAdminRevoked
        )
    }

    private fun isLegacyDeviceAdminActive(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val component = FocusGuardDeviceAdminReceiver.getComponentName(context)
        return runCatching {
            isLegacyDeviceAdminActive(
                deviceAdminActive = dpm.isAdminActive(component),
                deviceOwnerActive = dpm.isDeviceOwnerApp(context.packageName)
            )
        }.getOrDefault(false)
    }

    @Suppress("DEPRECATION")
    private suspend fun revokeLegacyDeviceAdmin(context: Context): Boolean =
        withContext(Dispatchers.IO) {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val component = FocusGuardDeviceAdminReceiver.getComponentName(context)

            try {
                if (dpm.isDeviceOwnerApp(context.packageName)) return@withContext true
                if (!dpm.isAdminActive(component)) return@withContext true

                dpm.removeActiveAdmin(component)
                repeat(REVOCATION_POLL_ATTEMPTS) {
                    if (!dpm.isAdminActive(component)) return@withContext true
                    delay(REVOCATION_POLL_INTERVAL_MILLIS)
                }
                !dpm.isAdminActive(component)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
        }

    private suspend fun awaitAccessibilityDisabled(context: Context): Boolean {
        repeat(REVOCATION_POLL_ATTEMPTS) {
            if (!PermissionUtils.isAccessibilityServiceEnabled(context)) return true
            delay(REVOCATION_POLL_INTERVAL_MILLIS)
        }
        return !PermissionUtils.isAccessibilityServiceEnabled(context)
    }
}
