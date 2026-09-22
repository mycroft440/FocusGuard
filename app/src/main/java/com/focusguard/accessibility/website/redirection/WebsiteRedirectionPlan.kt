package com.focusguard.accessibility.website.redirection

/** Executable retry policy consumed directly by [WebsiteRedirectionCoordinator]. */
internal object WebsiteRedirectionPlan {
    const val MAX_SAME_TAB_ATTEMPTS = 2
    const val MAX_SUBMIT_ALTERNATIVES = 3

    /**
     * The 601f93e release tried a package-scoped safe ACTION_VIEW only after the
     * certified same-tab path was exhausted. The opaque curtain stays up until the
     * destination is positively observed, so this request is never treated as proof.
     */
    const val ALLOW_EXTERNAL_BROWSER_INTENT_FALLBACK = true

    const val DESTINATION_CONFIRM_TIMEOUT_MILLIS = 2_000L

    private const val PREPARE_AND_RECOVERY_BUDGET_PER_ATTEMPT_MILLIS = 4_000L
    private const val POST_REDIRECT_DESTINATION_BUDGET_MILLIS = DESTINATION_CONFIRM_TIMEOUT_MILLIS
    private const val CURTAIN_FAILSAFE_MARGIN_MILLIS = 1_000L

    const val CURTAIN_FAILSAFE_MILLIS =
        MAX_SAME_TAB_ATTEMPTS * (
            MAX_SUBMIT_ALTERNATIVES * DESTINATION_CONFIRM_TIMEOUT_MILLIS +
                PREPARE_AND_RECOVERY_BUDGET_PER_ATTEMPT_MILLIS
            ) + POST_REDIRECT_DESTINATION_BUDGET_MILLIS + CURTAIN_FAILSAFE_MARGIN_MILLIS

    /**
     * Coordinate taps and guessed keyboard positions remain excluded. Once this
     * bounded same-tab budget is exhausted, the legacy package-scoped browser
     * intent fallback gets one chance; failure or unconfirmed navigation remains
     * terminal fail-closed.
     */
    fun canRetry(attemptNumber: Int): Boolean = attemptNumber < MAX_SAME_TAB_ATTEMPTS
}
