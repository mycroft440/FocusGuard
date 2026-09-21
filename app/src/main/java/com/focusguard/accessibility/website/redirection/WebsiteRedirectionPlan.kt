package com.focusguard.accessibility.website.redirection

/** Executable retry policy consumed directly by [WebsiteRedirectionCoordinator]. */
internal object WebsiteRedirectionPlan {
    // One automatic navigation operation per blocking occurrence. A late result may
    // still be observed, but failure never authorizes a second automatic submit.
    const val MAX_SAME_TAB_ATTEMPTS = 1
    const val MAX_SUBMIT_ALTERNATIVES = 1
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
     * Coordinate taps, guessed keyboard positions and ACTION_VIEW are deliberately
     * excluded. Exhausting the single same-tab operation is terminal fail-closed.
     */
    fun canRetry(attemptNumber: Int): Boolean = attemptNumber < MAX_SAME_TAB_ATTEMPTS
}
