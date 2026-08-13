package com.focusguard.security

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.focusguard.BuildConfig
import com.focusguard.admin.DeviceOwnerManager
import com.focusguard.focusmode.FocusModeManager
import com.focusguard.manager.BlockingSessionManager
import com.focusguard.service.BlockingAccessibilityService
import com.focusguard.utils.AccessibilityStateMonitor
import com.focusguard.utils.FocusGuardLogger
import com.focusguard.utils.UsageAccessStateMonitor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Coordinates the intentionally destructive, password-protected technical exit. */
object DevelopmentUninstallCoordinator {

    enum class Outcome {
        BLOCKS_REMOVED,
        STARTED,
        INVALID_PASSWORD,
        UNAVAILABLE,
        RELEASE_FAILED,
        UNINSTALL_UI_FAILED
    }

    suspend fun removeAllBlocksAndProtections(
        context: Context,
        password: String
    ): Outcome {
        if (!DevelopmentAccessPolicy.isAvailable(BuildConfig.DEV_AREA_PASSWORD)) {
            return Outcome.UNAVAILABLE
        }

        if (!DevelopmentAccessPolicy.acceptsPassword(
                input = password,
                configuredPassword = BuildConfig.DEV_AREA_PASSWORD
            )
        ) return Outcome.INVALID_PASSWORD

        val appContext = context.applicationContext
        AuthenticatedRemovalWindow.open(appContext)

        val released = try {
            withContext(Dispatchers.IO) {
                FocusModeManager.getInstance(appContext).forceStopForDevelopmentExit()
                val localStateDisarmed = BlockingSessionManager
                    .getInstance(appContext)
                    .removeAllBlocksForDevelopmentExit()
                val administrativeRolesReleased = DeviceOwnerManager
                    .getInstance(appContext)
                    .releaseRemovalProtectionForDevelopmentExit()
                localStateDisarmed && administrativeRolesReleased
            }
        } catch (error: CancellationException) {
            AuthenticatedRemovalWindow.close(appContext)
            throw error
        } catch (error: Exception) {
            FocusGuardLogger.logError(
                "DevelopmentExit",
                "Falha inesperada ao remover bloqueios e proteções",
                error
            )
            false
        }
        if (!released) {
            AuthenticatedRemovalWindow.close(appContext)
            return Outcome.RELEASE_FAILED
        }

        AccessibilityStateMonitor.stop(appContext)
        UsageAccessStateMonitor.stop()
        val accessibilityReleased = runCatching {
            appContext.sendBroadcast(
                BlockingAccessibilityService.createDevelopmentRelinquishIntent(appContext)
            )
        }.onFailure { error ->
            FocusGuardLogger.logError(
                "DevelopmentExit",
                "Falha ao solicitar a desativação da Acessibilidade",
                error
            )
        }.isSuccess
        if (!accessibilityReleased) {
            AuthenticatedRemovalWindow.close(appContext)
            return Outcome.RELEASE_FAILED
        }

        return Outcome.BLOCKS_REMOVED
    }

    suspend fun openUninstall(context: Context): Outcome {
        val appContext = context.applicationContext
        if (!AuthenticatedRemovalWindow.isActive(appContext)) return Outcome.UNAVAILABLE

        // Refresh the short authorization bridge immediately before handing the
        // flow to Android's own uninstall UI.
        AuthenticatedRemovalWindow.open(appContext)

        return withContext(Dispatchers.Main.immediate) {
            runCatching {
                appContext.startActivity(createUninstallIntent(appContext))
                Outcome.STARTED
            }.onFailure { error ->
                AuthenticatedRemovalWindow.close(appContext)
                FocusGuardLogger.logError(
                    "DevelopmentExit",
                    "Falha ao abrir o desinstalador do Android",
                    error
                )
            }.getOrDefault(Outcome.UNINSTALL_UI_FAILED)
        }
    }

    fun finishWithoutUninstall(context: Context) {
        AuthenticatedRemovalWindow.close(context.applicationContext)
    }

    internal fun createUninstallIntent(context: Context): Intent =
        Intent(Intent.ACTION_DELETE).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
}
