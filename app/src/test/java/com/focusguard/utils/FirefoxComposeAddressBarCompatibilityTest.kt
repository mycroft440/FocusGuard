package com.focusguard.utils

import com.focusguard.utils.BrowserUiCapabilityPolicy.NodeAction
import com.focusguard.utils.BrowserUiCapabilityPolicy.SelectionStatus
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FirefoxComposeAddressBarCompatibilityTest {

    @Test
    fun `Firefox bare URL tag is trusted only on native Firefox surface`() {
        val display = node(
            viewId = BrowserUiCapabilityPolicy.FIREFOX_COMPOSE_URL_ENTRY,
            text = "example.com",
            actions = setOf(NodeAction.CLICK)
        )

        assertThat(
            BrowserUiCapabilityPolicy.isReadOnlyAddressBarNode(
                display,
                FIREFOX_PACKAGE,
                WINDOW_ID,
                httpsHandlerRecognized = true
            )
        ).isTrue()
        assertThat(
            BrowserUiCapabilityPolicy.isActionableAddressBarNode(
                display,
                FIREFOX_PACKAGE,
                WINDOW_ID,
                httpsHandlerRecognized = true
            )
        ).isTrue()

        assertThat(
            BrowserUiCapabilityPolicy.isReadOnlyAddressBarNode(
                display.copy(inWebContent = true),
                FIREFOX_PACKAGE,
                WINDOW_ID,
                httpsHandlerRecognized = true
            )
        ).isFalse()
        assertThat(
            BrowserUiCapabilityPolicy.isActionableAddressBarNode(
                display.copy(inWebContent = true),
                FIREFOX_PACKAGE,
                WINDOW_ID,
                httpsHandlerRecognized = true
            )
        ).isFalse()
    }

    @Test
    fun `Firefox Compose tags cannot be spoofed by another browser package`() {
        val foreign = node(
            packageName = "com.example.browser",
            viewId = BrowserUiCapabilityPolicy.FIREFOX_COMPOSE_URL_ENTRY,
            text = "blocked.example",
            actions = setOf(NodeAction.CLICK)
        )

        assertThat(
            BrowserUiCapabilityPolicy.isStrongAddressBarResource(
                foreign.viewIdResourceName,
                foreign.packageName
            )
        ).isFalse()
        assertThat(
            BrowserUiCapabilityPolicy.browserOwnedEntryName(
                foreign.packageName,
                foreign.viewIdResourceName
            )
        ).isNull()
    }

    @Test
    fun `Firefox Compose editor participates in write and submit selection`() {
        val editor = node(
            viewId = BrowserUiCapabilityPolicy.FIREFOX_COMPOSE_SEARCH_ENTRY,
            editable = true,
            focused = true,
            uriInput = true,
            text = "www.google.com",
            actions = setOf(NodeAction.SET_TEXT, NodeAction.IME_ENTER)
        )

        val write = BrowserUiCapabilityPolicy.resolveUniqueAddressBarNode(
            nodes = listOf(editor),
            expectedBrowserPackage = FIREFOX_PACKAGE,
            expectedWindowId = WINDOW_ID,
            requiredAction = NodeAction.SET_TEXT,
            httpsHandlerRecognized = true
        )
        val submit = BrowserUiCapabilityPolicy.resolveUniqueAddressBarNode(
            nodes = listOf(editor),
            expectedBrowserPackage = FIREFOX_PACKAGE,
            expectedWindowId = WINDOW_ID,
            requiredAction = NodeAction.IME_ENTER,
            textPredicate = { it == "www.google.com" },
            httpsHandlerRecognized = true
        )

        assertThat(write.status).isEqualTo(SelectionStatus.SELECTED)
        assertThat(submit.status).isEqualTo(SelectionStatus.SELECTED)
        assertThat(
            BrowserUiCapabilityPolicy.isEditorEntryName(
                BrowserUiCapabilityPolicy.FIREFOX_COMPOSE_SEARCH_ENTRY
            )
        ).isTrue()
        assertThat(
            BrowserUiCapabilityPolicy.isStableUrlEntryName(
                BrowserUiCapabilityPolicy.FIREFOX_COMPOSE_SEARCH_ENTRY
            )
        ).isFalse()
        assertThat(
            BrowserUiCapabilityPolicy.isStableUrlEntryName(
                BrowserUiCapabilityPolicy.FIREFOX_COMPOSE_URL_ENTRY
            )
        ).isTrue()
    }

    @Test
    fun `mixed Firefox content description yields URL only after component trust`() {
        val description = "Example title, https://blocked.example/path?q=1, Search or enter address"

        assertThat(WebsiteBlocker.extractUrlCandidate(description))
            .isEqualTo("https://blocked.example/path?q=1")
        assertThat(WebsiteBlocker.extractUrlCandidate("Example title, Search or enter address"))
            .isNull()

        val trusted = node(
            viewId = BrowserUiCapabilityPolicy.FIREFOX_COMPOSE_URL_ENTRY,
            contentDescription = description,
            actions = setOf(NodeAction.CLICK)
        )
        assertThat(
            BrowserUiCapabilityPolicy.isReadOnlyAddressBarNode(
                trusted,
                FIREFOX_PACKAGE,
                WINDOW_ID,
                httpsHandlerRecognized = true
            )
        ).isTrue()
        assertThat(
            BrowserUiCapabilityPolicy.isReadOnlyAddressBarNode(
                trusted.copy(inWebContent = true),
                FIREFOX_PACKAGE,
                WINDOW_ID,
                httpsHandlerRecognized = true
            )
        ).isFalse()
    }

    @Test
    fun `Firefox defaults to click activation and bare tags are cacheable`() {
        assertThat(BrowserUiCapabilityPolicy.prefersClickAddressBarActivation(FIREFOX_PACKAGE))
            .isTrue()
        assertThat(
            BrowserUiCapabilityPolicy.browserOwnedEntryName(
                FIREFOX_PACKAGE,
                BrowserUiCapabilityPolicy.FIREFOX_COMPOSE_URL_ENTRY
            )
        ).isEqualTo(BrowserUiCapabilityPolicy.FIREFOX_COMPOSE_URL_ENTRY)
        assertThat(
            BrowserUiCapabilityPolicy.browserOwnedEntryName(
                FIREFOX_PACKAGE,
                "UNRELATED_BARE_TAG"
            )
        ).isNull()
    }

    @Test
    fun `bare Firefox tag never bypasses native ancestor validation`() {
        assertThat(
            BrowserSurfaceInspector.isTrustedExactAddressBarResource(
                BrowserUiCapabilityPolicy.FIREFOX_COMPOSE_URL_ENTRY,
                FIREFOX_PACKAGE
            )
        ).isFalse()
        assertThat(
            BrowserSurfaceInspector.isTrustedExactAddressBarResource(
                "$FIREFOX_PACKAGE:id/mozac_browser_toolbar_url_view",
                FIREFOX_PACKAGE
            )
        ).isTrue()
    }

    private fun node(
        packageName: String = FIREFOX_PACKAGE,
        viewId: String,
        editable: Boolean = false,
        focused: Boolean = false,
        focusable: Boolean = false,
        uriInput: Boolean = false,
        text: String? = null,
        contentDescription: String? = null,
        actions: Set<NodeAction>,
        visible: Boolean = true
    ) = BrowserUiCapabilityPolicy.Node(
        packageName = packageName,
        windowId = WINDOW_ID,
        viewIdResourceName = viewId,
        visible = visible,
        editable = editable,
        focused = focused,
        focusable = focusable,
        uriInput = uriInput,
        text = text,
        contentDescription = contentDescription,
        actions = actions
    )

    private companion object {
        const val FIREFOX_PACKAGE = "org.mozilla.firefox"
        const val WINDOW_ID = 11
    }
}
