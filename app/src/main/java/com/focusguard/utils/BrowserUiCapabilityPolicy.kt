package com.focusguard.utils

import java.util.Locale

/**
 * Pure, fail-closed classification for browser accessibility surfaces.
 *
 * Read-only observation deliberately accepts more evidence than UI automation.
 * A node can help identify/read an address bar without ever being authorized for
 * focus, text replacement, submission or tab closing.
 */
internal object BrowserUiCapabilityPolicy {

    private const val IME_ENTER_MIN_API = 30

    enum class NodeAction {
        FOCUS,
        SET_TEXT,
        IME_ENTER,
        LONG_CLICK,
        CLICK
    }

    enum class SelectionStatus {
        SELECTED,
        NOT_FOUND,
        AMBIGUOUS
    }

    data class Node(
        val packageName: String,
        val windowId: Int,
        val viewIdResourceName: String,
        val visible: Boolean,
        val editable: Boolean,
        val focused: Boolean,
        val focusable: Boolean,
        val uriInput: Boolean,
        val text: String?,
        val contentDescription: String? = null,
        val actions: Set<NodeAction>
    )

    data class Selection(
        val status: SelectionStatus,
        val index: Int? = null
    )

    val strongAddressBarEntryNames: Set<String> = linkedSetOf(
        // Chromium and Chromium forks.
        "url_bar",
        "url_bar_edit_text",
        "url_text",
        "location_bar_edit_text",
        "location_bar",
        "url_field",
        "url_edit_text",
        "omnibarTextInput",
        "omnibox_text",
        // Gecko/Fenix toolbars.
        "mozac_browser_toolbar_url_view",
        "mozac_browser_toolbar_edit_url_view",
        "browser_toolbar_url_view",
        // Samsung Internet and compact browsers such as Via use variants above
        // plus this generic browser-owned id.
        "address_bar"
    )

    val weakReadOnlyAddressBarEntryNames: Set<String> = setOf(
        "search_box_text",
        "line_1"
    )

    private val editorEntryNames: Set<String> = setOf(
        "url_bar_edit_text",
        "location_bar_edit_text",
        "url_edit_text",
        "omnibarTextInput",
        "omnibox_text",
        "mozac_browser_toolbar_edit_url_view"
    )

    private val clickableDisplayEntryNames: Set<String> = setOf(
        "url_bar",
        "url_text",
        "location_bar",
        "url_field",
        "mozac_browser_toolbar_url_view",
        "browser_toolbar_url_view",
        "address_bar"
    )

    private val readOnlyAddressBarDescriptions: Set<String> = setOf(
        "address and search bar",
        "endereço e barra de pesquisa",
        "barra de endereço e pesquisa",
        "search or type web address",
        "pesquisar ou digitar endereço web",
        "url bar",
        "barra de url",
        "address bar",
        "barra de endereços",
        "barra de endereço",
        "endereço da página"
    )

    fun isStrongAddressBarResource(
        viewIdResourceName: String,
        expectedBrowserPackage: String
    ): Boolean {
        if (expectedBrowserPackage.isBlank()) return false
        val prefix = "$expectedBrowserPackage:id/"
        if (!viewIdResourceName.startsWith(prefix)) return false
        return viewIdResourceName.substring(prefix.length) in strongAddressBarEntryNames
    }

    fun isReadOnlyAddressBarNode(
        node: Node,
        expectedBrowserPackage: String,
        expectedWindowId: Int,
        httpsHandlerRecognized: Boolean
    ): Boolean {
        if (!node.visible ||
            node.packageName != expectedBrowserPackage ||
            node.windowId != expectedWindowId
        ) return false

        if (isStrongAddressBarResource(node.viewIdResourceName, expectedBrowserPackage)) {
            return true
        }
        if (!httpsHandlerRecognized) return false

        val prefix = "$expectedBrowserPackage:id/"
        val browserOwnedResource = node.viewIdResourceName.startsWith(prefix) &&
            node.viewIdResourceName.length > prefix.length
        val entryName = node.viewIdResourceName.substringAfter(":id/", "")
        if (browserOwnedResource &&
            entryName in weakReadOnlyAddressBarEntryNames &&
            node.editable
        ) return true

        val description = node.contentDescription.orEmpty().trim().lowercase(Locale.ROOT)
        if (readOnlyAddressBarDescriptions.any { label ->
                description == label ||
                    description.startsWith("$label,") ||
                    description.startsWith("$label.") ||
                    description.startsWith("$label ")
            }
        ) return true

        val idLooksNative = entryName.lowercase(Locale.ROOT).let { id ->
            id.contains("url") || id.contains("uri") || id.contains("omnibox") ||
                id.contains("address") || id.contains("location_bar")
        }
        return node.editable && browserOwnedResource &&
            (node.uriInput || idLooksNative)
    }

    fun isActionableAddressBarNode(
        node: Node,
        expectedBrowserPackage: String,
        expectedWindowId: Int
    ): Boolean = node.visible &&
        node.packageName == expectedBrowserPackage &&
        node.windowId == expectedWindowId &&
        isStrongAddressBarResource(node.viewIdResourceName, expectedBrowserPackage)

    fun resolveUniqueAddressBarNode(
        nodes: List<Node>,
        expectedBrowserPackage: String,
        expectedWindowId: Int,
        requiredAction: NodeAction,
        textPredicate: ((String?) -> Boolean)? = null
    ): Selection {
        val ranked = nodes.indices.mapNotNull { index ->
            val node = nodes[index]
            if (!isActionableAddressBarNode(
                    node,
                    expectedBrowserPackage,
                    expectedWindowId
                ) || !supports(node, requiredAction) ||
                (textPredicate != null && !textPredicate(node.text))
            ) {
                null
            } else {
                index to actionRank(node, requiredAction)
            }
        }
        if (ranked.isEmpty()) return Selection(SelectionStatus.NOT_FOUND)
        val bestRank = ranked.maxOf { it.second }
        val winners = ranked.filter { it.second == bestRank }
        return if (winners.size == 1) {
            Selection(SelectionStatus.SELECTED, winners.single().first)
        } else {
            Selection(SelectionStatus.AMBIGUOUS)
        }
    }

    fun selectUniqueAddressBarNode(
        nodes: List<Node>,
        expectedBrowserPackage: String,
        expectedWindowId: Int,
        @Suppress("UNUSED_PARAMETER") httpsHandlerRecognized: Boolean,
        requiredAction: NodeAction? = null,
        expectedText: String? = null
    ): Int? {
        val action = requiredAction ?: return nodes.indices.filter { index ->
            isActionableAddressBarNode(
                nodes[index],
                expectedBrowserPackage,
                expectedWindowId
            )
        }.singleOrNull()
        return resolveUniqueAddressBarNode(
            nodes = nodes,
            expectedBrowserPackage = expectedBrowserPackage,
            expectedWindowId = expectedWindowId,
            requiredAction = action,
            textPredicate = expectedText?.let { expected ->
                { actual: String? -> actual?.trim() == expected.trim() }
            }
        ).index
    }

    fun selectUniqueExactBrowserNode(
        nodes: List<Node>,
        expectedBrowserPackage: String,
        expectedWindowId: Int,
        expectedEntryName: String,
        requiredAction: NodeAction
    ): Int? {
        val expectedId = "$expectedBrowserPackage:id/$expectedEntryName"
        return nodes.indices.filter { index ->
            val node = nodes[index]
            node.visible &&
                node.packageName == expectedBrowserPackage &&
                node.windowId == expectedWindowId &&
                node.viewIdResourceName == expectedId &&
                requiredAction in node.actions
        }.singleOrNull()
    }

    fun canUseImeEnter(apiLevel: Int): Boolean = apiLevel >= IME_ENTER_MIN_API

    fun mayRewriteBlockedTabAfterCloseAttempt(
        closeActionAccepted: Boolean,
        originalBlockedSurfaceStillCurrent: Boolean
    ): Boolean = !closeActionAccepted && originalBlockedSurfaceStillCurrent

    fun isFreshExpectedSurface(
        expectedBrowserPackage: String,
        expectedWindowId: Int,
        activePackageName: String,
        activeWindowId: Int,
        phaseStartedAtUptimeMillis: Long,
        latestWindowTransitionEventUptimeMillis: Long
    ): Boolean = expectedBrowserPackage.isNotBlank() &&
        activePackageName == expectedBrowserPackage &&
        activeWindowId == expectedWindowId &&
        phaseStartedAtUptimeMillis > 0L &&
        latestWindowTransitionEventUptimeMillis <= phaseStartedAtUptimeMillis

    private fun supports(node: Node, action: NodeAction): Boolean = when (action) {
        NodeAction.FOCUS -> node.focused ||
            (node.focusable && NodeAction.FOCUS in node.actions)
        NodeAction.SET_TEXT -> node.editable && node.focused &&
            NodeAction.SET_TEXT in node.actions
        NodeAction.IME_ENTER -> node.editable && node.focused &&
            NodeAction.IME_ENTER in node.actions
        NodeAction.LONG_CLICK -> NodeAction.LONG_CLICK in node.actions
        NodeAction.CLICK -> entryName(node) in clickableDisplayEntryNames &&
            NodeAction.CLICK in node.actions
    }

    private fun actionRank(node: Node, action: NodeAction): Int = when (action) {
        NodeAction.FOCUS -> when {
            node.focused -> 50
            entryName(node) in clickableDisplayEntryNames -> 40
            entryName(node) in editorEntryNames -> 30
            else -> 20
        }
        NodeAction.SET_TEXT,
        NodeAction.IME_ENTER -> if (entryName(node) in editorEntryNames) 50 else 30
        NodeAction.CLICK -> 40
        NodeAction.LONG_CLICK -> 30
    }

    private fun entryName(node: Node): String =
        node.viewIdResourceName.substringAfter(":id/", "")
}
