package com.focusguard.service

import com.focusguard.utils.BrowserUiCapabilityPolicy
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FirefoxWebsiteRedirectReliabilityTest {

    @Test
    fun `Firefox display address nodes keep bounded search open for edit-only child`() {
        val packageName = "org.mozilla.firefox"

        assertThat(
            BrowserUiCapabilityPolicy.shouldSearchAddressBarDescendants(
                packageName,
                BrowserUiCapabilityPolicy.FIREFOX_COMPOSE_URL_ENTRY
            )
        ).isTrue()
        assertThat(
            BrowserUiCapabilityPolicy.shouldSearchAddressBarDescendants(
                packageName,
                "$packageName:id/mozac_browser_toolbar_url_view"
            )
        ).isTrue()
        assertThat(
            BrowserUiCapabilityPolicy.shouldSearchAddressBarDescendants(
                packageName,
                BrowserUiCapabilityPolicy.FIREFOX_COMPOSE_SEARCH_ENTRY
            )
        ).isFalse()
        assertThat(
            BrowserUiCapabilityPolicy.shouldSearchAddressBarDescendants(
                "com.android.chrome",
                "com.android.chrome:id/url_bar"
            )
        ).isFalse()
    }

    @Test
    fun `Firefox always enters address edit mode click first while Chrome remains focus first`() {
        assertThat(
            BrowserUiCapabilityPolicy.prefersClickAddressBarActivation("org.mozilla.firefox")
        ).isTrue()
        assertThat(
            BrowserUiCapabilityPolicy.prefersClickAddressBarActivation("org.mozilla.firefox_beta")
        ).isTrue()
        assertThat(
            BrowserUiCapabilityPolicy.prefersClickAddressBarActivation("com.android.chrome")
        ).isFalse()
    }

    @Test
    fun `Firefox gets longer address editor transition window`() {
        val firefoxTimeout = BlockingAccessibilityService.websiteAddressBarActionTimeoutMillis(
            "org.mozilla.firefox"
        )
        val chromeTimeout = BlockingAccessibilityService.websiteAddressBarActionTimeoutMillis(
            "com.android.chrome"
        )

        assertThat(firefoxTimeout).isEqualTo(800L)
        assertThat(chromeTimeout).isEqualTo(360L)
        assertThat(firefoxTimeout).isGreaterThan(chromeTimeout)
    }

    @Test
    fun `recovery never goes back after navigation has already left the editor`() {
        assertThat(
            BlockingAccessibilityService.websiteRestoreDecision(
                safeRedirectStillEditing = true,
                blockedSurfaceStillCurrent = false
            )
        ).isEqualTo(
            BlockingAccessibilityService.WebsiteRestoreDecision.BACK_TO_BLOCKED_SURFACE
        )

        assertThat(
            BlockingAccessibilityService.websiteRestoreDecision(
                safeRedirectStillEditing = false,
                blockedSurfaceStillCurrent = true
            )
        ).isEqualTo(
            BlockingAccessibilityService.WebsiteRestoreDecision.RETRY_CURRENT_BLOCKED_SURFACE
        )

        assertThat(
            BlockingAccessibilityService.websiteRestoreDecision(
                safeRedirectStillEditing = false,
                blockedSurfaceStillCurrent = false
            )
        ).isEqualTo(
            BlockingAccessibilityService.WebsiteRestoreDecision.KEEP_CURRENT_SURFACE
        )
    }

    @Test
    fun `stable current Google surface can confirm a missed Firefox navigation event`() {
        val guard = BlockingAccessibilityService.WebsiteBlockTransitionGuard()
        val transition = guard.tryStart(
            browserPackageName = "org.mozilla.firefox",
            transitionId = 11L,
            destination = BlockingAccessibilityService.WebsiteTransitionDestination.GOOGLE,
            expectedWindowId = 7,
            inspectionGeneration = 3L,
            blockedCandidate = "example.com",
            blockedRules = setOf("example.com"),
            detectionEventUptimeMillis = 100L
        )!!

        assertThat(
            guard.confirmGoogleFromStableCurrentSurface(
                browserPackageName = "org.mozilla.firefox",
                windowId = 7,
                observedAtUptimeMillis = 160L
            )
        ).isFalse()

        assertThat(
            guard.markSanitizationRequested(
                browserPackageName = "org.mozilla.firefox",
                transitionId = transition.id,
                requestedAtUptimeMillis = 150L
            )
        ).isTrue()

        assertThat(
            guard.confirmGoogleFromStableCurrentSurface(
                browserPackageName = "org.mozilla.firefox",
                windowId = 8,
                observedAtUptimeMillis = 160L
            )
        ).isFalse()
        assertThat(
            guard.confirmGoogleFromStableCurrentSurface(
                browserPackageName = "org.mozilla.firefox",
                windowId = 7,
                observedAtUptimeMillis = 149L
            )
        ).isFalse()
        assertThat(
            guard.confirmGoogleFromStableCurrentSurface(
                browserPackageName = "org.mozilla.firefox",
                windowId = 7,
                observedAtUptimeMillis = 160L
            )
        ).isTrue()
        assertThat(transition.safeGoogleConfirmed.isCompleted).isTrue()
    }
}
