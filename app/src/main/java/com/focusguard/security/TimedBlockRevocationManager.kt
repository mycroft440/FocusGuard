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
    private val attemptLimiter = MasterCredentialAttemptLimiter(appContext)
    private val protectionController = TimedBlockProtectionController.getInstance(appContext)

    enum class Result {
        REVOKED,
        WRONG_PASSWORD,
        RATE_LIMITED,
        NOT_FOUND,
        FAILED
    }

    fun retryAfterMillis(): Long = attemptLimiter.gate().retryAfterMillis

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

            if (!attemptLimiter.gate().allowed) return@withContext Result.RATE_LIMITED
            if (!masterPasswordAccepted(password)) {
                attemptLimiter.recordFailure()
                return@withContext Result.WRONG_PASSWORD
            }
            attemptLimiter.recordSuccess()
            protectionController.beginRevocation()

            if (database.blockSessionDao().deactivateSession(sessionId) == 0) {
                protectionController.reconcileFromDatabase()
                return@withContext Result.NOT_FOUND
            }

            reconcileAfterRevocation()
            Result.REVOKED
        } catch (cancelled: CancellationException) {
            protectionController.reconcileFromDatabase()
            throw cancelled
        } catch (error: Exception) {
            protectionController.reconcileFromDatabase()
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
                if (!attemptLimiter.gate().allowed) return@withContext Result.RATE_LIMITED
                if (!masterPasswordAccepted(password)) {
                    attemptLimiter.recordFailure()
                    return@withContext Result.WRONG_PASSWORD
                }
                attemptLimiter.recordSuccess()

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

                protectionController.beginRevocation()
                var changed = false
                activeProtectedIds.forEach { id ->
                    changed = database.blockSessionDao().deactivateSession(id) > 0 || changed
                }
                if (!changed) {
                    protectionController.reconcileFromDatabase()
                    return@withContext Result.NOT_FOUND
                }

                reconcileAfterRevocation()
                Result.REVOKED
            } catch (cancelled: CancellationException) {
                protectionController.reconcileFromDatabase()
                throw cancelled
            } catch (error: Exception) {
                protectionController.reconcileFromDatabase()
                FocusGuardLogger.logError(
                    "TimedBlockRevocation",
                    "Falha ao revogar bloqueios por tempo protegidos",
                    error
                )
                Result.FAILED
            }
        }

    private suspend fun reconcileAfterRevocation() {
        // Reconcile the actual blocking engine first; then the TIME controller releases package
        // self-protection only when no explicit protected TIME commitment remains.
        sessionManager.checkAndEnforce()
        protectionController.reconcileFromDatabase()
    }

    private fun masterPasswordAccepted(password: String): Boolean {
        if (password.isBlank()) return false
        return credentialManager.verify(password) ==
            DeactivationCredentialManager.VerificationResult.PASSWORD_ACCEPTED
    }
}
