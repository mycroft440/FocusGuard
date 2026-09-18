package com.focusguard.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BrowserNativeUiPolicyTest {
    @Test
    fun `brave settings row is native browser ui`() {
        val node = BrowserUiCapabilityPolicy.Node(
            packageName = "com.brave.browser",
            windowId = 7,
            viewIdResourceName = "com.brave.browser:id/menu_item_text",
            visible = true,
            editable = false,
            focused = false,
            focusable = false,
            uriInput = false,
            text = "Configurações",
            actions = setOf(BrowserUiCapabilityPolicy.NodeAction.CLICK)
        )

        assertThat(
            BrowserUiCapabilityPolicy.isNativeBrowserUiNode(
                node = node,
                expectedBrowserPackage = "com.brave.browser",
                expectedWindowId = 7
            )
        ).isTrue()
    }

    @Test
    fun `settings activity resource is native browser ui without localized label`() {
        val node = BrowserUiCapabilityPolicy.Node(
            packageName = "com.brave.browser",
            windowId = 11,
            viewIdResourceName = "com.brave.browser:id/settings_container",
            visible = true,
            editable = false,
            focused = false,
            focusable = false,
            uriInput = false,
            text = null,
            actions = emptySet()
        )

        assertThat(
            BrowserUiCapabilityPolicy.isNativeBrowserUiNode(
                node = node,
                expectedBrowserPackage = "com.brave.browser",
                expectedWindowId = 11
            )
        ).isTrue()
    }

    @Test
    fun `ordinary web content is not native browser ui`() {
        val node = BrowserUiCapabilityPolicy.Node(
            packageName = "com.brave.browser",
            windowId = 4,
            viewIdResourceName = "com.brave.browser:id/content_view",
            visible = true,
            editable = false,
            focused = false,
            focusable = false,
            uriInput = false,
            text = "Configurações",
            actions = emptySet()
        )

        assertThat(
            BrowserUiCapabilityPolicy.isNativeBrowserUiNode(
                node = node,
                expectedBrowserPackage = "com.brave.browser",
                expectedWindowId = 4
            )
        ).isFalse()
    }
}
