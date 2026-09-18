package com.focusguard.utils

/**
 * Fail-closed policy for browsers that hide their current URL from Accessibility.
 *
 * Website rules cannot be enforced safely when FocusGuard cannot establish which
 * URL is open. A short grace period allows transient toolbar animations to settle;
 * after that, an opaque foreground browser must be blocked while website protection
 * still requires trustworthy URL observation.
 *
 * Browser-owned native chrome is different from an opaque web document: menus and
 * Settings/Preferences screens intentionally have no URL bar and must stay usable.
 */
object WebsiteObservabilityPolicy {
    const val OPAQUE_BROWSER_GRACE_MILLIS = 1_500L

    fun shouldBlockOpaqueBrowser(
        websiteProtectionRequiresObservation: Boolean,
        browserStillForeground: Boolean,
        addressBarObservable: Boolean,
        firstUnobservableElapsed: Long?,
        nowElapsed: Long,
        graceMillis: Long = OPAQUE_BROWSER_GRACE_MILLIS,
        nativeBrowserUiObserved: Boolean = false
    ): Boolean {
        if (!websiteProtectionRequiresObservation || !browserStillForeground) return false
        if (addressBarObservable || nativeBrowserUiObserved) return false
        val firstSeen = firstUnobservableElapsed ?: return false
        return nowElapsed - firstSeen >= graceMillis.coerceAtLeast(0L)
    }

    /** Compatibility alias retained for older callers/tests. */
    fun shouldStopObservingOpaqueBrowser(
        websiteProtectionRequiresObservation: Boolean,
        browserStillForeground: Boolean,
        addressBarObservable: Boolean,
        firstUnobservableElapsed: Long?,
        nowElapsed: Long,
        graceMillis: Long = OPAQUE_BROWSER_GRACE_MILLIS,
        nativeBrowserUiObserved: Boolean = false
    ): Boolean = shouldBlockOpaqueBrowser(
        websiteProtectionRequiresObservation = websiteProtectionRequiresObservation,
        browserStillForeground = browserStillForeground,
        addressBarObservable = addressBarObservable,
        firstUnobservableElapsed = firstUnobservableElapsed,
        nowElapsed = nowElapsed,
        graceMillis = graceMillis,
        nativeBrowserUiObserved = nativeBrowserUiObserved
    )
}
