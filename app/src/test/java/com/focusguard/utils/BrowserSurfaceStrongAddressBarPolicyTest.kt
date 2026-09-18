package com.focusguard.utils

import android.view.accessibility.AccessibilityNodeInfo
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BrowserSurfaceStrongAddressBarPolicyTest {

    @Test
    fun `firefox exact toolbar resources stay trusted`() {
        val packageName = "org.mozilla.firefox"

        assertThat(
            BrowserSurfaceInspector.isTrustedExactAddressBarResource(
                "$packageName:id/mozac_browser_toolbar_url_view",
                packageName
            )
        ).isTrue()
        assertThat(
            BrowserSurfaceInspector.isTrustedExactAddressBarResource(
                "$packageName:id/mozac_browser_toolbar_edit_url_view",
                packageName
            )
        ).isTrue()
    }

    @Test
    fun `via exact toolbar resources stay trusted`() {
        listOf("mark.via", "mark.via.gp").forEach { packageName ->
            assertThat(
                BrowserSurfaceInspector.isTrustedExactAddressBarResource(
                    "$packageName:id/url_text",
                    packageName
                )
            ).isTrue()
            assertThat(
                BrowserSurfaceInspector.isTrustedExactAddressBarResource(
                    "$packageName:id/address_bar",
                    packageName
                )
            ).isTrue()
        }
    }

    @Test
    fun `custom renderer class and html navigation actions identify web content`() {
        assertThat(
            BrowserSurfaceInspector.isWebContentSignal(
                className = "org.chromium.content.browser.RenderWidgetHostView",
                actionIds = emptySet()
            )
        ).isTrue()
        assertThat(
            BrowserSurfaceInspector.isWebContentSignal(
                className = "com.vendor.CustomSurface",
                actionIds = setOf(AccessibilityNodeInfo.ACTION_NEXT_HTML_ELEMENT)
            )
        ).isTrue()
    }

    @Test
    fun `ordinary native controls are not classified as web content`() {
        assertThat(
            BrowserSurfaceInspector.isWebContentSignal(
                className = "android.widget.LinearLayout",
                actionIds = setOf(AccessibilityNodeInfo.ACTION_CLICK)
            )
        ).isFalse()
    }

    @Test
    fun `page and foreign resources never become trusted browser chrome`() {
        val packageName = "mark.via.gp"

        assertThat(
            BrowserSurfaceInspector.isTrustedExactAddressBarResource(
                "$packageName:id/search_box_text",
                packageName
            )
        ).isFalse()
        assertThat(
            BrowserSurfaceInspector.isTrustedExactAddressBarResource(
                "com.example.page:id/url_text",
                packageName
            )
        ).isFalse()

        val semanticPageField = BrowserUiCapabilityPolicy.Node(
            packageName = packageName,
            windowId = 7,
            viewIdResourceName = "$packageName:id/navigation_input",
            visible = true,
            editable = true,
            focused = true,
            focusable = true,
            uriInput = true,
            text = "https://blocked.example",
            actions = setOf(BrowserUiCapabilityPolicy.NodeAction.SET_TEXT),
            inWebContent = true
        )
        assertThat(
            BrowserUiCapabilityPolicy.isActionableAddressBarNode(
                node = semanticPageField,
                expectedBrowserPackage = packageName,
                expectedWindowId = 7,
                httpsHandlerRecognized = true
            )
        ).isFalse()
    }
}
