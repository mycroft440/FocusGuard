package com.focusguard.security

import com.focusguard.database.AppUsageLimit

/**
 * Decides where the master credential (the deactivation password managed by
 * [DeactivationCredentialManager]) is required, and — just as importantly —
 * where it is *not enough*.
 *
 * Two product invariants are load-bearing here and must not be softened:
 *
 *  1. A Dopamine Fast (`TIME`) and a strict Pomodoro cannot be ended early by
 *     any credential. The app tells the user this in
 *     `R.string.dopamine_warning` and `R.string.create_session_time_warning`.
 *  2. A time-hardened usage limit (`lockMode == "TIME"` with a future
 *     `lockUntilTimestamp`) and Safety Mode cannot be lifted before expiry.
 *     The app promises this in `R.string.limits_security_mode_warning`,
 *     `R.string.limits_add_days_warning` and `R.string.status_safety_mode_warning`.
 *
 * The master credential is therefore a *prerequisite* for arming protection and
 * a *key* for reversible protection — never a master override.
 */
object MasterCredentialPolicy {

    const val LOCK_MODE_NONE = "NONE"
    const val LOCK_MODE_PASSWORD = "PASSWORD"
    const val LOCK_MODE_TIME = "TIME"

    private const val SESSION_TYPE_TIME = "TIME"
    private const val SESSION_TYPE_POMODORO = "POMODORO"
    private const val SESSION_TYPE_PASSWORD = "PASSWORD"

    // ---------------------------------------------------------------- creation

    /**
     * Every password block and every dopamine fast requires the master
     * credential to exist *before* it starts.
     *
     * For a password block it is the only way out. For a dopamine fast there is
     * no way out at all, so the credential is what still lets the user manage
     * limits and uninstall the app afterwards — without it, an interrupted fast
     * would leave them with no authenticated path anywhere.
     *
     * Pomodoro is deliberately excluded: it is a short focus timer the user
     * starts many times a day, not one of the blocks the app presents as
     * "bloqueio por tempo"/"bloqueio por senha". Demanding a password to start a
     * 25-minute timer would be friction with no protective payoff. Note that it
     * is still irreversible once running — see [isIrreversibleSessionType],
     * which answers a different question (can this be *ended* early?).
     */
    fun requiresMasterCredentialToCreate(sessionType: String): Boolean {
        return when (sessionType.uppercase()) {
            SESSION_TYPE_TIME, SESSION_TYPE_PASSWORD -> true
            else -> false
        }
    }

    /** Result of checking whether a new block may be armed. */
    enum class CreationGate {
        ALLOWED,

        /** The master credential has not been configured yet: send the user to set it. */
        MASTER_CREDENTIAL_REQUIRED
    }

    fun evaluateCreation(
        sessionType: String,
        hasMasterCredential: Boolean
    ): CreationGate {
        val needsCredential = requiresMasterCredentialToCreate(sessionType)
        return if (needsCredential && !hasMasterCredential) {
            CreationGate.MASTER_CREDENTIAL_REQUIRED
        } else {
            CreationGate.ALLOWED
        }
    }

    // -------------------------------------------------------- limit mutation

    /** Why a usage-limit change was refused, or that it is permitted. */
    enum class MutationGate {
        /** Credential already verified in this flow: proceed. */
        ALLOWED,

        /** Needs the master credential prompt before proceeding. */
        MASTER_CREDENTIAL_REQUIRED,

        /** No credential configured at all: the user must create one first. */
        MASTER_CREDENTIAL_NOT_CONFIGURED,

        /** Time-hardened until [AppUsageLimit.lockUntilTimestamp]; refuse outright. */
        BLOCKED_BY_TIME_HARDENING,

        /** Safety Mode is on; refuse outright. */
        BLOCKED_BY_SAFETY_MODE
    }

    /**
     * Gate for altering or removing a usage limit.
     *
     * Order matters: the unbreakable refusals are evaluated *before* the
     * credential prompt, so the user is never asked for a password that cannot
     * unlock anything.
     */
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
        if (!hasMasterCredential) {
            return MutationGate.MASTER_CREDENTIAL_NOT_CONFIGURED
        }
        if (!masterCredentialVerified) {
            return MutationGate.MASTER_CREDENTIAL_REQUIRED
        }
        return MutationGate.ALLOWED
    }

    /** Convenience overload for a persisted limit row. */
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

    /**
     * A limit is time-hardened while its lock mode is `TIME` and the expiry is
     * still in the future. A null or already-elapsed timestamp is not hardening
     * — an expired lock must never strand the user.
     */
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

    /** Why uninstalling FocusGuard was refused, or that it is permitted. */
    enum class UninstallGate {
        ALLOWED,
        MASTER_CREDENTIAL_REQUIRED,
        MASTER_CREDENTIAL_NOT_CONFIGURED,

        /** An unbreakable block is running: uninstall would be an escape hatch. */
        BLOCKED_BY_ACTIVE_IRREVERSIBLE_BLOCK
    }

    /**
     * Gate for the authenticated uninstall path.
     *
     * The user is entitled to leave the app — but not to use uninstall as a way
     * out of a dopamine fast they chose. While an irreversible block runs,
     * uninstall waits; everything else is unlocked by the master credential.
     */
    fun evaluateUninstall(
        hasActiveIrreversibleBlock: Boolean,
        hasMasterCredential: Boolean,
        masterCredentialVerified: Boolean
    ): UninstallGate {
        if (hasActiveIrreversibleBlock) {
            return UninstallGate.BLOCKED_BY_ACTIVE_IRREVERSIBLE_BLOCK
        }
        if (!hasMasterCredential) {
            return UninstallGate.MASTER_CREDENTIAL_NOT_CONFIGURED
        }
        if (!masterCredentialVerified) {
            return UninstallGate.MASTER_CREDENTIAL_REQUIRED
        }
        return UninstallGate.ALLOWED
    }

    /** True for session types that cannot be ended early by any credential. */
    fun isIrreversibleSessionType(sessionType: String): Boolean {
        return when (sessionType.uppercase()) {
            SESSION_TYPE_TIME, SESSION_TYPE_POMODORO -> true
            else -> false
        }
    }
}
