package com.focusguard.accessibility.website.identification

/**
 * A browser that reached URL recovery has already been recognized by the
 * Accessibility service. It must stay launchable when recovery ends without a
 * candidate: a missing URL is not evidence that a blocked website is open.
 *
 * Positive identification evidence is never downgraded. This policy also does
 * not affect the separate fail-closed path used after a blocked target was
 * positively identified and sanitization/redirection could not be certified.
 */
internal object BrowserSiteOnlyBlockingPolicy {
    /**
     * Only the final opaque-page evidence is downgraded. The caller reached this
     * point from a recognized browser surface, so package-specific allowlists are
     * deliberately unnecessary and cannot drift from browser recognition.
     */
    fun applyAfterRecovery(
        result: WebsiteIdentificationResult
    ): WebsiteIdentificationResult {
        if (result.status != WebsiteIdentificationStatus.UNOBSERVABLE ||
            !result.webContentObserved ||
            result.addressBarObservable ||
            result.bestCandidate != null
        ) {
            return result
        }
        return result.copy(webContentObserved = false)
    }
}
