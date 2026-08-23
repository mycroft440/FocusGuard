package com.focusguard.security

import com.focusguard.database.AppUsageLimit

/**
 * Decides where the master credential (the deactivation password managed by
 * [DeactivationCredentialManager]) is required, and — just as importantly —
 * where it is *not enough*.
 *
 * Product invariants:
 *
 *  1. An explicit time block (`TIME`) requires a master credential before it is
 *     armed. The running block remains sealed against per-target edits,
 *     biometrics and ordinary mutation paths, but the whole protected TIME
 *     session may be revoked through the dedicated master-password flow.
 *  2. A strict Pomodoro cannot be ended early by any credential.
 *  3. A time-hardened usage limit (`lockMode == "TIME"` with a future
 *     `lockUntilTimestamp`) and Safety Mode cannot be lifted before expiry.
 *
 * The dedicated TIME revocation path is intentionally separate from app-open
 * authentication: knowing the master password never turns a blocked app into a
 * one-tap bypass. It ends the whole commitment after an explicit confirmation.
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
     * PASSWORD and the explicit TIME block both need the master credential up
     * front because each has a credential-governed exit. Pomodoro remains a
     * short focus timer and does not require the credential to start.
     */
    fun requiresMasterCredentialToCreate(sessionType: String): Boolean {
        return when (sessionType.uppercase()) {
            SESSION_TYPE_PASSWORD, SESSION_TYPE_TIME -> true
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
        // Só o modo que o usuário escolheu proteger por senha exige a senha
        // mestra. O modo NONE é, por definição, a alternativa sem credencial.
        if (lockMode.equals(LOCK_MODE_PASSWORD, ignoreCase = true)) {
            if (!hasMasterCredential) {
                return MutationGate.MASTER_CREDENTIAL_NOT_CONFIGURED
            }
            if (!masterCredentialVerified) {
                return MutationGate.MASTER_CREDENTIAL_REQUIRED
            }
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

        /** A sealed block is running: revoke it through its dedicated exit first. */
        BLOCKED_BY_ACTIVE_IRREVERSIBLE_BLOCK
    }

    /**
     * Gate for the authenticated uninstall path.
     *
     * While a protected TIME session is running, uninstall is not used as a
     * shortcut around the explicit revocation flow. The user first revokes that
     * TIME commitment with the master password; once no such block remains, the
     * normal authenticated uninstall path is available again.
     */
    fun evaluateUninstall(
        hasActiveIrreversibleBlock: Boolean,
        hasMasterCredential: Boolean,
        masterCredentialVerified: Boolean,
        maintenanceWindowActive: Boolean = false
    ): UninstallGate {
        if (maintenanceWindowActive) {
            return UninstallGate.ALLOWED
        }
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

    /**
     * True for session types that remain sealed against ordinary per-target,
     * biometric and generic mutation paths. TIME is included because its only
     * early exit is the dedicated whole-session master-password revocation flow.
     */
    fun isIrreversibleSessionType(sessionType: String): Boolean {
        return when (sessionType.uppercase()) {
            SESSION_TYPE_TIME, SESSION_TYPE_POMODORO -> true
            else -> false
        }
    }

    /** Only the explicit TIME session has a dedicated whole-block master exit. */
    fun allowsExplicitMasterRevocation(sessionType: String): Boolean =
        sessionType.equals(SESSION_TYPE_TIME, ignoreCase = true)

    /**
     * Only the explicit TIME block prevents uninstall while its protected
     * commitment is active. A PASSWORD session is removable with its credential,
     * and Pomodoro is a focus timer rather than the long-term uninstall guard.
     */
    fun blocksUninstall(sessionType: String): Boolean =
        sessionType.equals(SESSION_TYPE_TIME, ignoreCase = true)
}
