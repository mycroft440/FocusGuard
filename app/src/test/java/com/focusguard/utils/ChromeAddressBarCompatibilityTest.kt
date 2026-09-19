package com.focusguard.utils

import com.focusguard.utils.BrowserUiCapabilityPolicy.NodeAction
import com.focusguard.utils.BrowserUiCapabilityPolicy.SelectionStatus
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ChromeAddressBarCompatibilityTest {

    @Test
    fun `Chrome family always prefers focus before click for omnibox activation`() {
        listOf(
            "com.android.chrome",
            "com.chrome.beta",
            "com.chrome.dev",
            "com.chrome.canary"
        ).forEach { packageName ->
            assertThat(BrowserUiCapabilityPolicy.isChromePackage(packageName)).isTrue()
            assertThat(
                BrowserUiCapabilityPolicy.prefersClickAddressBarActivation(packageName)
            ).isFalse()
        }
    }

    @Test
    fun `Chrome url bar remains a strong actionable native address control`() {
        val packageName = "com.android.chrome"
        val urlBar = BrowserUiCapabilityPolicy.Node(
            packageName = packageName,
            windowId = WINDOW_ID,
            viewIdResourceName = "$packageName:id/url_bar",
            visible = true,
            editable = true,
            focused = false,
            focusable = true,
            uriInput = true,
            text = "blocked.example",
            actions = setOf(NodeAction.FOCUS, NodeAction.CLICK)
        )

        assertThat(
            BrowserUiCapabilityPolicy.isStrongAddressBarResource(
                urlBar.viewIdResourceName,
                packageName
            )
        ).isTrue()
        assertThat(
            BrowserUiCapabilityPolicy.resolveUniqueAddressBarNode(
                nodes = listOf(urlBar),
                expectedBrowserPackage = packageName,
                expectedWindowId = WINDOW_ID,
                requiredAction = NodeAction.FOCUS,
                httpsHandlerRecognized = true
            ).status
        ).isEqualTo(SelectionStatus.SELECTED)
    }

    @Test
    fun `Chrome family classification does not leak to Chromium forks`() {
        assertThat(BrowserUiCapabilityPolicy.isChromePackage("com.brave.browser")).isFalse()
        assertThat(BrowserUiCapabilityPolicy.isChromePackage("com.microsoft.emmx")).isFalse()
    }

    private companion object {
        const val WINDOW_ID = 19
    }
}
