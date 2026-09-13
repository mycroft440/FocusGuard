package com.focusguard.security

import android.content.Context
import androidx.room.withTransaction
import com.focusguard.database.AppDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Coordinates configuration of the master credential with persisted protections.
 *
 * The credential itself remains stored by [DeactivationCredentialManager]. This
 * manager owns the business rule that the credential must already exist before a
 * TIME block or an enabled usage limit is configured. PASSWORD blocks are
 * independent and deliberately do not close this configuration path.
 */
@Singleton
class MasterCredentialConfigurationManager @Inject constructor(
    @ApplicationContext context: Context,
    private val deactivationCredentialManager: DeactivationCredentialManager
) {
    private val database = AppDatabase.getDatabase(context.applicationContext)

    /** Legacy construction path for activities that are not Hilt entry points. */
    constructor(context: Context) : this(
        context.applicationContext,
        DeactivationCredentialManager(context.applicationContext)
    )

    class ConfigurationBlockedException(
        val gate: MasterCredentialPolicy.ConfigurationGate
    ) : IllegalStateException(gate.name)

    suspend fun getConfigurationGate(): MasterCredentialPolicy.ConfigurationGate =
        withContext(Dispatchers.IO) {
            database.withTransaction {
                val activeSessions = database.blockSessionDao().getAllActiveSessionsStatic()
                val hasActiveUsageLimit =
                    database.appUsageLimitDao().getAllActiveLimitsStatic().isNotEmpty() ||
                        database.websiteUsageLimitDao().getAllStatic().any { it.isEnabled }

                MasterCredentialPolicy.evaluateCredentialConfiguration(
                    activeSessions = activeSessions,
                    hasActiveUsageLimit = hasActiveUsageLimit
                )
            }
        }

    /**
     * Re-checks the persisted protection state immediately before saving so a
     * screen opened earlier cannot keep an obsolete permission to configure the
     * master credential.
     */
    suspend fun configure(password: String): String = withContext(Dispatchers.IO) {
        val gate = getConfigurationGate()
        if (gate != MasterCredentialPolicy.ConfigurationGate.ALLOWED) {
            throw ConfigurationBlockedException(gate)
        }
        deactivationCredentialManager.configure(password)
    }
}
