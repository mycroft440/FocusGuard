package com.focusguard.security

import android.content.Context
import androidx.room.withTransaction
import com.focusguard.database.AppDatabase
import com.focusguard.utils.FocusGuardLogger

/**
 * Removes the obsolete master-password semantics from persisted daily limits.
 *
 * Older FocusGuard builds allowed usage-limit rows with lockMode=PASSWORD and
 * authenticated those rows with the master credential. That product contract no
 * longer exists: the master credential is reserved exclusively for the explicit
 * "Remove all blocks" action, while PASSWORD sessions use per-target credentials.
 *
 * We deliberately pause legacy rows instead of silently strengthening them or
 * deleting the user's configured allowance. The daily-limit amount remains in the
 * database and can be re-enabled from the current editor with today's behavior.
 */
object LegacyPasswordUsageLimitMigration {
    private const val PREFS_NAME = "credential_scope_migration"
    private const val KEY_DONE = "legacy_password_usage_limits_v1"

    suspend fun runIfNeeded(context: Context): Boolean {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_DONE, false)) return false

        return try {
            val database = AppDatabase.getDatabase(appContext)
            var changed = false
            database.withTransaction {
                val appDao = database.appUsageLimitDao()
                appDao.getAllStatic()
                    .filter { it.lockMode.equals("PASSWORD", ignoreCase = true) }
                    .forEach { legacy ->
                        appDao.update(
                            legacy.copy(
                                isEnabled = false,
                                lockMode = "NONE",
                                lockPasswordHash = null,
                                lockUntilTimestamp = null,
                                unlockWithPassword = false
                            )
                        )
                        changed = true
                    }

                val websiteDao = database.websiteUsageLimitDao()
                websiteDao.getAllStatic()
                    .filter { it.lockMode.equals("PASSWORD", ignoreCase = true) }
                    .forEach { legacy ->
                        websiteDao.insert(
                            legacy.copy(
                                isEnabled = false,
                                lockMode = "NONE",
                                lockPasswordHash = null,
                                lockUntilTimestamp = null
                            )
                        )
                        changed = true
                    }
            }

            check(prefs.edit().putBoolean(KEY_DONE, true).commit()) {
                "Não foi possível persistir a migração de limites legados"
            }
            if (changed) {
                FocusGuardLogger.log(
                    "CredentialMigration",
                    "Limites PASSWORD legados foram pausados e separados da senha mestre"
                )
            }
            changed
        } catch (error: Exception) {
            FocusGuardLogger.logError(
                "CredentialMigration",
                "Falha ao migrar limites PASSWORD legados; nova tentativa ocorrerá depois",
                error
            )
            false
        }
    }
}
