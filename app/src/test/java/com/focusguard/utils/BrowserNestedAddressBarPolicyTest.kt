package com.focusguard.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BrowserNestedAddressBarPolicyTest {

    @Test
    fun `strong firefox and via bars remain readable below web containers`() {
        listOf(
            BrowserUiCapabilityPolicy.Node(
                packageName = "org.mozilla.firefox",
                windowId = 3,
                viewIdResourceName =
                    "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
                visible = true,
                editable = false,
                focused = false,
                focusable = true,
                uriInput = false,
                text = "https://blocked.example",
                actions = setOf(BrowserUiCapabilityPolicy.NodeAction.CLICK),
                inWebContent = true
            ),
            BrowserUiCapabilityPolicy.Node(
                packageName = "mark.via.gp",
                windowId = 3,
                viewIdResourceName = "mark.via.gp:id/url_text",
                visible = true,
                editable = false,
                focused = false,
                focusable = true,
                uriInput = false,
                text = "blocked.example",
                actions = setOf(BrowserUiCapabilityPolicy.NodeAction.CLICK),
                inWebContent = true
            )
        ).forEach { node ->
            assertThat(
                BrowserUiCapabilityPolicy.isReadOnlyAddressBarNode(
                    node = node,
                    expectedBrowserPackage = node.packageName,
                    expectedWindowId = 3,
                    httpsHandlerRecognized = false
                )
            ).isTrue()
        }
    }

    @Test
    fun `uc and phoenix browser owned address resources can identify nested urls`() {
        listOf(
            BrowserUiCapabilityPolicy.Node(
                packageName = "com.UCMobile.intl",
                windowId = 8,
                viewIdResourceName = "com.UCMobile.intl:id/current_url_text",
                visible = true,
                editable = false,
                focused = false,
                focusable = false,
                uriInput = false,
                text = "https://blocked.example/path",
                actions = emptySet(),
                inWebContent = true
            ),
            BrowserUiCapabilityPolicy.Node(
                packageName = "com.transsion.phoenix",
                windowId = 8,
                viewIdResourceName = "com.transsion.phoenix:id/address_input",
                visible = true,
                editable = true,
                focused = true,
                focusable = true,
                uriInput = true,
                text = "blocked.example",
                actions = setOf(BrowserUiCapabilityPolicy.NodeAction.SET_TEXT),
                inWebContent = true
            )
        ).forEach { node ->
            assertThat(
                BrowserUiCapabilityPolicy.isReadOnlyAddressBarNode(
                    node = node,
                    expectedBrowserPackage = node.packageName,
                    expectedWindowId = 8,
                    httpsHandlerRecognized = true
                )
            ).isTrue()
        }
    }

    @Test
    fun `nested semantic fields remain read only and cannot gain browser actions`() {
        val phoenixAddress = BrowserUiCapabilityPolicy.Node(
            packageName = "com.transsion.phoenix",
            windowId = 9,
            viewIdResourceName = "com.transsion.phoenix:id/address_input",
            visible = true,
            editable = true,
            focused = true,
            focusable = true,
            uriInput = true,
            text = "blocked.example",
            actions = setOf(BrowserUiCapabilityPolicy.NodeAction.SET_TEXT),
            inWebContent = true
        )
        assertThat(
            BrowserUiCapabilityPolicy.isReadOnlyAddressBarNode(
                phoenixAddress,
                "com.transsion.phoenix",
                9,
                httpsHandlerRecognized = true
            )
        ).isTrue()
        assertThat(
            BrowserUiCapabilityPolicy.isActionableAddressBarNode(
                phoenixAddress,
                "com.transsion.phoenix",
                9,
                httpsHandlerRecognized = true
            )
        ).isFalse()
    }

    @Test
    fun `html and generic navigation fields inside web content stay rejected`() {
        val pageField = BrowserUiCapabilityPolicy.Node(
            packageName = "mark.via.gp",
            windowId = 11,
            viewIdResourceName = "mark.via.gp:id/navigation_input",
            visible = true,
            editable = true,
            focused = true,
            focusable = true,
            uriInput = true,
            text = "https://blocked.example",
            actions = setOf(BrowserUiCapabilityPolicy.NodeAction.SET_TEXT),
            inWebContent = true
        )
        val foreignResource = pageField.copy(
            viewIdResourceName = "com.example.page:id/current_url_text"
        )

        assertThat(
            BrowserUiCapabilityPolicy.isReadOnlyAddressBarNode(
                pageField,
                "mark.via.gp",
                11,
                httpsHandlerRecognized = true
            )
        ).isFalse()
        assertThat(
            BrowserUiCapabilityPolicy.isReadOnlyAddressBarNode(
                foreignResource,
                "mark.via.gp",
                11,
                httpsHandlerRecognized = true
            )
        ).isFalse()
    }
}
