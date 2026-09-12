package com.focusguard.security

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.focusguard.admin.DeviceOwnerManager
import com.focusguard.manager.BlockingSessionManager
import com.focusguard.utils.FocusGuardLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Final gate between an in-app uninstall authorization and Android's uninstall UI.
 *
 * The UI may authenticate with the same password, pattern or biometric that protects
 * the active reversible block. This coordinator re-checks the live block state right
 * before removing administrative protection so a time block cannot be bypassed by a
 * stale Compose snapshot.
 */
object AuthenticatedUninstallCoordinator {

    enum class Authorization {
        NONE,
        REVERSIBLE_BLOCK_AUTHENTICATED
    }

    enum class Outcome {
        STARTED,
        AUTHORIZATION_REQUIRED,
        BLOCKED_BY_IRREVERSIBLE_BLOCK,
        RELEASE_FAILED,
        UNINSTALL_UI_FAILED
    }

    suspend fun releaseAndOpen(
        context: Context,
        authorization: Authorization
    ): Outcome {
        val appContext = context.applicationContext
        val sessionManager = BlockingSessionManager.getInstance(appContext)
        val deviceOwnerManager = DeviceOwnerManager.getInstance(appContext)

        val irreversibleBlockActive = sessionManager.isUninstallBlockedByTimeFlow.first()
        val maintenanceActive = deviceOwnerManager.isMaintenanceActive()
        if (irreversibleBlockActive && !maintenanceActive) {
            return Outcome.BLOCKED_BY_IRREVERSIBLE_BLOCK
        }

        val reversibleOrFocusBlockActive = sessionManager.isBlockingActiveFlow.first()
        if (
            reversibleOrFocusBlockActive &&
            !maintenanceActive &&
            authorization != Authorization.REVERSIBLE_BLOCK_AUTHENTICATED
        ) {
            return Outcome.AUTHORIZATION_REQUIRED
        }

        // Establish the fail-closed system-UI handoff before releasing any
        // administrative role. If the current boot cannot be identified, no
        // protection is removed.
        if (!AuthenticatedRemovalWindow.open(appContext)) {
            return Outcome.RELEASE_FAILED
        }

        val released = try {
            withContext(Dispatchers.IO) {
                // This primitive only removes Android administrative roles/policies.
                // Reaching it from this coordinator requires the live checks above and,
                // when needed, a credential/biometric authorization in the UI.
                deviceOwnerManager.releaseRemovalProtectionForDevelopmentExit()
            }
        } catch (cancelled: CancellationException) {
            AuthenticatedRemovalWindow.close(appContext)
            throw cancelled
        } catch (error: Exception) {
            FocusGuardLogger.logError(
                "AuthenticatedUninstall",
                "Falha ao liberar as proteções administrativas para desinstalação",
                error
            )
            false
        }

        if (!released) {
            AuthenticatedRemovalWindow.close(appContext)
            return Outcome.RELEASE_FAILED
        }

        return withContext(Dispatchers.Main.immediate) {
            runCatching {
                // Administrative-role release is bounded to roughly five seconds,
                // while the handoff window lasts sixty. Do not create a second
                // persistence point after the roles are gone; just verify that the
                // already-established authorization is still alive.
                check(AuthenticatedRemovalWindow.isActive(appContext)) {
                    "Janela autenticada de remoção expirou antes da desinstalação"
                }
                appContext.startActivity(
                    Intent(Intent.ACTION_DELETE).apply {
                        data = Uri.parse("package:${appContext.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
                Outcome.STARTED
            }.onFailure { error ->
                AuthenticatedRemovalWindow.close(appContext)
                FocusGuardLogger.logError(
                    "AuthenticatedUninstall",
                    "Falha ao abrir a tela de desinstalação do Android",
                    error
                )
            }.getOrDefault(Outcome.UNINSTALL_UI_FAILED)
        }
    }
}
