package com.focusguard.service

import com.focusguard.utils.BrowserUiCapabilityPolicy
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WebsiteBlockDetectionLatencyTest {

    @Test
    fun `verified browser non editable url display is read only evidence`() {
        val display = BrowserUiCapabilityPolicy.Node(
            packageName = BROWSER_PACKAGE,
            windowId = WINDOW_ID,
            viewIdResourceName = "$BROWSER_PACKAGE:id/current_url",
            visible = true,
            editable = false,
            focused = false,
            focusable = false,
            uriInput = false,
            text = "https://m.youtube.com/watch?v=1",
            actions = emptySet()
        )

        assertThat(
            BrowserUiCapabilityPolicy.isReadOnlyAddressBarNode(
                display,
                BROWSER_PACKAGE,
                WINDOW_ID,
                httpsHandlerRecognized = true
            )
        ).isTrue()
        assertThat(
            BrowserUiCapabilityPolicy.isReadOnlyAddressBarNode(
                display,
                BROWSER_PACKAGE,
                WINDOW_ID,
                httpsHandlerRecognized = false
            )
        ).isFalse()
        assertThat(
            BrowserUiCapabilityPolicy.isActionableAddressBarNode(
                display,
                BROWSER_PACKAGE,
                WINDOW_ID
            )
        ).isFalse()
    }

    @Test
    fun `url named browser node without address shaped value stays rejected`() {
        val unrelated = BrowserUiCapabilityPolicy.Node(
            packageName = BROWSER_PACKAGE,
            windowId = WINDOW_ID,
            viewIdResourceName = "$BROWSER_PACKAGE:id/share_url",
            visible = true,
            editable = false,
            focused = false,
            focusable = false,
            uriInput = false,
            text = "YouTube",
            actions = emptySet()
        )

        assertThat(
            BrowserUiCapabilityPolicy.isReadOnlyAddressBarNode(
                unrelated,
                BROWSER_PACKAGE,
                WINDOW_ID,
                httpsHandlerRecognized = true
            )
        ).isFalse()
    }

    @Test
    fun `youtube domains and aliases are classified on the first known url`() {
        assertThat(
            BlockingAccessibilityService.immediateWebsiteBlockTarget(
                addressText = "https://m.youtube.com/watch?v=abc",
                url = "https://m.youtube.com/watch?v=abc",
                blockedRules = listOf("youtube.com")
            )
        ).isEqualTo("m.youtube.com")

        assertThat(
            BlockingAccessibilityService.immediateWebsiteBlockTarget(
                addressText = "https://youtu.be/abc",
                url = "https://youtu.be/abc",
                blockedRules = listOf("youtube.com")
            )
        ).isEqualTo("youtu.be")

        assertThat(
            BlockingAccessibilityService.immediateWebsiteBlockTarget(
                addressText = "https://www.youtube-nocookie.com/embed/abc",
                url = "https://www.youtube-nocookie.com/embed/abc",
                blockedRules = listOf("youtube.com")
            )
        ).isEqualTo("youtube-nocookie.com")
    }

    private companion object {
        const val BROWSER_PACKAGE = "com.example.browser"
        const val WINDOW_ID = 7
    }
}
