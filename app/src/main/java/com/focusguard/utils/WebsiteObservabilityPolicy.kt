package com.focusguard.utils

/** Fail-closed policy for browsers that hide their address bar from accessibility. */
object WebsiteObservabilityPolicy {
    const val OPAQUE_BROWSER_GRACE_MILLIS = 800L

    fun shouldBlockOpaqueBrowser(
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
}
