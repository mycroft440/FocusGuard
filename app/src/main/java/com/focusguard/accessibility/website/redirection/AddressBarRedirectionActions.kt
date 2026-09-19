package com.focusguard.accessibility.website.redirection

import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.accessibility.AccessibilityNodeInfo
import com.focusguard.accessibility.website.compatibility.BrowserCompatibilityStore
import com.focusguard.utils.BrowserSurfaceInspector
import com.focusguard.utils.BrowserUiCapabilityPolicy
import com.focusguard.utils.WebsiteBlocker
import java.util.Locale

/** Browser-owned, fail-closed actions used by the same-tab redirect pipeline. */
internal object AddressBarRedirectionActions {
    private const val MAX_TREE_DEPTH = 24
    private const val MAX_TREE_NODES = 512

    enum class Status { ACCEPTED, NOT_FOUND, AMBIGUOUS, REJECTED }

    data class Result(val status: Status, val selectedViewId: String? = null) {
        val accepted: Boolean get() = status == Status.ACCEPTED
    }

    /**
     * A certified redirect value is enough to keep submission fallbacks eligible
     * after a browser collapses editor focus in response to an accepted no-op.
     * Without that exact-text proof, focus remains mandatory.
     */
    internal fun canUseEditorForCertifiedSubmission(
        editable: Boolean,
        focused: Boolean,
        textCertified: Boolean
    ): Boolean = editable && (focused || textCertified)

    private val certifiedEditorActionLabels = setOf(
        "go", "ir", "navigate", "navegar", "enter", "search", "pesquisar", "done",
        "concluído", "concluido"
    )

    private val certifiedGoButtonEntryNames = setOf(
        "url_bar_go_button",
        "url_go_button",
        "omnibox_go_button",
        "omnibar_go_button",
        "location_bar_go_button",
        "address_bar_go_button",
        "navigation_go_button"
    )

    fun activateAddressBar(
        root: AccessibilityNodeInfo,
        browserPackageName: String,
        expectedWindowId: Int,
        httpsHandlerRecognized: Boolean,
        isCurrent: () -> Boolean
    ): Result {
        if (!isCurrent()) return Result(Status.REJECTED)
        val preferClick = BrowserUiCapabilityPolicy.prefersClickAddressBarActivation(
            browserPackageName
        )
        val primary = if (preferClick) BrowserUiCapabilityPolicy.NodeAction.CLICK
            else BrowserUiCapabilityPolicy.NodeAction.FOCUS
        val secondary = if (preferClick) BrowserUiCapabilityPolicy.NodeAction.FOCUS
            else BrowserUiCapabilityPolicy.NodeAction.CLICK
        val first = legacyAction(
            root, browserPackageName, expectedWindowId, primary,
            httpsHandlerRecognized = httpsHandlerRecognized,
            isCurrent = isCurrent
        )
        if (!isCurrent()) return Result(Status.REJECTED)
        if (first.accepted || first.status == Status.AMBIGUOUS) return first
        return legacyAction(
            root, browserPackageName, expectedWindowId, secondary,
            httpsHandlerRecognized = httpsHandlerRecognized,
            isCurrent = isCurrent
        )
    }

    fun activate(
        root: AccessibilityNodeInfo,
        browserPackageName: String,
        expectedWindowId: Int,
        action: BrowserUiCapabilityPolicy.NodeAction,
        httpsHandlerRecognized: Boolean,
        isCurrent: () -> Boolean
    ): Result = legacyAction(
        root,
        browserPackageName,
        expectedWindowId,
        action,
        httpsHandlerRecognized = httpsHandlerRecognized,
        isCurrent = isCurrent
    )

    /**
     * The historical name is retained because callers use this as their submitter
     * guard. With no text predicate it still requires focus. When an exact redirect
     * predicate is supplied, an unfocused editor is accepted only while it still
     * contains that certified replacement address.
     */
    fun hasFocusedAddressEditor(
        root: AccessibilityNodeInfo,
        browserPackageName: String,
        expectedWindowId: Int,
        httpsHandlerRecognized: Boolean,
        textPredicate: ((String?) -> Boolean)? = null,
        requireUnique: Boolean = true
    ): Boolean {
        val nodes = collectAddressBarNodes(root, browserPackageName, expectedWindowId, httpsHandlerRecognized)
        return try {
            nodes.count { node ->
                val fact = node.toFact()
                val textMatches = textPredicate == null || textPredicate(fact.text)
                val exactTextCertified = textPredicate != null && textMatches
                canUseEditorForCertifiedSubmission(
                    editable = fact.editable,
                    focused = fact.focused,
                    textCertified = exactTextCertified
                ) &&
                    BrowserUiCapabilityPolicy.isActionableAddressBarNode(
                        fact, browserPackageName, expectedWindowId, httpsHandlerRecognized
                    ) && textMatches
            }.let { count -> if (requireUnique) count == 1 else count > 0 }
        } finally { nodes.forEach(::recycleSafely) }
    }

    fun selectAll(
        root: AccessibilityNodeInfo,
        browserPackageName: String,
        expectedWindowId: Int,
        httpsHandlerRecognized: Boolean,
        isCurrent: () -> Boolean
    ): Result = rawEditorAction(
        root,
        browserPackageName,
        expectedWindowId,
        AccessibilityNodeInfo.ACTION_SET_SELECTION,
        httpsHandlerRecognized,
        isCurrent
    ) { selected ->
        Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
            putInt(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,
                selected.text?.length ?: 0
            )
        }
    }

    fun setText(
        root: AccessibilityNodeInfo,
        browserPackageName: String,
        expectedWindowId: Int,
        text: String,
        httpsHandlerRecognized: Boolean,
        isCurrent: () -> Boolean
    ): Result = legacyAction(
        root = root,
        browserPackageName = browserPackageName,
        expectedWindowId = expectedWindowId,
        action = BrowserUiCapabilityPolicy.NodeAction.SET_TEXT,
        arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        },
        httpsHandlerRecognized = httpsHandlerRecognized,
        isCurrent = isCurrent
    )

    fun paste(
        root: AccessibilityNodeInfo,
        browserPackageName: String,
        expectedWindowId: Int,
        httpsHandlerRecognized: Boolean,
        isCurrent: () -> Boolean
    ): Result = rawEditorAction(
        root,
        browserPackageName,
        expectedWindowId,
        AccessibilityNodeInfo.ACTION_PASTE,
        httpsHandlerRecognized,
        isCurrent
    ) { null }

    fun submitImeEnter(
        root: AccessibilityNodeInfo,
        browserPackageName: String,
        expectedWindowId: Int,
        textPredicate: ((String?) -> Boolean)?,
        httpsHandlerRecognized: Boolean,
        isCurrent: () -> Boolean
    ): Result {
        if (!isCurrent() || !BrowserUiCapabilityPolicy.canUseImeEnter(Build.VERSION.SDK_INT)) {
            return Result(Status.NOT_FOUND)
        }
        return legacyAction(
            root = root,
            browserPackageName = browserPackageName,
            expectedWindowId = expectedWindowId,
            action = BrowserUiCapabilityPolicy.NodeAction.IME_ENTER,
            textPredicate = textPredicate,
            httpsHandlerRecognized = httpsHandlerRecognized,
            isCurrent = isCurrent
        )
    }

    fun submitAnnouncedEditorAction(
        root: AccessibilityNodeInfo,
        browserPackageName: String,
        expectedWindowId: Int,
        textPredicate: ((String?) -> Boolean)?,
        httpsHandlerRecognized: Boolean,
        isCurrent: () -> Boolean
    ): Result {
        if (!isCurrent()) return Result(Status.REJECTED)
        val nodes = collectAddressBarNodes(
            root, browserPackageName, expectedWindowId, httpsHandlerRecognized
        )
        return try {
            val preferredEntryName = BrowserCompatibilityStore
                .preferredAddressBarEntryName(browserPackageName)
            val candidates = nodes.mapNotNull { node ->
                val fact = runCatching { node.toFact() }.getOrNull() ?: return@mapNotNull null
                val textMatches = textPredicate == null || textPredicate(node.text?.toString())
                val exactTextCertified = textPredicate != null && textMatches
                val valid = canUseEditorForCertifiedSubmission(
                    editable = node.isEditable,
                    focused = node.isFocused,
                    textCertified = exactTextCertified
                ) &&
                    BrowserUiCapabilityPolicy.isActionableAddressBarNode(
                        fact, browserPackageName, expectedWindowId, httpsHandlerRecognized
                    ) &&
                    textMatches &&
                    node.actionList.count(::isCertifiedEditorAction) == 1
                if (!valid) return@mapNotNull null
                val entryName = BrowserUiCapabilityPolicy.browserOwnedEntryName(
                    browserPackageName,
                    node.viewIdResourceName
                ).orEmpty()
                val cachedBonus = if (entryName == preferredEntryName) 100 else 0
                node to (editorRank(entryName) + cachedBonus)
            }
            if (!isCurrent()) return Result(Status.REJECTED)
            if (candidates.isEmpty()) return Result(Status.NOT_FOUND)
            val bestRank = candidates.maxOf { it.second }
            val winners = candidates.filter { it.second == bestRank }
            if (winners.size != 1) return Result(Status.AMBIGUOUS)
            val selected = winners.single().first
            if (!isCurrent()) return Result(Status.REJECTED)
            val action = selected.actionList.single(::isCertifiedEditorAction)
            val accepted = runCatching { isCurrent() && selected.performAction(action.id) }.getOrDefault(false)
            if (!isCurrent()) return Result(Status.REJECTED, selected.viewIdResourceName)
            Result(
                if (accepted) Status.ACCEPTED else Status.REJECTED,
                selected.viewIdResourceName
            )
        } finally {
            nodes.forEach(::recycleSafely)
        }
    }

    fun clickCertifiedGoButton(
        root: AccessibilityNodeInfo,
        browserPackageName: String,
        expectedWindowId: Int,
        isCurrent: () -> Boolean
    ): Result {
        if (!isCurrent() || !rootMatches(root, browserPackageName, expectedWindowId)) {
            return Result(Status.NOT_FOUND)
        }
        val nodes = certifiedGoButtonEntryNames.flatMap { entry ->
            runCatching {
                root.findAccessibilityNodeInfosByViewId("$browserPackageName:id/$entry")
            }.getOrDefault(emptyList())
        }
        return try {
            if (!isCurrent()) return Result(Status.REJECTED)
            val candidates = nodes.filter { node ->
                node.isVisibleToUser && BrowserSurfaceInspector.isNativeNode(node) &&
                    node.packageName?.toString() == browserPackageName &&
                    node.windowId == expectedWindowId &&
                    node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }
            }
            when (candidates.size) {
                0 -> Result(Status.NOT_FOUND)
                1 -> {
                    val selected = candidates.single()
                    if (!isCurrent()) return Result(Status.REJECTED)
                    val accepted = runCatching {
                        selected.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    }.getOrDefault(false)
                    if (!isCurrent()) return Result(Status.REJECTED, selected.viewIdResourceName)
                    Result(
                        if (accepted) Status.ACCEPTED else Status.REJECTED,
                        selected.viewIdResourceName
                    )
                }
                else -> Result(Status.AMBIGUOUS)
            }
        } finally {
            nodes.forEach(::recycleSafely)
        }
    }

    private fun legacyAction(
        root: AccessibilityNodeInfo,
        browserPackageName: String,
        expectedWindowId: Int,
        action: BrowserUiCapabilityPolicy.NodeAction,
        arguments: Bundle? = null,
        textPredicate: ((String?) -> Boolean)? = null,
        httpsHandlerRecognized: Boolean,
        isCurrent: () -> Boolean
    ): Result {
        if (!isCurrent()) return Result(Status.REJECTED)
        val result = WebsiteBlocker.performUniqueAddressBarAction(
            root = root,
            browserPackageName = browserPackageName,
            expectedWindowId = expectedWindowId,
            requiredAction = action,
            arguments = arguments,
            textPredicate = textPredicate,
            httpsHandlerRecognized = httpsHandlerRecognized,
            allowFallbacks = false,
            isCurrent = isCurrent
        )
        if (!isCurrent()) return Result(Status.REJECTED, result.selectedViewId)
        return Result(
            status = when (result.status) {
                WebsiteBlocker.AddressBarActionStatus.ACCEPTED -> Status.ACCEPTED
                WebsiteBlocker.AddressBarActionStatus.NOT_FOUND -> Status.NOT_FOUND
                WebsiteBlocker.AddressBarActionStatus.AMBIGUOUS -> Status.AMBIGUOUS
                WebsiteBlocker.AddressBarActionStatus.REJECTED -> Status.REJECTED
            },
            selectedViewId = result.selectedViewId
        )
    }

    private fun rawEditorAction(
        root: AccessibilityNodeInfo,
        browserPackageName: String,
        expectedWindowId: Int,
        actionId: Int,
        httpsHandlerRecognized: Boolean,
        isCurrent: () -> Boolean,
        arguments: (AccessibilityNodeInfo) -> Bundle?
    ): Result {
        if (!isCurrent()) return Result(Status.REJECTED)
        val nodes = collectAddressBarNodes(
            root, browserPackageName, expectedWindowId, httpsHandlerRecognized
        )
        return try {
            if (!isCurrent()) return Result(Status.REJECTED)
            val preferredEntryName = BrowserCompatibilityStore
                .preferredAddressBarEntryName(browserPackageName)
            val candidates = nodes.mapNotNull { node ->
                val fact = runCatching { node.toFact() }.getOrNull() ?: return@mapNotNull null
                if (!node.isEditable || !node.isFocused ||
                    node.actionList.none { it.id == actionId } ||
                    !BrowserUiCapabilityPolicy.isActionableAddressBarNode(
                        fact, browserPackageName, expectedWindowId, httpsHandlerRecognized
                    )
                ) {
                    null
                } else {
                    val entryName = BrowserUiCapabilityPolicy.browserOwnedEntryName(
                        browserPackageName,
                        node.viewIdResourceName
                    ).orEmpty()
                    val cachedBonus = if (entryName == preferredEntryName) 100 else 0
                    node to (editorRank(entryName) + cachedBonus)
                }
            }
            if (!isCurrent()) return Result(Status.REJECTED)
            if (candidates.isEmpty()) return Result(Status.NOT_FOUND)
            val bestRank = candidates.maxOf { it.second }
            val winners = candidates.filter { it.second == bestRank }
            if (winners.size != 1) return Result(Status.AMBIGUOUS)
            val selected = winners.single().first
            if (!isCurrent()) return Result(Status.REJECTED)
            val actionArguments = arguments(selected)
            val accepted = runCatching {
                isCurrent() && selected.performAction(actionId, actionArguments)
            }.getOrDefault(false)
            if (!isCurrent()) return Result(Status.REJECTED, selected.viewIdResourceName)
            Result(
                if (accepted) Status.ACCEPTED else Status.REJECTED,
                selected.viewIdResourceName
            )
        } finally {
            nodes.forEach(::recycleSafely)
        }
    }

    private fun collectAddressBarNodes(
        root: AccessibilityNodeInfo,
        browserPackageName: String,
        expectedWindowId: Int,
        httpsHandlerRecognized: Boolean
    ): MutableList<AccessibilityNodeInfo> {
        if (!rootMatches(root, browserPackageName, expectedWindowId)) return mutableListOf()
        val output = mutableListOf<AccessibilityNodeInfo>()
        BrowserCompatibilityStore.prioritizeAddressBarEntryNames(
            packageName = browserPackageName,
            defaults = BrowserUiCapabilityPolicy.strongAddressBarEntryNames
        ).forEach { entry ->
            val matches = runCatching {
                root.findAccessibilityNodeInfosByViewId("$browserPackageName:id/$entry")
            }.getOrDefault(emptyList())
            matches.forEach { candidate ->
                if (!retainDistinctNode(output, candidate)) recycleSafely(candidate)
            }
        }

        // Keep editor/action discovery aligned with read-only URL discovery. Exact
        // package-qualified browser resources may legitimately be nested below a
        // WebView-like container. Bare Firefox Compose tags are collected only
        // after BrowserSurfaceInspector proves they live in native browser chrome.
        collectStrongNodes(
            node = root,
            browserPackageName = browserPackageName,
            expectedWindowId = expectedWindowId,
            output = output,
            depth = 0,
            visited = intArrayOf(0)
        )

        if (httpsHandlerRecognized) {
            collectSemanticNodes(
                root, browserPackageName, expectedWindowId, output, 0, intArrayOf(0)
            )
        }
        return output
    }

    private fun collectStrongNodes(
        node: AccessibilityNodeInfo,
        browserPackageName: String,
        expectedWindowId: Int,
        output: MutableList<AccessibilityNodeInfo>,
        depth: Int,
        visited: IntArray
    ) {
        if (depth > MAX_TREE_DEPTH || visited[0] >= MAX_TREE_NODES ||
            !node.isVisibleToUser || node.windowId != expectedWindowId
        ) return
        visited[0] += 1

        val viewId = node.viewIdResourceName.orEmpty()
        val composeTag = BrowserUiCapabilityPolicy.isFirefoxComposeAddressBarResource(
            viewId,
            browserPackageName
        )
        val nativeEnough = !composeTag || BrowserSurfaceInspector.isNativeNode(node)
        if (nativeEnough && node.packageName?.toString() == browserPackageName &&
            BrowserUiCapabilityPolicy.isStrongAddressBarResource(viewId, browserPackageName)
        ) {
            if (output.none { existing -> existing == node }) {
                @Suppress("DEPRECATION")
                output += AccessibilityNodeInfo.obtain(node)
            }
            if (!BrowserUiCapabilityPolicy.shouldSearchAddressBarDescendants(
                    browserPackageName,
                    viewId
                )
            ) return
        }

        for (index in 0 until node.childCount) {
            if (visited[0] >= MAX_TREE_NODES) break
            val child = node.getChild(index) ?: continue
            try {
                collectStrongNodes(
                    child,
                    browserPackageName,
                    expectedWindowId,
                    output,
                    depth + 1,
                    visited
                )
            } finally {
                recycleSafely(child)
            }
        }
    }

    private fun collectSemanticNodes(
        node: AccessibilityNodeInfo,
        browserPackageName: String,
        expectedWindowId: Int,
        output: MutableList<AccessibilityNodeInfo>,
        depth: Int,
        visited: IntArray
    ) {
        if (depth >= MAX_TREE_DEPTH || visited[0] >= MAX_TREE_NODES ||
            BrowserSurfaceInspector.isWebContainer(node)
        ) return
        for (index in 0 until node.childCount) {
            if (visited[0] >= MAX_TREE_NODES) return
            val child = node.getChild(index) ?: continue
            var retained = false
            try {
                visited[0] += 1
                val fact = child.toFact()
                val semantic = !BrowserUiCapabilityPolicy.isStrongAddressBarResource(
                    fact.viewIdResourceName, browserPackageName
                ) && BrowserUiCapabilityPolicy.isSemanticActionableAddressBarNode(
                    fact, browserPackageName, expectedWindowId, true
                )
                if (semantic) {
                    retained = retainDistinctNode(output, child)
                } else {
                    collectSemanticNodes(
                        child, browserPackageName, expectedWindowId, output,
                        depth + 1, visited
                    )
                }
            } catch (_: RuntimeException) {
                // Stale subtree is not certifiable.
            } finally {
                if (!retained) recycleSafely(child)
            }
        }
    }

    private fun retainDistinctNode(
        output: MutableList<AccessibilityNodeInfo>,
        candidate: AccessibilityNodeInfo
    ): Boolean {
        if (output.any { existing -> existing == candidate }) return false
        output += candidate
        return true
    }

    private fun AccessibilityNodeInfo.toFact(): BrowserUiCapabilityPolicy.Node {
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return BrowserUiCapabilityPolicy.Node(
            packageName = packageName?.toString().orEmpty(),
            windowId = windowId,
            viewIdResourceName = viewIdResourceName.orEmpty(),
            visible = isVisibleToUser,
            editable = isEditable,
            focused = isFocused,
            focusable = isFocusable,
            uriInput = variation == InputType.TYPE_TEXT_VARIATION_URI,
            text = text?.toString(),
            contentDescription = contentDescription?.toString(),
            actions = actionList.mapNotNull { action ->
                when {
                    action.id == AccessibilityNodeInfo.ACTION_FOCUS ->
                        BrowserUiCapabilityPolicy.NodeAction.FOCUS
                    action.id == AccessibilityNodeInfo.ACTION_SET_TEXT ->
                        BrowserUiCapabilityPolicy.NodeAction.SET_TEXT
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                        action.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id ->
                        BrowserUiCapabilityPolicy.NodeAction.IME_ENTER
                    action.id == AccessibilityNodeInfo.ACTION_LONG_CLICK ->
                        BrowserUiCapabilityPolicy.NodeAction.LONG_CLICK
                    action.id == AccessibilityNodeInfo.ACTION_CLICK ->
                        BrowserUiCapabilityPolicy.NodeAction.CLICK
                    else -> null
                }
            }.toSet(),
            hintText = hintText?.toString(),
            inWebContent = !BrowserSurfaceInspector.isNativeNode(this)
        )
    }

    private fun editorRank(entryName: String): Int = when (entryName) {
        "url_bar_edit_text", "location_bar_edit_text", "url_edit_text",
        "omnibarTextInput", "omnibox_text", "inputField",
        "mozac_browser_toolbar_edit_url_view",
        "mozac_browser_toolbar_edit_url",
        "browser_toolbar_edit_url_view",
        BrowserUiCapabilityPolicy.FIREFOX_COMPOSE_SEARCH_ENTRY -> 50
        else -> 30
    }

    private fun isCertifiedEditorAction(action: AccessibilityNodeInfo.AccessibilityAction): Boolean =
        action.label?.toString()?.trim()?.lowercase(Locale.ROOT) in certifiedEditorActionLabels

    private fun rootMatches(
        root: AccessibilityNodeInfo,
        browserPackageName: String,
        expectedWindowId: Int
    ): Boolean = browserPackageName.isNotBlank() && expectedWindowId >= 0 &&
        root.packageName?.toString() == browserPackageName && root.windowId == expectedWindowId

    private fun recycleSafely(node: AccessibilityNodeInfo?) {
        if (node == null) return
        @Suppress("DEPRECATION")
        runCatching { node.recycle() }
    }
}
