package com.focusguard.utils

import com.focusguard.utils.BrowserUiCapabilityPolicy.NodeAction
import com.focusguard.utils.BrowserUiCapabilityPolicy.SelectionStatus
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BrowserSemanticActionPolicyTest {

    @Test
    fun `verified HTTPS handler can use unique native URI editor`() {
        val editor = node(
            viewId = "$BROWSER_PACKAGE:id/navigation_input",
            uriInput = true,
            actions = setOf(NodeAction.SET_TEXT, NodeAction.IME_ENTER)
        )

        val setSelection = BrowserUiCapabilityPolicy.resolveUniqueAddressBarNode(
            nodes = listOf(editor),
            expectedBrowserPackage = BROWSER_PACKAGE,
            expectedWindowId = WINDOW_ID,
            requiredAction = NodeAction.SET_TEXT,
            httpsHandlerRecognized = true
        )
        val submitSelection = BrowserUiCapabilityPolicy.resolveUniqueAddressBarNode(
            nodes = listOf(editor),
            expectedBrowserPackage = BROWSER_PACKAGE,
            expectedWindowId = WINDOW_ID,
            requiredAction = NodeAction.IME_ENTER,
            textPredicate = { it == "https://www.google.com" },
            httpsHandlerRecognized = true
        )

        assertThat(setSelection.status).isEqualTo(SelectionStatus.SELECTED)
        assertThat(submitSelection.status).isEqualTo(SelectionStatus.SELECTED)
    }

    @Test
    fun `semantic URI editor needs verified HTTPS handler`() {
        val editor = node(
            viewId = "$BROWSER_PACKAGE:id/navigation_input",
            uriInput = true,
            actions = setOf(NodeAction.SET_TEXT)
        )

        assertThat(
            BrowserUiCapabilityPolicy.resolveUniqueAddressBarNode(
                nodes = listOf(editor),
                expectedBrowserPackage = BROWSER_PACKAGE,
                expectedWindowId = WINDOW_ID,
                requiredAction = NodeAction.SET_TEXT,
                httpsHandlerRecognized = false
            ).status
        ).isEqualTo(SelectionStatus.NOT_FOUND)
    }

    @Test
    fun `page field and foreign resource id stay non actionable`() {
        val pageField = node(
            viewId = "$BROWSER_PACKAGE:id/search_box_text",
            uriInput = false,
            actions = setOf(NodeAction.SET_TEXT)
        )
        val foreignUri = node(
            viewId = "com.example.page:id/navigation_input",
            uriInput = true,
            actions = setOf(NodeAction.SET_TEXT)
        )

        assertThat(
            BrowserUiCapabilityPolicy.resolveUniqueAddressBarNode(
                nodes = listOf(pageField, foreignUri),
                expectedBrowserPackage = BROWSER_PACKAGE,
                expectedWindowId = WINDOW_ID,
                requiredAction = NodeAction.SET_TEXT,
                httpsHandlerRecognized = true
            ).status
        ).isEqualTo(SelectionStatus.NOT_FOUND)
    }

    @Test
    fun `two semantic URI editors fail closed as ambiguous`() {
        val editors = listOf("navigation_input", "location_input").map { entry ->
            node(
                viewId = "$BROWSER_PACKAGE:id/$entry",
                uriInput = true,
                actions = setOf(NodeAction.SET_TEXT)
            )
        }

        assertThat(
            BrowserUiCapabilityPolicy.resolveUniqueAddressBarNode(
                nodes = editors,
                expectedBrowserPackage = BROWSER_PACKAGE,
                expectedWindowId = WINDOW_ID,
                requiredAction = NodeAction.SET_TEXT,
                httpsHandlerRecognized = true
            ).status
        ).isEqualTo(SelectionStatus.AMBIGUOUS)
    }

    private fun node(
        viewId: String,
        uriInput: Boolean,
        actions: Set<NodeAction>
    ) = BrowserUiCapabilityPolicy.Node(
        packageName = BROWSER_PACKAGE,
        windowId = WINDOW_ID,
        viewIdResourceName = viewId,
        visible = true,
        editable = true,
        focused = true,
        focusable = true,
        uriInput = uriInput,
        text = "https://www.google.com",
        actions = actions
    )

    private companion object {
        const val BROWSER_PACKAGE = "com.example.browser"
        const val WINDOW_ID = 7
    }
}
