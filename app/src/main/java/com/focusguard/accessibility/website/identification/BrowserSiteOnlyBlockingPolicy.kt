package com.focusguard.accessibility.website.identification

/**
 * Final normalization after bounded URL recovery.
 *
 * Reaching this point from a recognized browser does not make a missing URL safe.
 * When the current window was positively classified as web content, that evidence
 * must survive recovery so the caller can apply the opaque-page fail-closed path.
 * Native browser UI remains represented separately as NATIVE_BROWSER_UI and is
 * therefore not converted into opaque web content here.
 */
internal object BrowserSiteOnlyBlockingPolicy {
    fun applyAfterRecovery(
        result: WebsiteIdentificationResult
    ): WebsiteIdentificationResult {
        if (result.status == WebsiteIdentificationStatus.ADDRESS_BAR_OBSERVABLE &&
            result.webContentObserved &&
            result.urlCandidate.isNullOrBlank()
        ) {
            // Finding an address-bar control is not proof of the document URL. Once
            // bounded recovery is exhausted, web content with no usable URL must go
            // through the caller's opaque-page fail-closed path instead of being
            // implicitly allowed merely because the control exists.
            return result.copy(
                status = WebsiteIdentificationStatus.UNOBSERVABLE,
                evidence = result.evidence + WebsiteIdentificationLayer.FAIL_CLOSED
            )
        }
        return result
    }
}
