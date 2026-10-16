package com.focusguard.security

import com.focusguard.database.AppUsageLimit
import com.focusguard.database.BlockSession
import com.focusguard.database.WebsiteUsageLimit

/**
 * Central policy for irreversible protection and the master credential boundary.
 *
 * The master credential is administrative and remains independent from credentials
 * configured for individual PASSWORD targets. Creating blocking sessions and
 * configuring ordinary usage limits do not depend on the master credential;
 * active protection rules remain governed by their own hardening and maintenance
 * boundaries.
 *
 * Two protection invariants remain load-bearing:
 *  1. Dopamine Fast (`TIME`) cannot be ended early by a credential. The only way
 *     out before its end is the optional cancel window chosen at creation (48 h by
 *     default); once it passes, the block cannot be undone.
 *  2. A time-hardened usage limit and Safety Mode cannot be mutated before their
 *     own protection rules permit it. While a limit is locked that way it is also
 *     a commitment like the fast: it arms self-protection and refuses uninstall.
 */
object MasterCredentialPolicy {

    const val LOCK_MODE_NONE = "NONE"
    const val LOCK_MODE_PASSWORD = "PASSWORD"
    const val LOCK_MODE_TIME = "TIME"

    private const val SESSION_TYPE_TIME = "TIME"
    private const val SESSION_TYPE_POMODORO = "POMODORO"

    // ---------------------------------------------------------------- creation

    /** Creating a blocking session never redirects through master-password setup. */
    @Suppress("UNUSED_PARAMETER")
    fun requiresMasterCredentialToCreate(sessionType: String): Boolean = false

    enum class CreationGate {
        ALLOWED,
        /** Retained for source compatibility with older callers. */
        MASTER_CREDENTIAL_REQUIRED
    }

    @Suppress("UNUSED_PARAMETER")
    fun evaluateCreation(
        sessionType: String,
        hasMasterCredential: Boolean
    ): CreationGate = CreationGate.ALLOWED

    // ----------------------------------------------- credential configuration

    enum class ConfigurationGate {
        ALLOWED,
        BLOCKED_BY_TIME_BLOCK,
        BLOCKED_BY_USAGE_LIMIT
    }

    /**
     * The master credential must be chosen before an irreversible TIME block or
     * an enabled usage limit already exists. PASSWORD sessions are deliberately
     * ignored because their target credentials are independent from the master
     * credential. Pomodoro is also outside this gate: only the explicit TIME
     * blocking mode requested by this boundary prevents configuration.
     */
    fun evaluateCredentialConfiguration(
        activeSessions: Iterable<BlockSession>,
        hasActiveUsageLimit: Boolean,
        nowMillis: Long = System.currentTimeMillis()
    ): ConfigurationGate {
        val hasActiveTimeBlock = activeSessions.any { session ->
            isTimeCommitmentActive(
                sessionType = session.sessionType,
                isActive = session.isActive,
                endTime = session.endTime,
                nowMillis = nowMillis
            )
        }
        if (hasActiveTimeBlock) return ConfigurationGate.BLOCKED_BY_TIME_BLOCK
        if (hasActiveUsageLimit) return ConfigurationGate.BLOCKED_BY_USAGE_LIMIT
        return ConfigurationGate.ALLOWED
    }

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
     * Mode. The master credential is deliberately ignored: it must not become a
     * generic password for creating, editing or removing individual limits.
     */
    @Suppress("UNUSED_PARAMETER")
    fun evaluateLimitMutation(
        lockMode: String,
        lockUntilTimestamp: Long?,
        safetyModeEnabled: Boolean,
        hasMasterCredential: Boolean,
        masterCredentialVerified: Boolean,
        nowMillis: Long = System.currentTimeMillis(),
        /** Ver [isLimitLockedAfterCancelWindow]. */
        lockAfter: Long? = null
    ): MutationGate {
        if (isTimeHardened(lockMode, lockUntilTimestamp, nowMillis) ||
            isLimitLockedAfterCancelWindow(lockAfter, lockUntilTimestamp, nowMillis)
        ) {
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
        nowMillis = nowMillis,
        lockAfter = limit.lockAfter
    )

    /**
     * Limite criado com a opção "só fica sem volta depois de 48 h": alterável até
     * [lockAfter] e travado dali até o fim da regra. Sem [lockAfter] (limites antigos),
     * nunca trava por esta regra.
     */
    fun isLimitLockedAfterCancelWindow(
        lockAfter: Long?,
        ruleEndMillis: Long?,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean = lockAfter != null &&
        nowMillis >= lockAfter &&
        (ruleEndMillis == null || nowMillis < ruleEndMillis)

    /** Quando um limite novo passa a travar, conforme a opção das 48 h. */
    fun limitLockAfter(createdAtMillis: Long, cancelWindowEnabled: Boolean): Long =
        if (cancelWindowEnabled) {
            createdAtMillis + CANCEL_WINDOW_HOURS * 3_600_000L
        } else {
            createdAtMillis
        }

    /**
     * Limite sem volta agora: o modo antigo "TIME" dentro do prazo ou, nos limites
     * atuais, travado entre o fim da janela das 48 h (ou a criação, com ela desligada)
     * e o fim da regra. Como o jejum, arma a autoproteção e impede desinstalar: sem
     * isso, desligar a acessibilidade, revogar o Acesso de uso ou desinstalar o app
     * desfazia um limite que não podia mais ser removido.
     */
    fun isLimitCommitmentActive(
        isEnabled: Boolean,
        lockMode: String,
        lockUntilTimestamp: Long?,
        lockAfter: Long?,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean = isEnabled && (
        isTimeHardened(lockMode, lockUntilTimestamp, nowMillis) ||
            // Sem fim de regra não há "até": um limite assim nunca vira compromisso.
            (lockUntilTimestamp != null &&
                isLimitLockedAfterCancelWindow(lockAfter, lockUntilTimestamp, nowMillis))
        )

    fun isLimitCommitmentActive(
        limit: AppUsageLimit,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean = isLimitCommitmentActive(
        isEnabled = limit.isEnabled,
        lockMode = limit.lockMode,
        lockUntilTimestamp = limit.lockUntilTimestamp,
        lockAfter = limit.lockAfter,
        nowMillis = nowMillis
    )

    fun isLimitCommitmentActive(
        limit: WebsiteUsageLimit,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean = isLimitCommitmentActive(
        isEnabled = limit.isEnabled,
        lockMode = limit.lockMode,
        lockUntilTimestamp = limit.lockUntilTimestamp,
        lockAfter = limit.lockAfter,
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

    /** Número de horas em que um bloqueio por tempo recém-criado ainda pode ser cancelado. */
    const val CANCEL_WINDOW_HOURS = 48

    /**
     * Se um bloqueio por tempo ainda está no período em que pode ser cancelado. Depois
     * dele (ou sem ele, nos bloqueios criados sem a opção), o bloqueio é sem volta.
     */
    fun isWithinCancelWindow(
        cancelableUntil: Long?,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean = cancelableUntil != null && nowMillis < cancelableUntil

    fun blocksUninstall(sessionType: String): Boolean =
        sessionType.equals(SESSION_TYPE_TIME, ignoreCase = true)
}
