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
    ): WebsiteIdentificationResult = result
}
