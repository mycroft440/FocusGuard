package com.focusguard.accessibility.website.redirection

/** Executable retry policy consumed directly by [WebsiteRedirectionCoordinator]. */
internal object WebsiteRedirectionPlan {
    const val MAX_SAME_TAB_ATTEMPTS = 2

    /**
     * Coordinate taps, guessed keyboard positions and ACTION_VIEW are deliberately
     * excluded. Exhausting this bounded same-tab budget is terminal fail-closed.
     */
    fun canRetry(attemptNumber: Int): Boolean = attemptNumber < MAX_SAME_TAB_ATTEMPTS
}
