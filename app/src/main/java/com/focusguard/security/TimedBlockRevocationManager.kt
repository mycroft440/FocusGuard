package com.focusguard.security

import android.content.Context
import com.focusguard.database.AppDatabase
import com.focusguard.manager.BlockingSessionManager
import com.focusguard.utils.FocusGuardLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Typed-master-credential escape hatch for explicit protected TIME sessions. */
class TimedBlockRevocationManager(context: Context) {

    private val appContext = context.applicationContext
    private val database = AppDatabase.getDatabase(appContext)
    private val sessionManager = BlockingSessionManager.getInstance(appContext)
    private val credentialManager = DeactivationCredentialManager(appContext)
    private val protectionController = TimedBlockProtectionController.getInstance(appContext)

    enum class Result {
        REVOKED,
        WRONG_PASSWORD,
        NOT_FOUND,
        FAILED
    }

    suspend fun revokeSessionWithMasterCredential(
        sessionId: Int,
        password: String
    ): Result = withContext(Dispatchers.IO) {
        try {
            if (!protectionController.isProtectedSession(sessionId)) {
                return@withContext Result.NOT_FOUND
            }
            val session = database.blockSessionDao().getActiveSessionById(sessionId)
                ?: return@withContext Result.NOT_FOUND
            if (!session.sessionType.equals("TIME", ignoreCase = true) ||
                !sessionManager.isCurrentlyInBlockingWindow(session)
            ) {
                protectionController.reconcileFromDatabase()
                return@withContext Result.NOT_FOUND
            }

            if (!masterCredentialAccepted(password)) {
                return@withContext Result.WRONG_PASSWORD
            }
            if (database.blockSessionDao().deactivateSession(sessionId) == 0) {
                return@withContext Result.NOT_FOUND
            }

            // Reconcile the actual blocking engine first; then the package-scoped
            // uninstall guard is released if this was the final protected TIME block.
            sessionManager.checkAndEnforce()
            protectionController.reconcileFromDatabase()
            Result.REVOKED
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            FocusGuardLogger.logError(
                "TimedBlockRevocation",
                "Falha ao revogar bloqueio por tempo $sessionId",
                error
            )
            Result.FAILED
        }
    }

    private fun masterCredentialAccepted(password: String): Boolean {
        if (password.isBlank()) return false
        return when (credentialManager.verify(password)) {
            DeactivationCredentialManager.VerificationResult.PASSWORD_ACCEPTED,
            DeactivationCredentialManager.VerificationResult.RECOVERY_ACCEPTED -> true
            DeactivationCredentialManager.VerificationResult.REJECTED,
            DeactivationCredentialManager.VerificationResult.NOT_CONFIGURED -> false
        }
    }
}
