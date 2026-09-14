package com.focusguard.utils

/**
 * Accessibility fallback policy for browsers that hide their address bar.
 *
 * A `true` decision means the short observability grace period expired while
 * website protection still requires a readable URL. It does not mean the whole
 * browser must be blocked. The current service deliberately preserves browser
 * availability and stops website-specific observation when no trustworthy URL
 * surface exists.
 */
object WebsiteObservabilityPolicy {
    const val OPAQUE_BROWSER_GRACE_MILLIS = 200L

    fun shouldStopObservingOpaqueBrowser(
        websiteProtectionRequiresObservation: Boolean,
        browserStillForeground: Boolean,
        addressBarObservable: Boolean,
        firstUnobservableElapsed: Long?,
        nowElapsed: Long,
        graceMillis: Long = OPAQUE_BROWSER_GRACE_MILLIS
    ): Boolean {
        if (!websiteProtectionRequiresObservation || !browserStillForeground) return false
        if (addressBarObservable) return false
        val firstSeen = firstUnobservableElapsed ?: return false
        return nowElapsed - firstSeen >= graceMillis.coerceAtLeast(0L)
    }

    /**
     * Compatibility name kept for the existing accessibility-service call sites.
     * Despite the historical name, `true` only signals that opaque-browser URL
     * observation exhausted its grace period; it does not mandate whole-browser blocking.
     */
    fun shouldBlockOpaqueBrowser(
        websiteProtectionRequiresObservation: Boolean,
        browserStillForeground: Boolean,
        addressBarObservable: Boolean,
        firstUnobservableElapsed: Long?,
        nowElapsed: Long,
        graceMillis: Long = OPAQUE_BROWSER_GRACE_MILLIS
    ): Boolean = shouldStopObservingOpaqueBrowser(
        websiteProtectionRequiresObservation = websiteProtectionRequiresObservation,
        browserStillForeground = browserStillForeground,
        addressBarObservable = addressBarObservable,
        firstUnobservableElapsed = firstUnobservableElapsed,
        nowElapsed = nowElapsed,
        graceMillis = graceMillis
    )
}
