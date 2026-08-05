package com.focusguard.security

/**
 * Decides whether a fingerprint may be offered to open a blocked app.
 *
 * Biometrics here are strictly a *convenience substitute for a password that is
 * already accepted* — never a new way out. The rule is one sentence: if typing
 * the password would open this app, the fingerprint may too; otherwise the
 * fingerprint is not offered at all.
 *
 * That keeps two promises intact:
 *  - A Dopamine Fast stays sealed (`R.string.dopamine_warning`).
 *  - A time-hardened limit stays sealed until expiry
 *    (`R.string.limits_security_mode_warning`).
 *
 * Scope is also limited to the moment of opening a blocked app. Managing
 * passwords, editing limits and uninstalling all go through the master
 * credential instead — see [MasterCredentialPolicy].
 */
object BiometricAppUnlockPolicy {

    /** What kind of protection is currently keeping the app closed. */
    enum class BlockOrigin {
        /** Session with `sessionType == "PASSWORD"`: password ends it. */
        PASSWORD_SESSION,

        /** Usage limit exceeded, configured with `unlockWithPassword = true`. */
        USAGE_LIMIT_PASSWORD_UNLOCK,

        /** Usage limit exceeded with no password unlock configured. */
        USAGE_LIMIT_NO_UNLOCK,

        /** Usage limit hardened by time until a future timestamp. */
        USAGE_LIMIT_TIME_HARDENED,

        /** Dopamine fast (`sessionType == "TIME"`). */
        DOPAMINE_FAST,

        /** Strict Pomodoro lock. */
        STRICT_POMODORO
    }

    /**
     * Whether a password (and therefore, optionally, a fingerprint) can open
     * this block at all. This is the product invariant, independent of any user
     * preference.
     */
    fun acceptsCredentialUnlock(origin: BlockOrigin): Boolean {
        return when (origin) {
            BlockOrigin.PASSWORD_SESSION,
            BlockOrigin.USAGE_LIMIT_PASSWORD_UNLOCK -> true

            BlockOrigin.USAGE_LIMIT_NO_UNLOCK,
            BlockOrigin.USAGE_LIMIT_TIME_HARDENED,
            BlockOrigin.DOPAMINE_FAST,
            BlockOrigin.STRICT_POMODORO -> false
        }
    }

    /**
     * Whether to show the fingerprint affordance on the block notice.
     *
     * Requires all three: the block accepts credential unlock, the user turned
     * the preference on, and the device actually has biometrics enrolled.
     */
    fun shouldOfferBiometricUnlock(
        origin: BlockOrigin,
        biometricUnlockEnabled: Boolean,
        biometricAvailable: Boolean
    ): Boolean {
        return acceptsCredentialUnlock(origin) &&
            biometricUnlockEnabled &&
            biometricAvailable
    }

    /**
     * Guard for the success callback. Re-checked after the prompt returns so a
     * block that became irreversible while the sheet was open — or a callback
     * arriving for the wrong origin — cannot unlock anything.
     */
    fun canAcceptBiometricResult(
        origin: BlockOrigin,
        biometricUnlockEnabled: Boolean
    ): Boolean {
        return acceptsCredentialUnlock(origin) && biometricUnlockEnabled
    }

    /**
     * Classifies what is blocking the app right now.
     *
     * Precedence is deliberate: strict Pomodoro and the dopamine fast outrank
     * usage limits, because when several protections overlap the *strictest* one
     * must decide. Getting this backwards would let a permissive limit unseal a
     * fast.
     */
    fun classify(
        strictPomodoroActive: Boolean,
        activeSessionType: String?,
        usageLimitExceeded: Boolean,
        limitLockMode: String?,
        limitLockUntilTimestamp: Long?,
        limitUnlockWithPassword: Boolean,
        nowMillis: Long = System.currentTimeMillis()
    ): BlockOrigin? {
        if (strictPomodoroActive) return BlockOrigin.STRICT_POMODORO

        val sessionType = activeSessionType?.uppercase()
        if (sessionType != null && MasterCredentialPolicy.isIrreversibleSessionType(sessionType)) {
            return BlockOrigin.DOPAMINE_FAST
        }

        if (usageLimitExceeded) {
            val hardened = MasterCredentialPolicy.isTimeHardened(
                lockMode = limitLockMode.orEmpty(),
                lockUntilTimestamp = limitLockUntilTimestamp,
                nowMillis = nowMillis
            )
            return when {
                hardened -> BlockOrigin.USAGE_LIMIT_TIME_HARDENED
                limitUnlockWithPassword -> BlockOrigin.USAGE_LIMIT_PASSWORD_UNLOCK
                else -> BlockOrigin.USAGE_LIMIT_NO_UNLOCK
            }
        }

        if (sessionType == "PASSWORD") return BlockOrigin.PASSWORD_SESSION

        return null
    }
}
