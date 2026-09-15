package com.focusguard.utils

import com.focusguard.accessibility.website.compatibility.BrowserActivationMethod
import com.focusguard.accessibility.website.compatibility.BrowserCompatibilityStore
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
    private const val DUCKDUCKGO_PACKAGE = "com.duckduckgo.mobile.android"
    private const val DUCKDUCKGO_NATIVE_INPUT_ENTRY = "inputField"

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
        val actions: Set<NodeAction>,
        val hintText: String? = null,
        val inWebContent: Boolean = false
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
        // DuckDuckGo's native input rollout. Authorization remains package- and
        // semantics-gated below because the id itself is intentionally generic.
        DUCKDUCKGO_NATIVE_INPUT_ENTRY,
        // Gecko/Fenix toolbars.
        "mozac_browser_toolbar_url_view",
        "mozac_browser_toolbar_edit_url_view",
        "browser_toolbar_url_view",
        // Samsung Internet and compact browsers such as Via use variants above
        // plus this generic browser-owned id.
        "address_bar",
        "bro_omnibox_address_title",
        "bro_omnibox_address_bar"
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
        DUCKDUCKGO_NATIVE_INPUT_ENTRY,
        "mozac_browser_toolbar_edit_url_view"
    )

    private val clickableDisplayEntryNames: Set<String> = setOf(
        "url_bar",
        "url_text",
        "location_bar",
        "url_field",
        "mozac_browser_toolbar_url_view",
        "browser_toolbar_url_view",
        "address_bar",
        "bro_omnibox_address_title",
        "bro_omnibox_address_bar",
        // DuckDuckGo requires an explicit tap before its editor reliably accepts
        // replacement text. The native input id is authorized only after the
        // package-specific address-mode check in isActionableAddressBarNode().
        "omnibarTextInput",
        DUCKDUCKGO_NATIVE_INPUT_ENTRY
    )

    private val readOnlyAddressBarDescriptions: Set<String> = setOf(
        "address and search bar",
        "endereço e barra de pesquisa",
        "barra de endereço e pesquisa",
        "search or type web address",
        "pesquisar ou digitar endereço web",
        "search or enter address",
        "pesquisar ou inserir endereço",
        "url bar",
        "barra de url",
        "address bar",
        "barra de endereços",
        "barra de endereço",
        "endereço da página"
    )

    private val nativeSettingsLabels: Set<String> = setOf(
        "settings",
        "setting",
        "preferences",
        "preference",
        "configurações",
        "configuracoes"
    )

    fun isStrongAddressBarResource(
        viewIdResourceName: String,
        expectedBrowserPackage: String
    ): Boolean {
        if (expectedBrowserPackage.isBlank()) return false
        val prefix = "$expectedBrowserPackage:id/"
        if (!viewIdResourceName.startsWith(prefix)) return false
        val entryName = viewIdResourceName.substring(prefix.length)
        if (entryName == DUCKDUCKGO_NATIVE_INPUT_ENTRY) {
            return expectedBrowserPackage == DUCKDUCKGO_PACKAGE
        }
        return entryName in strongAddressBarEntryNames
    }

    /**
     * Detects browser-owned native chrome that intentionally has no address bar,
     * such as Chromium/Brave's app menu and Settings/Preferences screens.
     *
     * This is deliberately narrower than a generic text match: evidence must be
     * owned by the browser package and either use a settings/preference resource
     * id or expose an exact settings label from a menu/title-like native view.
     */
    internal fun isNativeBrowserUiNode(
        node: Node,
        expectedBrowserPackage: String,
        expectedWindowId: Int
    ): Boolean {
        if (node.inWebContent || !node.visible || node.editable ||
            expectedBrowserPackage.isBlank() ||
            node.packageName != expectedBrowserPackage ||
            node.windowId != expectedWindowId
        ) return false

        val prefix = "$expectedBrowserPackage:id/"
        val viewId = node.viewIdResourceName
        if (!viewId.startsWith(prefix) || viewId.length <= prefix.length) return false
        val entryName = viewId.substring(prefix.length).lowercase(Locale.ROOT)

        if (entryName.contains("settings") || entryName.contains("preference")) return true

        val nativeLabel = sequenceOf(node.text, node.contentDescription, node.hintText)
            .mapNotNull { it?.trim()?.lowercase(Locale.ROOT)?.takeIf(String::isNotEmpty) }
            .any { it in nativeSettingsLabels }
        if (!nativeLabel) return false

        return entryName.contains("menu") ||
            entryName == "title" ||
            entryName.endsWith("_title") ||
            entryName.contains("toolbar")
    }

    fun isReadOnlyAddressBarNode(
        node: Node,
        expectedBrowserPackage: String,
        expectedWindowId: Int,
        httpsHandlerRecognized: Boolean
    ): Boolean {
        if (node.inWebContent || !node.visible ||
            node.packageName != expectedBrowserPackage ||
            node.windowId != expectedWindowId
        ) return false

        // Native browser menus/settings are a legitimate no-address-bar surface.
        // Never pretend this node is an address bar or authorize automation
        // against it. Whole-window classification is handled separately.
        if (isNativeBrowserUiNode(node, expectedBrowserPackage, expectedWindowId)) {
            return false
        }

        if (isStrongAddressBarResource(node.viewIdResourceName, expectedBrowserPackage)) {
            return !isDuckDuckGoNativeInput(node) || isDuckDuckGoAddressInput(node)
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

        if (hasAddressBarLabel(node)) return true

        val normalizedEntryName = entryName.lowercase(Locale.ROOT)
        val idLooksNative = normalizedEntryName.let { id ->
            id.contains("url") || id.contains("uri") || id.contains("omnibox") ||
                id.contains("address") || id.contains("location_bar")
        }
        val editableAddressField = node.editable && browserOwnedResource &&
            (node.uriInput || idLooksNative)
        if (editableAddressField) return true

        val idLooksReadOnlyDisplay = normalizedEntryName == "current_url" ||
            normalizedEntryName == "current_uri" ||
            normalizedEntryName == "url_display" ||
            normalizedEntryName == "uri_display" ||
            normalizedEntryName.startsWith("current_url_") ||
            normalizedEntryName.startsWith("current_uri_") ||
            normalizedEntryName.startsWith("address_bar_") ||
            normalizedEntryName.startsWith("location_bar_") ||
            normalizedEntryName.startsWith("omnibox_")
        val displayLooksAddressLike = node.text.orEmpty().trim().let { value ->
            value.isNotEmpty() &&
                value.none(Char::isWhitespace) &&
                (value.contains("://") || value.contains('.'))
        }
        return browserOwnedResource && idLooksReadOnlyDisplay && displayLooksAddressLike
    }

    fun isSemanticActionableAddressBarNode(
        node: Node,
        expectedBrowserPackage: String,
        expectedWindowId: Int,
        httpsHandlerRecognized: Boolean
    ): Boolean {
        if (node.inWebContent || !httpsHandlerRecognized ||
            !node.visible || !node.editable || !node.uriInput ||
            node.packageName != expectedBrowserPackage ||
            node.windowId != expectedWindowId
        ) return false

        val prefix = "$expectedBrowserPackage:id/"
        val viewId = node.viewIdResourceName
        if (!viewId.startsWith(prefix) || viewId.length <= prefix.length) return false

        val entryName = viewId.substring(prefix.length).lowercase(Locale.ROOT)
        return entryName.contains("url") || entryName.contains("uri") ||
            entryName.contains("omnibox") || entryName.contains("address") ||
            entryName.contains("location") || entryName.contains("navigation") ||
            hasAddressBarLabel(node)
    }

    fun isActionableAddressBarNode(
        node: Node,
        expectedBrowserPackage: String,
        expectedWindowId: Int,
        httpsHandlerRecognized: Boolean = false
    ): Boolean {
        if (node.inWebContent || !node.visible ||
            node.packageName != expectedBrowserPackage ||
            node.windowId != expectedWindowId
        ) return false

        if (isStrongAddressBarResource(node.viewIdResourceName, expectedBrowserPackage)) {
            return !isDuckDuckGoNativeInput(node) || isDuckDuckGoAddressInput(node)
        }
        return isSemanticActionableAddressBarNode(
            node = node,
            expectedBrowserPackage = expectedBrowserPackage,
            expectedWindowId = expectedWindowId,
            httpsHandlerRecognized = httpsHandlerRecognized
        )
    }

    fun resolveUniqueAddressBarNode(
        nodes: List<Node>,
        expectedBrowserPackage: String,
        expectedWindowId: Int,
        requiredAction: NodeAction,
        textPredicate: ((String?) -> Boolean)? = null,
        httpsHandlerRecognized: Boolean = false
    ): Selection {
        val preferredEntryName = BrowserCompatibilityStore
            .preferredAddressBarEntryName(expectedBrowserPackage)
        val ranked = nodes.indices.mapNotNull { index ->
            val node = nodes[index]
            if (!isActionableAddressBarNode(
                    node,
                    expectedBrowserPackage,
                    expectedWindowId,
                    httpsHandlerRecognized = httpsHandlerRecognized
                ) || !supports(node, requiredAction) ||
                (textPredicate != null && !textPredicate(node.text))
            ) {
                null
            } else {
                val cachedPreferenceBonus = if (
                    preferredEntryName != null && entryName(node) == preferredEntryName
                ) 100 else 0
                index to (actionRank(node, requiredAction) + cachedPreferenceBonus)
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
        httpsHandlerRecognized: Boolean,
        requiredAction: NodeAction? = null,
        expectedText: String? = null
    ): Int? {
        val action = requiredAction ?: return nodes.indices.filter { index ->
            isActionableAddressBarNode(
                nodes[index],
                expectedBrowserPackage,
                expectedWindowId,
                httpsHandlerRecognized = httpsHandlerRecognized
            )
        }.singleOrNull()
        return resolveUniqueAddressBarNode(
            nodes = nodes,
            expectedBrowserPackage = expectedBrowserPackage,
            expectedWindowId = expectedWindowId,
            requiredAction = action,
            textPredicate = expectedText?.let { expected ->
                { actual: String? -> actual?.trim() == expected.trim() }
            },
            httpsHandlerRecognized = httpsHandlerRecognized
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

    fun prefersClickAddressBarActivation(expectedBrowserPackage: String): Boolean = when (
        BrowserCompatibilityStore.preferredActivationMethod(expectedBrowserPackage)
    ) {
        BrowserActivationMethod.CLICK -> true
        BrowserActivationMethod.FOCUS -> false
        null -> expectedBrowserPackage == DUCKDUCKGO_PACKAGE ||
            expectedBrowserPackage in setOf(
                "com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary",
                "com.sec.android.app.sbrowser", "com.sec.android.app.sbrowser.beta",
                "mark.via", "mark.via.gp", "com.yandex.browser", "com.yandex.browser.beta",
                "com.yandex.browser.alpha", "com.yandex.browser.lite"
            )
    }

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
        NodeAction.CLICK -> NodeAction.CLICK in node.actions
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

    private fun hasAddressBarLabel(node: Node): Boolean =
        sequenceOf(node.contentDescription, node.hintText).filterNotNull().any { raw ->
            val value = raw.trim().lowercase(Locale.ROOT)
            readOnlyAddressBarDescriptions.any { label ->
                value == label || value.startsWith("$label,") ||
                    value.startsWith("$label.") || value.startsWith("$label ")
            }
        }

    private fun isDuckDuckGoNativeInput(node: Node): Boolean =
        node.packageName == DUCKDUCKGO_PACKAGE &&
            entryName(node) == DUCKDUCKGO_NATIVE_INPUT_ENTRY

    private fun isDuckDuckGoAddressInput(node: Node): Boolean {
        if (!isDuckDuckGoNativeInput(node) || !node.editable) return false
        if (node.uriInput) return true
        val labels = sequenceOf(node.contentDescription, node.hintText)
            .mapNotNull { it?.trim()?.lowercase(Locale.ROOT)?.takeIf(String::isNotEmpty) }
        return labels.any { value ->
            readOnlyAddressBarDescriptions.any { label ->
                value == label ||
                    value.startsWith("$label,") ||
                    value.startsWith("$label.") ||
                    value.startsWith("$label ")
            }
        }
    }

    private fun entryName(node: Node): String =
        node.viewIdResourceName.substringAfter(":id/", "")
}
