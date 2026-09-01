package com.focusguard.security

import com.focusguard.database.AppUsageLimit

/**
 * Central policy for irreversible protection and the master credential boundary.
 *
 * The master credential is intentionally NOT a password for protected targets.
 * It exists only for the explicit "remove all blocks" administrative action in
 * FocusGuard settings. Password/pattern/biometric credentials configured for a
 * PASSWORD block are independent and are handled by PasswordAppUnlockStore.
 *
 * Two protection invariants remain load-bearing:
 *  1. Dopamine Fast (`TIME`) and strict Pomodoro cannot be ended early by a
 *     credential.
 *  2. A time-hardened usage limit and Safety Mode cannot be mutated before their
 *     own protection rules permit it.
 */
object MasterCredentialPolicy {

    const val LOCK_MODE_NONE = "NONE"
    const val LOCK_MODE_PASSWORD = "PASSWORD"
    const val LOCK_MODE_TIME = "TIME"

    private const val SESSION_TYPE_TIME = "TIME"
    private const val SESSION_TYPE_POMODORO = "POMODORO"

    // ---------------------------------------------------------------- creation

    /**
     * Creating a block never depends on the master credential. A PASSWORD block
     * carries its own target credential; TIME/POMODORO use their own commitment
     * rules. Kept as a function for source compatibility with older callers.
     */
    @Suppress("UNUSED_PARAMETER")
    fun requiresMasterCredentialToCreate(sessionType: String): Boolean = false

    enum class CreationGate {
        ALLOWED,
        /** Legacy value retained for binary/source compatibility; no longer emitted. */
        MASTER_CREDENTIAL_REQUIRED
    }

    @Suppress("UNUSED_PARAMETER")
    fun evaluateCreation(
        sessionType: String,
        hasMasterCredential: Boolean
    ): CreationGate = CreationGate.ALLOWED

    // -------------------------------------------------------- limit mutation

    enum class MutationGate {
        ALLOWED,
        /** Legacy values retained for older UI branches; no longer emitted here. */
        MASTER_CREDENTIAL_REQUIRED,
        MASTER_CREDENTIAL_NOT_CONFIGURED,
        BLOCKED_BY_TIME_HARDENING,
        BLOCKED_BY_SAFETY_MODE
    }

    /**
     * Altering a usage limit is governed by the limit's own hardening and Safety
     * Mode. The master credential is deliberately ignored: it must never become a
     * generic password for individual limits.
     */
    @Suppress("UNUSED_PARAMETER")
    fun evaluateLimitMutation(
        lockMode: String,
        lockUntilTimestamp: Long?,
        safetyModeEnabled: Boolean,
        hasMasterCredential: Boolean,
        masterCredentialVerified: Boolean,
        nowMillis: Long = System.currentTimeMillis()
    ): MutationGate {
        if (isTimeHardened(lockMode, lockUntilTimestamp, nowMillis)) {
            return MutationGate.BLOCKED_BY_TIME_HARDENING
        }
        if (safetyModeEnabled) {
            return MutationGate.BLOCKED_BY_SAFETY_MODE
        }
        return MutationGate.ALLOWED
    }

    fun evaluateLimitMutation(
        limit: AppUsageLimit,
        safetyModeEnabled: Boolean,
        hasMasterCredential: Boolean,
        masterCredentialVerified: Boolean,
        nowMillis: Long = System.currentTimeMillis()
    ): MutationGate = evaluateLimitMutation(
        lockMode = limit.lockMode,
        lockUntilTimestamp = limit.lockUntilTimestamp,
        safetyModeEnabled = safetyModeEnabled,
        hasMasterCredential = hasMasterCredential,
        masterCredentialVerified = masterCredentialVerified,
        nowMillis = nowMillis
    )

    fun isTimeHardened(
        lockMode: String,
        lockUntilTimestamp: Long?,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        if (!lockMode.equals(LOCK_MODE_TIME, ignoreCase = true)) return false
        val until = lockUntilTimestamp ?: return false
        return until > nowMillis
    }

    // ------------------------------------------------------------- uninstall

    enum class UninstallGate {
        ALLOWED,
        /** Legacy values retained for older UI branches; no longer emitted here. */
        MASTER_CREDENTIAL_REQUIRED,
        MASTER_CREDENTIAL_NOT_CONFIGURED,
        BLOCKED_BY_ACTIVE_IRREVERSIBLE_BLOCK
    }

    /**
     * Uninstall is independent from the master credential. An active irreversible
     * TIME commitment can still refuse uninstall, except in its maintenance
     * window; otherwise uninstall is allowed without reusing the master password.
     */
    @Suppress("UNUSED_PARAMETER")
    fun evaluateUninstall(
        hasActiveIrreversibleBlock: Boolean,
        hasMasterCredential: Boolean,
        masterCredentialVerified: Boolean,
        maintenanceWindowActive: Boolean = false
    ): UninstallGate {
        if (maintenanceWindowActive) return UninstallGate.ALLOWED
        if (hasActiveIrreversibleBlock) {
            return UninstallGate.BLOCKED_BY_ACTIVE_IRREVERSIBLE_BLOCK
        }
        return UninstallGate.ALLOWED
    }

    fun isIrreversibleSessionType(sessionType: String): Boolean = when (sessionType.uppercase()) {
        SESSION_TYPE_TIME, SESSION_TYPE_POMODORO -> true
        else -> false
    }

    fun isTimeCommitmentActive(
        sessionType: String,
        isActive: Boolean,
        endTime: Long?,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        if (!isActive || !sessionType.equals(SESSION_TYPE_TIME, ignoreCase = true)) {
            return false
        }
        return endTime == null || endTime > nowMillis
    }

    fun blocksUninstall(sessionType: String): Boolean =
        sessionType.equals(SESSION_TYPE_TIME, ignoreCase = true)
}
