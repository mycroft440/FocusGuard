package com.focusguard.accessibility.website.redirection

/**
 * Explicit same-tab redirect phases. A caller must reacquire the accessibility
 * tree at every REIDENTIFY phase instead of carrying a node across animations.
 */
internal enum class WebsiteRedirectionPhase {
    ACTIVATE_ADDRESS_BAR,
    REIDENTIFY_EDITOR,
    SELECT_ALL,
    SET_TEXT,
    PASTE_FALLBACK,
    REIDENTIFY_SUBMITTER,
    ANNOUNCED_EDITOR_ACTION,
    IME_ENTER,
    CERTIFIED_GO_BUTTON,
    BACK_AND_RETRY,
    VERIFY_DESTINATION,
    REDIRECT_CONFIRMED,
    FAIL_CLOSED
}

internal object WebsiteRedirectionPlan {
    const val MAX_SAME_TAB_ATTEMPTS = 2

    val orderedLayers: List<WebsiteRedirectionPhase> = listOf(
        WebsiteRedirectionPhase.ACTIVATE_ADDRESS_BAR,
        WebsiteRedirectionPhase.REIDENTIFY_EDITOR,
        WebsiteRedirectionPhase.SELECT_ALL,
        WebsiteRedirectionPhase.SET_TEXT,
        WebsiteRedirectionPhase.PASTE_FALLBACK,
        WebsiteRedirectionPhase.REIDENTIFY_SUBMITTER,
        WebsiteRedirectionPhase.ANNOUNCED_EDITOR_ACTION,
        WebsiteRedirectionPhase.IME_ENTER,
        WebsiteRedirectionPhase.CERTIFIED_GO_BUTTON,
        WebsiteRedirectionPhase.BACK_AND_RETRY,
        WebsiteRedirectionPhase.VERIFY_DESTINATION,
        WebsiteRedirectionPhase.REDIRECT_CONFIRMED,
        WebsiteRedirectionPhase.FAIL_CLOSED
    )

    /**
     * Coordinate taps, keyboard-position guessing and ACTION_VIEW intentionally
     * do not belong to this plan. Failure stays in the current blocked surface.
     */
    fun canRetry(attemptNumber: Int): Boolean = attemptNumber < MAX_SAME_TAB_ATTEMPTS
}
