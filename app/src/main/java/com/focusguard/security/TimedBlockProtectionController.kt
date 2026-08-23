package com.focusguard.security

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Build
import com.focusguard.admin.FocusGuardDeviceAdminReceiver
import com.focusguard.database.AppDatabase
import com.focusguard.utils.FocusGuardLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Sole owner of package-level self-protection for the explicit "block by time" flow.
 *
 * The protection record lives in Device Protected Storage so the uninstall/user-control
 * policies can be restored before the first unlock after a reboot. Other blocking modes are
 * never adopted here, even if they happen to use the TIME database type internally.
 */
class TimedBlockProtectionController private constructor(private val context: Context) {

    data class HealthSnapshot(
        val deviceOwnerReady: Boolean,
        val phase: TimedBlockProtectionStateMachine.Phase,
        val protectedSessionCount: Int,
        val uninstallBlocked: Boolean,
        val userControlDisabled: Boolean,
        val lastPolicyIdentifier: String?,
        val lastPolicyResultCode: Int?
    ) {
        val systemProtectionConfirmed: Boolean
            get() = deviceOwnerReady && uninstallBlocked && userControlDisabled
    }

    private data class ProtectedRecord(val sessionId: Int, val endTimeMillis: Long?) {
        fun serialize(): String = "$sessionId:${endTimeMillis ?: OPEN_ENDED_SENTINEL}"

        companion object {
            fun parse(raw: String): ProtectedRecord? {
                val separator = raw.indexOf(':')
                if (separator <= 0) return null
                val id = raw.substring(0, separator).toIntOrNull() ?: return null
                val encodedEnd = raw.substring(separator + 1).toLongOrNull() ?: return null
                return ProtectedRecord(
                    sessionId = id,
                    endTimeMillis = if (encodedEnd == OPEN_ENDED_SENTINEL) null else encodedEnd
                )
            }
        }
    }

    private val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val admin = FocusGuardDeviceAdminReceiver.getComponentName(context)
    private val storageContext = runCatching {
        context.createDeviceProtectedStorageContext()
    }.getOrDefault(context)
    private val preferences = storageContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isDeviceOwnerReady(): Boolean = runCatching {
        dpm.isDeviceOwnerApp(context.packageName)
    }.getOrDefault(false)

    fun phase(): TimedBlockProtectionStateMachine.Phase =
        TimedBlockProtectionStateMachine.Phase.fromStorage(
            preferences.getString(KEY_PHASE, null)
        )

    /**
     * Closes the uninstall/force-stop race before Room creates the TIME session.
     * [commitProtectedTimeSession] must be called with the exact inserted session id.
     */
    fun prepareForTimeCreation(nowMillis: Long = System.currentTimeMillis()): Boolean {
        if (!isDeviceOwnerReady()) return false
        val saved = preferences.edit()
            .putBoolean(KEY_PENDING_CREATION, true)
            .putLong(KEY_PENDING_SINCE, nowMillis)
            .putString(KEY_PHASE, TimedBlockProtectionStateMachine.Phase.PREPARING.storageValue)
            .commit()
        if (!saved) return false

        val applied = applyPackageProtection(true)
        if (!applied) {
            preferences.edit()
                .putString(KEY_PHASE, TimedBlockProtectionStateMachine.Phase.ERROR.storageValue)
                .commit()
        }
        return applied
    }

    /** Binds protection to one exact DB id; no "newest TIME" heuristic is used. */
    suspend fun commitProtectedTimeSession(sessionId: Int): Boolean = withContext(Dispatchers.IO) {
        if (!isDeviceOwnerReady()) return@withContext false
        val session = AppDatabase.getDatabase(context).blockSessionDao().getActiveSessionById(sessionId)
            ?: return@withContext failPending("Sessão TIME $sessionId não existe ou não está ativa")

        if (!session.sessionType.equals("TIME", ignoreCase = true) ||
            !TimedBlockProtectionPolicy.requiresUninstallProtection(
                sessionType = session.sessionType,
                isActive = session.isActive,
                endTimeMillis = session.endTime
            )
        ) {
            return@withContext failPending("Sessão $sessionId não é um TIME protegível")
        }

        val records = protectedRecords().associateBy { it.sessionId }.toMutableMap()
        records[sessionId] = ProtectedRecord(sessionId, session.endTime)
        val saved = persistRecords(
            records = records.values,
            pending = false,
            phase = TimedBlockProtectionStateMachine.Phase.ACTIVE
        )
        if (!saved) return@withContext false

        val applied = applyPackageProtection(true)
        if (!applied) {
            setPhase(TimedBlockProtectionStateMachine.Phase.ERROR)
        }
        applied
    }

    /** Roll back only the exact TIME session whose protection failed to commit. */
    suspend fun rollbackProtectedTimeSession(sessionId: Int): Boolean = withContext(Dispatchers.IO) {
        val database = AppDatabase.getDatabase(context)
        val removed = database.blockSessionDao().deactivateSession(sessionId) > 0
        removeProtectedRecord(sessionId)
        clearPendingCreation()
        reconcileFromDatabase()
        removed
    }

    suspend fun cancelPendingCreation() = withContext(Dispatchers.IO) {
        clearPendingCreation()
        reconcileFromDatabase()
    }

    fun beginRevocation() {
        setPhase(TimedBlockProtectionStateMachine.Phase.REVOKING)
    }

    fun isProtectedSession(sessionId: Int): Boolean =
        protectedRecords().any { it.sessionId == sessionId }

    fun protectedSessionIdsSnapshot(): Set<Int> =
        protectedRecords().mapTo(linkedSetOf()) { it.sessionId }

    fun hasProtectedSessions(): Boolean = protectedRecords().isNotEmpty()

    /**
     * Reconciles persisted commitments against Room and then makes package policy match exactly.
     * Untracked TIME sessions are deliberately ignored: this is what limits the feature to the
     * explicit "Bloquear apps por tempo" flow.
     */
    suspend fun reconcileFromDatabase(
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean = withContext(Dispatchers.IO) {
        if (!isDeviceOwnerReady()) return@withContext false

        val database = AppDatabase.getDatabase(context)
        val activeById = database.blockSessionDao().getAllActiveSessionsStatic().associateBy { it.id }
        val validRecords = protectedRecords().mapNotNullTo(linkedSetOf()) { record ->
            val session = activeById[record.sessionId] ?: return@mapNotNullTo null
            if (!TimedBlockProtectionPolicy.requiresUninstallProtection(
                    sessionType = session.sessionType,
                    isActive = session.isActive,
                    endTimeMillis = session.endTime,
                    nowMillis = nowMillis
                )
            ) return@mapNotNullTo null
            ProtectedRecord(session.id, session.endTime)
        }

        val pending = preferences.getBoolean(KEY_PENDING_CREATION, false) &&
            isPendingCreationFresh(nowMillis)
        val required = validRecords.isNotEmpty() || pending
        val nextPhase = TimedBlockProtectionStateMachine.afterReconcile(
            hasProtectedSessions = validRecords.isNotEmpty(),
            pendingCreation = pending,
            policyApplied = true
        )
        if (!persistRecords(validRecords, pending, nextPhase)) return@withContext false

        val applied = applyPackageProtection(required)
        if (!applied && required) setPhase(TimedBlockProtectionStateMachine.Phase.ERROR)
        applied
    }

    /**
     * Direct-Boot path: no Room/credential-encrypted storage is touched. Finite sessions whose
     * persisted deadline already elapsed are dropped before policy is restored.
     */
    fun restorePersistedAtDirectBoot(nowMillis: Long = System.currentTimeMillis()): Boolean {
        if (!isDeviceOwnerReady()) return false

        val stillValid = protectedRecords().filterTo(linkedSetOf()) { record ->
            record.endTimeMillis == null || record.endTimeMillis > nowMillis
        }
        val pending = preferences.getBoolean(KEY_PENDING_CREATION, false) &&
            isPendingCreationFresh(nowMillis)
        val required = stillValid.isNotEmpty() || pending
        val nextPhase = TimedBlockProtectionStateMachine.afterReconcile(
            hasProtectedSessions = stillValid.isNotEmpty(),
            pendingCreation = pending,
            policyApplied = true
        )
        persistRecords(stillValid, pending, nextPhase)
        return applyPackageProtection(required)
    }

    fun healthSnapshot(): HealthSnapshot {
        val ownerReady = isDeviceOwnerReady()
        val uninstallBlocked = if (ownerReady) runCatching {
            dpm.isUninstallBlocked(admin, context.packageName)
        }.getOrDefault(false) else false
        val userControlDisabled = if (ownerReady && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                context.packageName in dpm.getUserControlDisabledPackages(admin)
            }.getOrDefault(false)
        } else ownerReady

        return HealthSnapshot(
            deviceOwnerReady = ownerReady,
            phase = phase(),
            protectedSessionCount = protectedRecords().size,
            uninstallBlocked = uninstallBlocked,
            userControlDisabled = userControlDisabled,
            lastPolicyIdentifier = preferences.getString(KEY_LAST_POLICY_IDENTIFIER, null),
            lastPolicyResultCode = if (preferences.contains(KEY_LAST_POLICY_RESULT)) {
                preferences.getInt(KEY_LAST_POLICY_RESULT, Int.MIN_VALUE)
            } else null
        )
    }

    /** Stores Android 14+ policy callbacks without trusting them as the only verification path. */
    fun recordPolicyUpdate(
        policyIdentifier: String,
        packageName: String?,
        resultCode: Int,
        changed: Boolean
    ) {
        if (packageName != null && packageName != context.packageName) return
        preferences.edit()
            .putString(KEY_LAST_POLICY_IDENTIFIER, policyIdentifier)
            .putInt(KEY_LAST_POLICY_RESULT, resultCode)
            .putBoolean(KEY_LAST_POLICY_WAS_CHANGE, changed)
            .putLong(KEY_LAST_POLICY_AT, System.currentTimeMillis())
            .commit()
    }

    private fun failPending(message: String): Boolean {
        FocusGuardLogger.log("TimedBlockProtection", message)
        clearPendingCreation()
        setPhase(TimedBlockProtectionStateMachine.Phase.ERROR)
        applyPackageProtection(protectedRecords().isNotEmpty())
        return false
    }

    private fun protectedRecords(): Set<ProtectedRecord> {
        val encoded = preferences.getStringSet(KEY_PROTECTED_RECORDS, emptySet()).orEmpty()
        val parsed = encoded.mapNotNullTo(linkedSetOf(), ProtectedRecord::parse)
        if (parsed.isNotEmpty()) return parsed

        // Migration from the first implementation, which stored only ids. Unknown deadlines are
        // treated conservatively as open-ended until the first credential-unlocked reconcile.
        return preferences.getStringSet(KEY_LEGACY_PROTECTED_SESSION_IDS, emptySet())
            .orEmpty()
            .mapNotNullTo(linkedSetOf()) { id -> id.toIntOrNull()?.let { ProtectedRecord(it, null) } }
    }

    private fun persistRecords(
        records: Collection<ProtectedRecord>,
        pending: Boolean,
        phase: TimedBlockProtectionStateMachine.Phase
    ): Boolean {
        val editor = preferences.edit()
            .putStringSet(KEY_PROTECTED_RECORDS, records.mapTo(linkedSetOf(), ProtectedRecord::serialize))
            .remove(KEY_LEGACY_PROTECTED_SESSION_IDS)
            .putBoolean(KEY_PENDING_CREATION, pending)
            .putString(KEY_PHASE, phase.storageValue)
        if (!pending) editor.remove(KEY_PENDING_SINCE)
        return editor.commit()
    }

    private fun removeProtectedRecord(sessionId: Int) {
        val remaining = protectedRecords().filterNot { it.sessionId == sessionId }
        persistRecords(
            records = remaining,
            pending = preferences.getBoolean(KEY_PENDING_CREATION, false),
            phase = if (remaining.isEmpty()) {
                TimedBlockProtectionStateMachine.Phase.IDLE
            } else {
                TimedBlockProtectionStateMachine.Phase.ACTIVE
            }
        )
    }

    private fun clearPendingCreation() {
        preferences.edit()
            .putBoolean(KEY_PENDING_CREATION, false)
            .remove(KEY_PENDING_SINCE)
            .commit()
    }

    private fun setPhase(phase: TimedBlockProtectionStateMachine.Phase) {
        preferences.edit().putString(KEY_PHASE, phase.storageValue).commit()
    }

    private fun isPendingCreationFresh(nowMillis: Long): Boolean {
        val since = preferences.getLong(KEY_PENDING_SINCE, 0L)
        return since > 0L && nowMillis - since <= MAX_PENDING_CREATION_MILLIS
    }

    /**
     * TIME owns both package policies. Existing unrelated disabled-control packages are preserved
     * instead of replacing the administrator's whole list.
     */
    private fun applyPackageProtection(blocked: Boolean): Boolean {
        return runCatching {
            dpm.setUninstallBlocked(admin, context.packageName, blocked)

            val userControlApplied = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val current = dpm.getUserControlDisabledPackages(admin).toMutableSet()
                if (blocked) current.add(context.packageName) else current.remove(context.packageName)
                dpm.setUserControlDisabledPackages(admin, current.toList())
                context.packageName in dpm.getUserControlDisabledPackages(admin) == blocked
            } else true

            val uninstallApplied = dpm.isUninstallBlocked(admin, context.packageName) == blocked
            uninstallApplied && userControlApplied
        }.onFailure { error ->
            FocusGuardLogger.logError(
                "TimedBlockProtection",
                "Falha ao reconciliar proteção TIME (blocked=$blocked)",
                error
            )
        }.getOrDefault(false)
    }

    companion object {
        private const val PREFS = "focusguard_timed_block_protection"
        private const val KEY_PROTECTED_RECORDS = "protected_records_v2"
        private const val KEY_LEGACY_PROTECTED_SESSION_IDS = "protected_session_ids"
        private const val KEY_PENDING_CREATION = "pending_creation"
        private const val KEY_PENDING_SINCE = "pending_since"
        private const val KEY_PHASE = "phase"
        private const val KEY_LAST_POLICY_IDENTIFIER = "last_policy_identifier"
        private const val KEY_LAST_POLICY_RESULT = "last_policy_result"
        private const val KEY_LAST_POLICY_WAS_CHANGE = "last_policy_was_change"
        private const val KEY_LAST_POLICY_AT = "last_policy_at"
        private const val MAX_PENDING_CREATION_MILLIS = 5L * 60L * 1_000L
        private const val OPEN_ENDED_SENTINEL = Long.MAX_VALUE

        @Volatile private var instance: TimedBlockProtectionController? = null

        fun getInstance(context: Context): TimedBlockProtectionController =
            instance ?: synchronized(this) {
                instance ?: TimedBlockProtectionController(context.applicationContext)
                    .also { instance = it }
            }
    }
}
