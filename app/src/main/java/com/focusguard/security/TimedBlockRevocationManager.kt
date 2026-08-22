package com.focusguard.security

import android.content.Context
import com.focusguard.database.AppDatabase
import com.focusguard.manager.BlockingSessionManager
import com.focusguard.utils.FocusGuardLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Typed-master-password escape hatch for explicit protected TIME sessions. */
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

            if (!masterPasswordAccepted(password)) {
                return@withContext Result.WRONG_PASSWORD
            }
            if (database.blockSessionDao().deactivateSession(sessionId) == 0) {
                return@withContext Result.NOT_FOUND
            }

            reconcileAfterRevocation()
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

    /** Accessible from the current TIME detail surface, where entries are target-based. */
    suspend fun revokeAllProtectedTimeSessionsWithMasterCredential(password: String): Result =
        withContext(Dispatchers.IO) {
            try {
                val protectedIds = protectionController.protectedSessionIdsSnapshot()
                if (protectedIds.isEmpty()) return@withContext Result.NOT_FOUND
                if (!masterPasswordAccepted(password)) return@withContext Result.WRONG_PASSWORD

                val activeProtectedIds = protectedIds.filter { id ->
                    val session = database.blockSessionDao().getActiveSessionById(id)
                    session != null &&
                        session.sessionType.equals("TIME", ignoreCase = true) &&
                        sessionManager.isCurrentlyInBlockingWindow(session)
                }
                if (activeProtectedIds.isEmpty()) {
                    protectionController.reconcileFromDatabase()
                    return@withContext Result.NOT_FOUND
                }

                var changed = false
                activeProtectedIds.forEach { id ->
                    changed = database.blockSessionDao().deactivateSession(id) > 0 || changed
                }
                if (!changed) return@withContext Result.NOT_FOUND

                reconcileAfterRevocation()
                Result.REVOKED
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                FocusGuardLogger.logError(
                    "TimedBlockRevocation",
                    "Falha ao revogar bloqueios por tempo protegidos",
                    error
                )
                Result.FAILED
            }
        }

    private suspend fun reconcileAfterRevocation() {
        // Reconcile the actual blocking engine first; then the package-scoped
        // uninstall guard is released if this was the final protected TIME block.
        sessionManager.checkAndEnforce()
        protectionController.reconcileFromDatabase()
    }

    private fun masterPasswordAccepted(password: String): Boolean {
        if (password.isBlank()) return false
        return credentialManager.verify(password) ==
            DeactivationCredentialManager.VerificationResult.PASSWORD_ACCEPTED
    }
}
