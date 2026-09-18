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
    IME_ENTER,
    ANNOUNCED_EDITOR_ACTION,
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
        WebsiteRedirectionPhase.IME_ENTER,
        WebsiteRedirectionPhase.ANNOUNCED_EDITOR_ACTION,
        WebsiteRedirectionPhase.CERTIFIED_GO_BUTTON,
        WebsiteRedirectionPhase.BACK_AND_RETRY,
        WebsiteRedirectionPhase.VERIFY_DESTINATION,
        WebsiteRedirectionPhase.REDIRECT_CONFIRMED,
        WebsiteRedirectionPhase.FAIL_CLOSED
    )

    /**
     * Coordinate taps and keyboard-position guessing intentionally do not belong
     * to this same-tab plan. The service may request an ACTION_VIEW safe-browser
     * fallback after these phases fail, but launching that intent is only a
     * navigation request: it is not evidence that the original blocked tab was
     * replaced or otherwise neutralized. The blocking curtain must remain guarded
     * by post-navigation/original-surface confirmation.
     */
    fun canRetry(attemptNumber: Int): Boolean = attemptNumber < MAX_SAME_TAB_ATTEMPTS
}
