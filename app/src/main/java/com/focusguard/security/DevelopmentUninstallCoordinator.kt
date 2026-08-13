package com.focusguard.security

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.focusguard.BuildConfig
import com.focusguard.admin.DeviceOwnerManager
import com.focusguard.manager.BlockingSessionManager
import com.focusguard.service.BlockingAccessibilityService
import com.focusguard.utils.AccessibilityStateMonitor
import com.focusguard.utils.FocusGuardLogger
import com.focusguard.utils.UsageAccessStateMonitor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Coordinates the intentionally destructive, debug-only uninstall escape hatch. */
object DevelopmentUninstallCoordinator {

    enum class Outcome {
        STARTED,
        INVALID_PASSWORD,
        UNAVAILABLE,
        RELEASE_FAILED,
        UNINSTALL_UI_FAILED
    }

    suspend fun relinquishAndOpenUninstall(
        context: Context,
        password: String
    ): Outcome {
        if (!DevelopmentAccessPolicy.isAvailable(
                isDebugBuild = BuildConfig.DEBUG,
                configuredPassword = BuildConfig.DEV_AREA_PASSWORD
            )
        ) return Outcome.UNAVAILABLE

        if (!DevelopmentAccessPolicy.acceptsPassword(
                input = password,
                isDebugBuild = BuildConfig.DEBUG,
                configuredPassword = BuildConfig.DEV_AREA_PASSWORD
            )
        ) return Outcome.INVALID_PASSWORD

        val appContext = context.applicationContext
        AuthenticatedRemovalWindow.open(appContext)

        val released = try {
            withContext(Dispatchers.IO) {
                val localStateDisarmed = BlockingSessionManager
                    .getInstance(appContext)
                    .prepareForDevelopmentUninstall()
                localStateDisarmed && DeviceOwnerManager
                    .getInstance(appContext)
                    .releaseRemovalProtectionForDevelopment()
            }
        } catch (error: CancellationException) {
            AuthenticatedRemovalWindow.close(appContext)
            throw error
        } catch (error: Exception) {
            FocusGuardLogger.logError(
                "DevelopmentUninstall",
                "Falha inesperada ao preparar a desinstalação de desenvolvimento",
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
        appContext.sendBroadcast(
            BlockingAccessibilityService.createDevelopmentRelinquishIntent(appContext)
        )

        return withContext(Dispatchers.Main.immediate) {
            runCatching {
                appContext.startActivity(createUninstallIntent(appContext))
                Outcome.STARTED
            }.onFailure { error ->
                AuthenticatedRemovalWindow.close(appContext)
                FocusGuardLogger.logError(
                    "DevelopmentUninstall",
                    "Falha ao abrir o desinstalador do Android",
                    error
                )
            }.getOrDefault(Outcome.UNINSTALL_UI_FAILED)
        }
    }

    internal fun createUninstallIntent(context: Context): Intent =
        Intent(Intent.ACTION_DELETE).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
}
