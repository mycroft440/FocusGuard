package com.focusguard.accessibility.website.identification

import com.focusguard.utils.WebsiteObservabilityPolicy

/**
 * Final identification layer: an opaque foreground browser is never interpreted
 * as safe merely because Accessibility could not prove its URL.
 */
internal object WebsiteIdentificationFailClosedPolicy {
    fun shouldBlock(
        websiteProtectionRequiresObservation: Boolean,
        browserStillForeground: Boolean,
        identification: WebsiteIdentificationResult,
        firstUnobservableElapsed: Long?,
        nowElapsed: Long,
        graceMillis: Long = WebsiteObservabilityPolicy.OPAQUE_BROWSER_GRACE_MILLIS
    ): Boolean = WebsiteObservabilityPolicy.shouldBlockOpaqueBrowser(
        websiteProtectionRequiresObservation = websiteProtectionRequiresObservation,
        browserStillForeground = browserStillForeground,
        addressBarObservable = identification.addressBarObservable,
        firstUnobservableElapsed = firstUnobservableElapsed,
        nowElapsed = nowElapsed,
        graceMillis = graceMillis
    )
}
