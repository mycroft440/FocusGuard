package com.focusguard.dev

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.focusguard.admin.DeviceOwnerManager
import com.focusguard.focusmode.FocusModeManager
import com.focusguard.focusmode.FocusModeStore
import com.focusguard.manager.BlockingSessionManager
import com.focusguard.security.AuthenticatedRemovalWindow
import com.focusguard.service.BlockingAccessibilityService
import com.focusguard.utils.FocusGuardLogger
import com.focusguard.utils.PermissionUtils
import kotlinx.coroutines.delay

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

    private const val ACCESSIBILITY_VERIFY_ATTEMPTS = 50
    private const val ACCESSIBILITY_VERIFY_DELAY_MILLIS = 100L

    data class Result(
        val blocksRemoved: Boolean,
        val focusModeStopped: Boolean,
        val administrativeRolesReleased: Boolean,
        val accessibilityRelinquishRequested: Boolean,
        val accessibilityDisabled: Boolean,
        val runtimeRevocationScheduled: Boolean
    ) {
        /**
         * Do not report success merely because a broadcast was accepted. The
         * Accessibility setting itself must be observed as disabled and the
         * persistent Focus Mode state must already be gone.
         */
        val coreProtectionDisarmed: Boolean
            get() = blocksRemoved &&
                focusModeStopped &&
                administrativeRolesReleased &&
                accessibilityDisabled
    }

    suspend fun disarmForTesting(context: Context): Result {
        val appContext = context.applicationContext

        // The accessibility service trusts this short internal window before
        // calling disableSelf(). The Settings test button opens it intentionally
        // without a password; that is the temporary test bypass.
        AuthenticatedRemovalWindow.open(appContext)

        // Focus Mode lives in device-protected storage and intentionally survives
        // process death/reboot. It therefore has to be dismantled explicitly;
        // clearing only Room sessions is not enough.
        val focusModeStopped = runCatching {
            FocusModeManager.getInstance(appContext).forceStopForDevelopmentExit()
            FocusModeStore.readSession(appContext) == null
        }.onFailure { error ->
            FocusGuardLogger.logError(
                "DevelopmentReset",
                "Falha ao encerrar o Modo Foco persistente",
                error
            )
        }.getOrDefault(false)

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

        val accessibilityAlreadyDisabled =
            !PermissionUtils.isAccessibilityServiceEnabled(appContext)
        val accessibilityRelinquishRequested = if (accessibilityAlreadyDisabled) {
            true
        } else {
            runCatching {
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
        }

        val accessibilityDisabled = when {
            accessibilityAlreadyDisabled -> true
            !accessibilityRelinquishRequested -> false
            else -> awaitAccessibilityDisabled(appContext)
        }
        if (accessibilityRelinquishRequested && !accessibilityDisabled) {
            FocusGuardLogger.log(
                "DevelopmentReset",
                "Acessibilidade continuou habilitada após a solicitação de disableSelf()"
            )
        }

        return Result(
            blocksRemoved = blocksRemoved,
            focusModeStopped = focusModeStopped,
            administrativeRolesReleased = administrativeRolesReleased,
            accessibilityRelinquishRequested = accessibilityRelinquishRequested,
            accessibilityDisabled = accessibilityDisabled,
            runtimeRevocationScheduled = false
        )
    }

    private suspend fun awaitAccessibilityDisabled(context: Context): Boolean {
        repeat(ACCESSIBILITY_VERIFY_ATTEMPTS) {
            if (!PermissionUtils.isAccessibilityServiceEnabled(context)) return true
            delay(ACCESSIBILITY_VERIFY_DELAY_MILLIS)
        }
        return !PermissionUtils.isAccessibilityServiceEnabled(context)
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
