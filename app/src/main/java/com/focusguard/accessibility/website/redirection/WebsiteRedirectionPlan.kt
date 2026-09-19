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

    /**
     * Website blocking must stay bound to the tab/window that exposed the blocked
     * destination. ACTION_VIEW may open another tab, so it remains a last-resort fallback only after
     * the bounded same-tab rewrite attempts fail. The blocked tab is still protected
     * if the user returns to it.
     */
    const val ALLOW_EXTERNAL_BROWSER_INTENT_FALLBACK = true

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
     * to this same-tab plan. After the bounded same-tab attempts are exhausted the service may request the
     * package-scoped safe-browser fallback. That request is never treated as proof
     * that the original tab was neutralized; destination confirmation remains required.
     */
    fun canRetry(attemptNumber: Int): Boolean = attemptNumber < MAX_SAME_TAB_ATTEMPTS
}
