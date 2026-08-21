package com.focusguard.security

/**
 * Decides when a revoked Usage Access permission is worth warning about.
 *
 * Every app usage limit is measured through `queryAndAggregateUsageStats`. Without
 * the permission that call returns an empty map, so no limit is ever seen as
 * exceeded and **every app limit silently stops enforcing** — the app looks like
 * it is working while it is not. Android also auto-revokes permissions from apps
 * that go unused, so this can happen without the user ever deciding to do it.
 *
 * The distinction that matters is between "revoked" and "revoked while something
 * depends on it". Nagging a user who has no app limits configured would be noise,
 * and noise trains people to dismiss the warning that matters.
 */
object UsageAccessPausePolicy {

    enum class State {
        /** Permission granted: limits are actually being measured. */
        MEASURING,

        /** Revoked, but no app limit depends on it yet. */
        REVOKED_WITHOUT_IMPACT,

        /** Revoked while app limits exist: they stopped enforcing, silently. */
        REVOKED_WITH_LIMITS_AT_RISK
    }

    fun evaluate(
        usageAccessGranted: Boolean,
        enabledAppLimitCount: Int
    ): State = when {
        usageAccessGranted -> State.MEASURING
        enabledAppLimitCount > 0 -> State.REVOKED_WITH_LIMITS_AT_RISK
        else -> State.REVOKED_WITHOUT_IMPACT
    }

    /** Only the state where protection is actually broken raises a warning. */
    fun shouldWarn(state: State): Boolean = state == State.REVOKED_WITH_LIMITS_AT_RISK

    fun shouldWarn(
        usageAccessGranted: Boolean,
        enabledAppLimitCount: Int
    ): Boolean = shouldWarn(evaluate(usageAccessGranted, enabledAppLimitCount))

    /**
     * True when an empty usage-stats result means "cannot measure" rather than
     * "nothing used yet".
     *
     * Callers that compute exceeded limits use this to log the difference instead
     * of treating an unmeasurable limit as a satisfied one.
     */
    fun measurementIsUnavailable(
        usageAccessGranted: Boolean,
        enabledAppLimitCount: Int
    ): Boolean = !usageAccessGranted && enabledAppLimitCount > 0
}

