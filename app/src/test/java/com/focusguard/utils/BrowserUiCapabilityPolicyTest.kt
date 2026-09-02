package com.focusguard.utils

import com.focusguard.utils.BrowserUiCapabilityPolicy.NodeAction
import com.focusguard.utils.BrowserUiCapabilityPolicy.SelectionStatus
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BrowserUiCapabilityPolicyTest {

    @Test
    fun `arbitrary Chromium package can expose exact close capability`() {
        val packageName = "org.example.chromium.fork"
        val nodes = listOf(
            node(
                packageName = packageName,
                viewId = "$packageName:id/tab_switcher_button",
                actions = setOf(NodeAction.LONG_CLICK)
            ),
            node(
                packageName = packageName,
                viewId = "$packageName:id/close_tab",
                actions = setOf(NodeAction.CLICK)
            )
        )

        assertThat(
            BrowserUiCapabilityPolicy.selectUniqueExactBrowserNode(
                nodes,
                packageName,
                WINDOW_ID,
                "tab_switcher_button",
                NodeAction.LONG_CLICK
            )
        ).isEqualTo(0)
        assertThat(
            BrowserUiCapabilityPolicy.selectUniqueExactBrowserNode(
                nodes,
                packageName,
                WINDOW_ID,
                "close_tab",
                NodeAction.CLICK
            )
        ).isEqualTo(1)
    }

    @Test
    fun `strong editor ids cover Chromium Gecko Samsung and Via families`() {
        val ids = listOf(
            "org.example.chromium:id/url_bar_edit_text",
            "org.mozilla.firefox:id/mozac_browser_toolbar_edit_url_view",
            "com.sec.android.app.sbrowser:id/location_bar_edit_text",
            "mark.via.gp:id/url_text"
        )

        ids.forEach { viewId ->
            val packageName = viewId.substringBefore(":id/")
            val selection = BrowserUiCapabilityPolicy.resolveUniqueAddressBarNode(
                nodes = listOf(
                    node(
                        packageName = packageName,
                        viewId = viewId,
                        editable = true,
                        focused = true,
                        actions = setOf(NodeAction.SET_TEXT)
                    )
                ),
                expectedBrowserPackage = packageName,
                expectedWindowId = WINDOW_ID,
                requiredAction = NodeAction.SET_TEXT
            )
            assertThat(selection.status).isEqualTo(SelectionStatus.SELECTED)
            assertThat(selection.index).isEqualTo(0)
        }
    }

    @Test
    fun `semantic URI and weak ids are read only capabilities`() {
        val semantic = node(
            viewId = "$BROWSER_PACKAGE:id/current_uri",
            editable = true,
            focused = true,
            uriInput = true,
            actions = setOf(NodeAction.SET_TEXT)
        )
        val weakGoogleField = node(
            viewId = "$BROWSER_PACKAGE:id/search_box_text",
            editable = true,
            focused = true,
            actions = setOf(NodeAction.SET_TEXT)
        )

        listOf(semantic, weakGoogleField).forEach { candidate ->
            assertThat(
                BrowserUiCapabilityPolicy.isReadOnlyAddressBarNode(
                    candidate,
                    BROWSER_PACKAGE,
                    WINDOW_ID,
                    httpsHandlerRecognized = true
                )
            ).isTrue()
            assertThat(
                BrowserUiCapabilityPolicy.isActionableAddressBarNode(
                    candidate,
                    BROWSER_PACKAGE,
                    WINDOW_ID
                )
            ).isFalse()
        }
        assertThat(
            BrowserUiCapabilityPolicy.isReadOnlyAddressBarNode(
                semantic,
                BROWSER_PACKAGE,
                WINDOW_ID,
                httpsHandlerRecognized = false
            )
        ).isFalse()
    }

    @Test
    fun `localized description observes handler but never authorizes action`() {
        val described = node(
            viewId = "",
            contentDescription = "Barra de endereço e pesquisa, example.com",
            actions = emptySet()
        )

        assertThat(
            BrowserUiCapabilityPolicy.isReadOnlyAddressBarNode(
                described,
                BROWSER_PACKAGE,
                WINDOW_ID,
                httpsHandlerRecognized = true
            )
        ).isTrue()
        assertThat(
            BrowserUiCapabilityPolicy.isActionableAddressBarNode(
                described,
                BROWSER_PACKAGE,
                WINDOW_ID
            )
        ).isFalse()
    }

    @Test
    fun `two equally ranked editors are ambiguous`() {
        val candidates = listOf("url_bar_edit_text", "location_bar_edit_text").map { entry ->
            node(
                viewId = "$BROWSER_PACKAGE:id/$entry",
                editable = true,
                focused = true,
                actions = setOf(NodeAction.SET_TEXT)
            )
        }

        assertThat(
            BrowserUiCapabilityPolicy.resolveUniqueAddressBarNode(
                candidates,
                BROWSER_PACKAGE,
                WINDOW_ID,
                NodeAction.SET_TEXT
            ).status
        ).isEqualTo(SelectionStatus.AMBIGUOUS)
    }

    @Test
    fun `focused child outranks focusable parent in one logical bar`() {
        val parent = node(
            viewId = "$BROWSER_PACKAGE:id/url_bar",
            focusable = true,
            actions = setOf(NodeAction.FOCUS)
        )
        val focusedChild = node(
            viewId = "$BROWSER_PACKAGE:id/url_bar_edit_text",
            editable = true,
            focused = true,
            actions = setOf(NodeAction.SET_TEXT)
        )

        val selection = BrowserUiCapabilityPolicy.resolveUniqueAddressBarNode(
            listOf(parent, focusedChild),
            BROWSER_PACKAGE,
            WINDOW_ID,
            NodeAction.FOCUS
        )
        assertThat(selection.status).isEqualTo(SelectionStatus.SELECTED)
        assertThat(selection.index).isEqualTo(1)
    }

    @Test
    fun `Gecko display click can transition to a unique editor`() {
        val display = node(
            viewId = "$BROWSER_PACKAGE:id/mozac_browser_toolbar_url_view",
            actions = setOf(NodeAction.CLICK)
        )
        val click = BrowserUiCapabilityPolicy.resolveUniqueAddressBarNode(
            listOf(display),
            BROWSER_PACKAGE,
            WINDOW_ID,
            NodeAction.CLICK
        )
        assertThat(click.status).isEqualTo(SelectionStatus.SELECTED)

        val editor = node(
            viewId = "$BROWSER_PACKAGE:id/mozac_browser_toolbar_edit_url_view",
            editable = true,
            focused = true,
            actions = setOf(NodeAction.SET_TEXT, NodeAction.IME_ENTER)
        )
        val set = BrowserUiCapabilityPolicy.resolveUniqueAddressBarNode(
            listOf(editor),
            BROWSER_PACKAGE,
            WINDOW_ID,
            NodeAction.SET_TEXT
        )
        assertThat(set.status).isEqualTo(SelectionStatus.SELECTED)
    }

    @Test
    fun `ordinary page fields are never actionable`() {
        val pageSearch = node(
            viewId = "$BROWSER_PACKAGE:id/search_box_text",
            editable = true,
            focused = true,
            actions = setOf(NodeAction.SET_TEXT)
        )
        val foreignUri = node(
            viewId = "com.example.page:id/url",
            editable = true,
            focused = true,
            uriInput = true,
            actions = setOf(NodeAction.SET_TEXT)
        )

        assertThat(
            BrowserUiCapabilityPolicy.resolveUniqueAddressBarNode(
                listOf(pageSearch, foreignUri),
                BROWSER_PACKAGE,
                WINDOW_ID,
                NodeAction.SET_TEXT
            ).status
        ).isEqualTo(SelectionStatus.NOT_FOUND)
    }

    @Test
    fun `IME enter requires advertised action focused editor and safe text`() {
        val editor = node(
            viewId = "$BROWSER_PACKAGE:id/url_bar_edit_text",
            editable = true,
            focused = true,
            text = "www.google.com",
            actions = setOf(NodeAction.IME_ENTER)
        )
        val safe: (String?) -> Boolean = { it == "www.google.com" }

        assertThat(
            BrowserUiCapabilityPolicy.resolveUniqueAddressBarNode(
                listOf(editor),
                BROWSER_PACKAGE,
                WINDOW_ID,
                NodeAction.IME_ENTER,
                safe
            ).status
        ).isEqualTo(SelectionStatus.SELECTED)
        assertThat(
            BrowserUiCapabilityPolicy.resolveUniqueAddressBarNode(
                listOf(editor.copy(actions = setOf(NodeAction.SET_TEXT))),
                BROWSER_PACKAGE,
                WINDOW_ID,
                NodeAction.IME_ENTER,
                safe
            ).status
        ).isEqualTo(SelectionStatus.NOT_FOUND)
        assertThat(
            BrowserUiCapabilityPolicy.resolveUniqueAddressBarNode(
                listOf(editor.copy(text = "google.com.evil")),
                BROWSER_PACKAGE,
                WINDOW_ID,
                NodeAction.IME_ENTER,
                safe
            ).status
        ).isEqualTo(SelectionStatus.NOT_FOUND)
    }

    @Test
    fun `exact lookup is not limited by 256 preceding page nodes`() {
        val pageNodes = (0 until 300).map { index ->
            node(
                viewId = "$BROWSER_PACKAGE:id/page_$index",
                actions = emptySet()
            )
        }
        val editor = node(
            viewId = "$BROWSER_PACKAGE:id/url_bar_edit_text",
            editable = true,
            focused = true,
            actions = setOf(NodeAction.SET_TEXT)
        )
        val selection = BrowserUiCapabilityPolicy.resolveUniqueAddressBarNode(
            pageNodes + editor,
            BROWSER_PACKAGE,
            WINDOW_ID,
            NodeAction.SET_TEXT
        )
        assertThat(selection.index).isEqualTo(300)
    }

    @Test
    fun `API below 30 has no certifiable IME submit`() {
        assertThat(BrowserUiCapabilityPolicy.canUseImeEnter(26)).isFalse()
        assertThat(BrowserUiCapabilityPolicy.canUseImeEnter(29)).isFalse()
        assertThat(BrowserUiCapabilityPolicy.canUseImeEnter(30)).isTrue()
    }

    @Test
    fun `accepted close never authorizes rewrite of a surviving tab`() {
        assertThat(
            BrowserUiCapabilityPolicy.mayRewriteBlockedTabAfterCloseAttempt(
                closeActionAccepted = true,
                originalBlockedSurfaceStillCurrent = true
            )
        ).isFalse()
        assertThat(
            BrowserUiCapabilityPolicy.mayRewriteBlockedTabAfterCloseAttempt(
                closeActionAccepted = false,
                originalBlockedSurfaceStillCurrent = true
            )
        ).isTrue()
        assertThat(
            BrowserUiCapabilityPolicy.mayRewriteBlockedTabAfterCloseAttempt(
                closeActionAccepted = false,
                originalBlockedSurfaceStillCurrent = false
            )
        ).isFalse()
    }

    @Test
    fun `new window transition wrong package and wrong window reject action`() {
        fun allowed(
            activePackage: String = BROWSER_PACKAGE,
            activeWindow: Int = WINDOW_ID,
            latestWindowTransition: Long = 100L
        ) = BrowserUiCapabilityPolicy.isFreshExpectedSurface(
            expectedBrowserPackage = BROWSER_PACKAGE,
            expectedWindowId = WINDOW_ID,
            activePackageName = activePackage,
            activeWindowId = activeWindow,
            phaseStartedAtUptimeMillis = 100L,
            latestWindowTransitionEventUptimeMillis = latestWindowTransition
        )

        assertThat(allowed()).isTrue()
        assertThat(allowed(activePackage = "com.other.browser")).isFalse()
        assertThat(allowed(activeWindow = WINDOW_ID + 1)).isFalse()
        assertThat(allowed(latestWindowTransition = 101L)).isFalse()
    }

    private fun node(
        packageName: String = BROWSER_PACKAGE,
        viewId: String,
        editable: Boolean = false,
        focused: Boolean = false,
        focusable: Boolean = false,
        uriInput: Boolean = false,
        text: String? = null,
        contentDescription: String? = null,
        actions: Set<NodeAction>
    ) = BrowserUiCapabilityPolicy.Node(
        packageName = packageName,
        windowId = WINDOW_ID,
        viewIdResourceName = viewId,
        visible = true,
        editable = editable,
        focused = focused,
        focusable = focusable,
        uriInput = uriInput,
        text = text,
        contentDescription = contentDescription,
        actions = actions
    )

    private companion object {
        const val BROWSER_PACKAGE = "com.example.browser"
        const val WINDOW_ID = 7
    }
}
