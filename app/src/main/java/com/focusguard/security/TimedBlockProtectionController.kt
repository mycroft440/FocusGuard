package com.focusguard.security

import android.app.admin.DevicePolicyManager
import android.content.Context
import com.focusguard.admin.FocusGuardDeviceAdminReceiver
import com.focusguard.database.AppDatabase
import com.focusguard.utils.FocusGuardLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Owns the package-scoped uninstall guard for the explicit "block by time" flow.
 *
 * The state needed before first unlock is kept in device-protected storage. Other
 * blocking modes are deliberately not adopted here, even when their database
 * session type happens to use TIME internally.
 */
class TimedBlockProtectionController private constructor(private val context: Context) {

    private val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val admin = FocusGuardDeviceAdminReceiver.getComponentName(context)
    private val storageContext = runCatching {
        context.createDeviceProtectedStorageContext()
    }.getOrDefault(context)
    private val preferences = storageContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isDeviceOwnerReady(): Boolean = runCatching {
        dpm.isDeviceOwnerApp(context.packageName)
    }.getOrDefault(false)

    /**
     * Closes the uninstall race before the Room transaction creates the session.
     * Call [commitNewestTimeSession] immediately after a successful creation.
     */
    fun prepareForTimeCreation(): Boolean {
        if (!isDeviceOwnerReady()) return false
        if (!preferences.edit()
                .putBoolean(KEY_PENDING_CREATION, true)
                .putLong(KEY_PENDING_SINCE, System.currentTimeMillis())
                .commit()
        ) return false
        return applyUninstallBlocked(true)
    }

    /**
     * Adopts only the TIME session created by the explicit time-block screen.
     * This is what keeps recovery presets and every other mode out of this feature.
     */
    suspend fun commitNewestTimeSession(createdNotBeforeMillis: Long): Boolean =
        withContext(Dispatchers.IO) {
            if (!isDeviceOwnerReady()) return@withContext false
            val database = AppDatabase.getDatabase(context)
            val candidate = database.blockSessionDao().getAllActiveSessionsStatic()
                .asSequence()
                .filter { session ->
                    session.sessionType.equals("TIME", ignoreCase = true) &&
                        session.isActive &&
                        session.startTime >= createdNotBeforeMillis
                }
                .maxByOrNull { it.startTime }
                ?: return@withContext false.also {
                    clearPendingCreation()
                    reconcileFromDatabase()
                }

            val ids = protectedSessionIds().toMutableSet().apply {
                add(candidate.id.toString())
            }
            val saved = preferences.edit()
                .putStringSet(KEY_PROTECTED_SESSION_IDS, ids)
                .putBoolean(KEY_PENDING_CREATION, false)
                .remove(KEY_PENDING_SINCE)
                .commit()
            if (!saved) return@withContext false
            applyUninstallBlocked(true)
        }

    suspend fun cancelPendingCreation() = withContext(Dispatchers.IO) {
        clearPendingCreation()
        reconcileFromDatabase()
    }

    fun isProtectedSession(sessionId: Int): Boolean =
        sessionId.toString() in protectedSessionIds()

    /**
     * Reconciles the persisted commitment with Room after credential storage is
     * available. This call intentionally runs after the generic blocking engine,
     * so non-TIME modes cannot accidentally inherit the uninstall guard.
     */
    suspend fun reconcileFromDatabase(): Boolean = withContext(Dispatchers.IO) {
        if (!isDeviceOwnerReady()) return@withContext false

        val now = System.currentTimeMillis()
        val database = AppDatabase.getDatabase(context)
        val activeById = database.blockSessionDao().getAllActiveSessionsStatic()
            .associateBy { it.id }
        val validIds = protectedSessionIds().filterTo(linkedSetOf()) { rawId ->
            val id = rawId.toIntOrNull() ?: return@filterTo false
            val session = activeById[id] ?: return@filterTo false
            TimedBlockProtectionPolicy.requiresUninstallProtection(
                sessionType = session.sessionType,
                isActive = session.isActive,
                endTimeMillis = session.endTime,
                nowMillis = now
            )
        }

        val pending = preferences.getBoolean(KEY_PENDING_CREATION, false) &&
            isPendingCreationFresh(now)
        val saved = preferences.edit()
            .putStringSet(KEY_PROTECTED_SESSION_IDS, validIds)
            .putBoolean(KEY_PENDING_CREATION, pending)
            .apply {
                if (!pending) remove(KEY_PENDING_SINCE)
            }
            .commit()
        if (!saved) return@withContext false

        applyUninstallBlocked(validIds.isNotEmpty() || pending)
    }

    /** Direct-Boot path: no Room/credential-encrypted storage is touched here. */
    fun restorePersistedAtDirectBoot(): Boolean {
        if (!isDeviceOwnerReady()) return false
        val now = System.currentTimeMillis()
        val pending = preferences.getBoolean(KEY_PENDING_CREATION, false) &&
            isPendingCreationFresh(now)
        val required = protectedSessionIds().isNotEmpty() || pending
        return applyUninstallBlocked(required)
    }

    private fun protectedSessionIds(): Set<String> =
        preferences.getStringSet(KEY_PROTECTED_SESSION_IDS, emptySet()).orEmpty().toSet()

    private fun clearPendingCreation() {
        preferences.edit()
            .putBoolean(KEY_PENDING_CREATION, false)
            .remove(KEY_PENDING_SINCE)
            .commit()
    }

    private fun isPendingCreationFresh(nowMillis: Long): Boolean {
        val since = preferences.getLong(KEY_PENDING_SINCE, 0L)
        return since > 0L && nowMillis - since <= MAX_PENDING_CREATION_MILLIS
    }

    private fun applyUninstallBlocked(blocked: Boolean): Boolean {
        return runCatching {
            dpm.setUninstallBlocked(admin, context.packageName, blocked)
            dpm.isUninstallBlocked(admin, context.packageName) == blocked
        }.onFailure { error ->
            FocusGuardLogger.logError(
                "TimedBlockProtection",
                "Falha ao reconciliar proteção de desinstalação (blocked=$blocked)",
                error
            )
        }.getOrDefault(false)
    }

    companion object {
        private const val PREFS = "focusguard_timed_block_protection"
        private const val KEY_PROTECTED_SESSION_IDS = "protected_session_ids"
        private const val KEY_PENDING_CREATION = "pending_creation"
        private const val KEY_PENDING_SINCE = "pending_since"
        private const val MAX_PENDING_CREATION_MILLIS = 5L * 60L * 1_000L

        @Volatile private var instance: TimedBlockProtectionController? = null

        fun getInstance(context: Context): TimedBlockProtectionController =
            instance ?: synchronized(this) {
                instance ?: TimedBlockProtectionController(context.applicationContext)
                    .also { instance = it }
            }
    }
}
