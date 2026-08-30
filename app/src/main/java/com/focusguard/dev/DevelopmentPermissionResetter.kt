package com.focusguard.dev

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.focusguard.admin.DeviceOwnerManager
import com.focusguard.manager.BlockingSessionManager
import com.focusguard.security.AuthenticatedRemovalWindow
import com.focusguard.service.BlockingAccessibilityService
import com.focusguard.utils.FocusGuardLogger

/**
 * TEMPORARY TEST ESCAPE HATCH.
 *
 * This deliberately bypasses the normal password/removal flow so development
 * builds installed on a real phone can always disarm HardBlock in one tap.
 * Delete this object together with its Settings entry before final production
 * hardening.
 *
 * Android does not let a normal app silently revoke every special access it has
 * (Usage Access, exact alarms, DND access, battery optimisation exemption, etc.).
 * The safety goal here is therefore stronger than merely toggling permission
 * bits: remove every local enforcement source, release Device Owner/Admin,
 * relinquish Accessibility, then revoke the runtime permissions Android allows
 * an app to revoke itself.
 */
object DevelopmentPermissionResetter {
    const val TEMPORARY_TEST_ESCAPE_ENABLED = true

    data class Result(
        val blocksRemoved: Boolean,
        val administrativeRolesReleased: Boolean,
        val accessibilityRelinquishRequested: Boolean,
        val runtimeRevocationScheduled: Boolean
    ) {
        val coreProtectionDisarmed: Boolean
            get() = blocksRemoved &&
                administrativeRolesReleased &&
                accessibilityRelinquishRequested
    }

    suspend fun disarmForTesting(context: Context): Result {
        val appContext = context.applicationContext

        // The accessibility service already trusts this very short internal
        // window before calling disableSelf(). The Settings test button opens it
        // intentionally without a password; that is the temporary test bypass.
        AuthenticatedRemovalWindow.open(appContext)

        val blocksRemoved = runCatching {
            BlockingSessionManager.getInstance(appContext)
                .removeAllBlocksForDevelopmentExit()
        }.onFailure { error ->
            FocusGuardLogger.logError(
                "DevelopmentReset",
                "Falha ao remover fontes locais de bloqueio",
                error
            )
        }.getOrDefault(false)

        val administrativeRolesReleased = runCatching {
            DeviceOwnerManager.getInstance(appContext)
                .releaseRemovalProtectionForDevelopmentExit()
        }.onFailure { error ->
            FocusGuardLogger.logError(
                "DevelopmentReset",
                "Falha ao liberar Device Owner/Device Admin",
                error
            )
        }.getOrDefault(false)

        val accessibilityRelinquishRequested = runCatching {
            appContext.sendBroadcast(
                BlockingAccessibilityService.createDevelopmentRelinquishIntent(appContext)
            )
            true
        }.onFailure { error ->
            FocusGuardLogger.logError(
                "DevelopmentReset",
                "Falha ao solicitar desligamento da Acessibilidade",
                error
            )
        }.getOrDefault(false)

        return Result(
            blocksRemoved = blocksRemoved,
            administrativeRolesReleased = administrativeRolesReleased,
            accessibilityRelinquishRequested = accessibilityRelinquishRequested,
            runtimeRevocationScheduled = false
        )
    }

    /**
     * Must be called last. On Android 13+ the OS can kill the process once the
     * app is no longer visible in order to complete the self-revocation.
     */
    fun revokeRuntimePermissionsOnKill(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false

        val appContext = context.applicationContext
        val grantedPermissions = runtimePermissionsForSdk(Build.VERSION.SDK_INT)
            .filter { permission ->
                appContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
            }

        if (grantedPermissions.isEmpty()) return true

        return runCatching {
            appContext.revokeSelfPermissionsOnKill(grantedPermissions)
            true
        }.onFailure { error ->
            FocusGuardLogger.logError(
                "DevelopmentReset",
                "Falha ao agendar revogação das permissões runtime",
                error
            )
        }.getOrDefault(false)
    }

    internal fun runtimePermissionsForSdk(sdkInt: Int): List<String> = buildList {
        add(Manifest.permission.CAMERA)
        if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
