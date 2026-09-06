package com.focusguard.security

import android.content.Context
import androidx.room.withTransaction
import com.focusguard.database.AppDatabase
import com.focusguard.utils.FocusGuardLogger
import com.focusguard.utils.UsageLimitBehaviorPolicy
import com.focusguard.utils.UsageLimitPauseStateStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Clears temporary releases when this package is replaced by an update.
 *
 * PASSWORD daily-limit releases are persisted in Room by setting lockUntilTimestamp
 * to the next local midnight. That persistence is useful across a normal process
 * restart, but an explicit app update is a trust boundary: every temporary release
 * must be revoked and the configured protections must remain intact.
 *
 * Daily PAUSE_30/BLOCK_UNTIL_TOMORROW limits also use lockUntilTimestamp, but there
 * it is the rule deadline rather than an unlock. Their deadlines are preserved.
 * PAUSE_30 additionally persists whether today's pause was already completed; that
 * release history is cleared independently so an update cannot leave the target
 * effectively unlocked for the rest of the day.
 */
object AppUpdateUnlockResetter {

    data class ResetResult(
        val appLimitUnlocksCleared: Int,
        val websiteLimitUnlocksCleared: Int,
        val pauseReleasesCleared: Int
    )

    suspend fun reset(context: Context): ResetResult = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext

        // Process-local one-visit grants are cheap to clear and must never bridge
        // a package replacement, including on OEMs that keep a process around.
        PasswordTargetAccessGrant.clear()

        // PAUSE_30 completion is itself a release for the rest of the local day.
        // Clear it globally before touching Room so even an orphaned/stale entry,
        // or a later database failure, cannot survive the package replacement.
        val pauseReleasesCleared = runCatching {
            UsageLimitPauseStateStore.clearAllTemporaryReleases()
        }.onFailure { error ->
            FocusGuardLogger.logError(
                "AppUpdateUnlockResetter",
                "Falha ao revogar liberações de pausa de 30 minutos",
                error
            )
        }.getOrDefault(0)

        // These windows deliberately survive ordinary process death, but must not
        // cross an app update. Each cleanup is independent so one storage failure
        // cannot prevent the remaining release paths from being revoked.
        runCatching { AuthenticatedRemovalWindow.close(appContext) }
            .onFailure { error ->
                FocusGuardLogger.logError(
                    "AppUpdateUnlockResetter",
                    "Falha ao fechar janela autenticada de remoção",
                    error
                )
            }
        runCatching { DeviceAdminActivationWindow.close(appContext) }
            .onFailure { error ->
                FocusGuardLogger.logError(
                    "AppUpdateUnlockResetter",
                    "Falha ao fechar autorização temporária de Device Admin",
                    error
                )
            }
        runCatching { DeviceOwnerMaintenanceGate.revoke(appContext) }
            .onFailure { error ->
                FocusGuardLogger.logError(
                    "AppUpdateUnlockResetter",
                    "Falha ao revogar janela temporária de manutenção Device Owner",
                    error
                )
            }

        val database = AppDatabase.getDatabase(appContext)
        var appUnlocksCleared = 0
        var websiteUnlocksCleared = 0

        database.withTransaction {
            val appDao = database.appUsageLimitDao()
            appDao.getAllStatic().forEach { limit ->
                if (
                    AppUpdateUnlockResetPolicy.shouldResetPasswordRelease(
                        lockMode = limit.lockMode,
                        lockUntilTimestamp = limit.lockUntilTimestamp
                    )
                ) {
                    appDao.update(limit.copy(lockUntilTimestamp = null))
                    appUnlocksCleared++
                }
            }

            val websiteDao = database.websiteUsageLimitDao()
            websiteDao.getAllStatic().forEach { limit ->
                if (
                    AppUpdateUnlockResetPolicy.shouldResetPasswordRelease(
                        lockMode = limit.lockMode,
                        lockUntilTimestamp = limit.lockUntilTimestamp
                    )
                ) {
                    // WebsiteUsageLimitDao exposes REPLACE insert as its update path.
                    websiteDao.insert(limit.copy(lockUntilTimestamp = null))
                    websiteUnlocksCleared++
                }
            }
        }

        ResetResult(
            appLimitUnlocksCleared = appUnlocksCleared,
            websiteLimitUnlocksCleared = websiteUnlocksCleared,
            pauseReleasesCleared = pauseReleasesCleared
        )
    }
}

internal object AppUpdateUnlockResetPolicy {
    fun shouldResetPasswordRelease(lockMode: String, lockUntilTimestamp: Long?): Boolean =
        lockUntilTimestamp != null && lockMode.equals("PASSWORD", ignoreCase = true)

    fun shouldResetPauseRelease(lockMode: String): Boolean =
        UsageLimitBehaviorPolicy.isPauseMode(lockMode)
}
