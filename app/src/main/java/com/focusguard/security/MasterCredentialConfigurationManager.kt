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
 * manager owns the business rule that it can only be created or changed while no
 * TIME block or enabled usage limit exists. PASSWORD blocks are independent and
 * deliberately do not close this configuration path.
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
                evaluateConfigurationGate()
            }
        }

    /**
     * Re-checks the persisted protection state and keeps the Room transaction open
     * through the credential write. That prevents a concurrent protection write in
     * the same database from being inserted between the gate check and the save.
     */
    suspend fun configure(password: String): String = withContext(Dispatchers.IO) {
        database.withTransaction {
            val gate = evaluateConfigurationGate()
            if (gate != MasterCredentialPolicy.ConfigurationGate.ALLOWED) {
                throw ConfigurationBlockedException(gate)
            }
            deactivationCredentialManager.configure(password)
        }
    }

    private suspend fun evaluateConfigurationGate(): MasterCredentialPolicy.ConfigurationGate {
        val activeSessions = database.blockSessionDao().getAllActiveSessionsStatic()
        val hasActiveUsageLimit =
            database.appUsageLimitDao().getAllActiveLimitsStatic().isNotEmpty() ||
                database.websiteUsageLimitDao().getAllStatic().any { it.isEnabled }

        return MasterCredentialPolicy.evaluateCredentialConfiguration(
            activeSessions = activeSessions,
            hasActiveUsageLimit = hasActiveUsageLimit
        )
    }
}
