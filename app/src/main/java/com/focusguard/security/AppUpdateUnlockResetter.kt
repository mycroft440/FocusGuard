package com.focusguard.security

import android.content.Context
import androidx.room.withTransaction
import com.focusguard.database.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Clears temporary target releases when this package is replaced by an update.
 *
 * PASSWORD daily-limit releases are persisted in Room by setting lockUntilTimestamp
 * to the next local midnight. That persistence is useful across a normal process
 * restart, but an explicit app update is a trust boundary: every temporary release
 * must be revoked and the configured protections must remain intact.
 *
 * Daily PAUSE_30/BLOCK_UNTIL_TOMORROW limits also use lockUntilTimestamp, but there
 * it is the rule deadline rather than an unlock. The policy below intentionally
 * excludes those modes (and TIME) so an update never shortens a configured rule.
 */
object AppUpdateUnlockResetter {

    data class ResetResult(
        val appLimitUnlocksCleared: Int,
        val websiteLimitUnlocksCleared: Int
    )

    suspend fun reset(context: Context): ResetResult = withContext(Dispatchers.IO) {
        // One-visit PASSWORD-session grants live in memory. Clear them explicitly
        // as well so this remains correct if the receiver runs in a process that
        // happened to survive package replacement on an OEM implementation.
        PasswordTargetAccessGrant.clear()

        val database = AppDatabase.getDatabase(context.applicationContext)
        var appUnlocksCleared = 0
        var websiteUnlocksCleared = 0

        database.withTransaction {
            val appDao = database.appUsageLimitDao()
            appDao.getAllStatic()
                .filter { limit ->
                    AppUpdateUnlockResetPolicy.shouldReset(
                        lockMode = limit.lockMode,
                        lockUntilTimestamp = limit.lockUntilTimestamp
                    )
                }
                .forEach { limit ->
                    appDao.update(limit.copy(lockUntilTimestamp = null))
                    appUnlocksCleared++
                }

            val websiteDao = database.websiteUsageLimitDao()
            websiteDao.getAllStatic()
                .filter { limit ->
                    AppUpdateUnlockResetPolicy.shouldReset(
                        lockMode = limit.lockMode,
                        lockUntilTimestamp = limit.lockUntilTimestamp
                    )
                }
                .forEach { limit ->
                    // WebsiteUsageLimitDao exposes REPLACE insert as its update path.
                    websiteDao.insert(limit.copy(lockUntilTimestamp = null))
                    websiteUnlocksCleared++
                }
        }

        ResetResult(
            appLimitUnlocksCleared = appUnlocksCleared,
            websiteLimitUnlocksCleared = websiteUnlocksCleared
        )
    }
}

internal object AppUpdateUnlockResetPolicy {
    fun shouldReset(lockMode: String, lockUntilTimestamp: Long?): Boolean =
        lockUntilTimestamp != null && lockMode.equals("PASSWORD", ignoreCase = true)
}
