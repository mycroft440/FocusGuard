package com.focusguard.accessibility.website.identification

/**
 * Browsers with known Accessibility support must stay launchable when their URL
 * is temporarily unobservable. A missing URL is not evidence that a blocked site
 * is open; only a positively identified blocked candidate may start blocking and
 * redirection for these packages.
 *
 * Unknown/unsupported browsers keep the existing opaque-browser fail-closed
 * behavior. This policy is intentionally independent from Device Owner policies.
 */
internal object BrowserSiteOnlyBlockingPolicy {
    private val siteOnlyBrowserPackages: Set<String> = setOf(
        "com.android.chrome",
        "com.chrome.beta",
        "com.chrome.dev",
        "com.chrome.canary",
        "mark.via",
        "mark.via.gp",
        "com.yandex.browser",
        "com.yandex.browser.beta",
        "com.yandex.browser.alpha",
        "com.yandex.browser.lite",
        "com.brave.browser"
    )

    fun keepsBrowserAccessibleWhenUrlIsUnobservable(packageName: String): Boolean =
        packageName in siteOnlyBrowserPackages

    /**
     * Preserve all positive identification evidence. Only the final opaque-page
     * evidence is downgraded for known supported browsers, preventing the caller
     * from converting "URL unavailable" into a whole-browser block.
     */
    fun applyAfterRecovery(
        packageName: String,
        result: WebsiteIdentificationResult
    ): WebsiteIdentificationResult {
        if (!keepsBrowserAccessibleWhenUrlIsUnobservable(packageName) ||
            result.status != WebsiteIdentificationStatus.UNOBSERVABLE ||
            !result.webContentObserved ||
            result.addressBarObservable ||
            result.bestCandidate != null
        ) {
            return result
        }
        return result.copy(webContentObserved = false)
    }
}
