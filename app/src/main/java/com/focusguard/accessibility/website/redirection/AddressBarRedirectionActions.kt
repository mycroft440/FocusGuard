package com.focusguard.accessibility.website.redirection

import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.accessibility.AccessibilityNodeInfo
import com.focusguard.utils.BrowserUiCapabilityPolicy
import java.util.Locale

/**
 * Browser-owned address-bar actions used by same-tab website redirection.
 *
 * Every public function operates on the root supplied for that single phase.
 * Callers must discard that root after the action and reacquire a fresh tree
 * before the next phase.
 */
internal object AddressBarRedirectionActions {
    private const val MAX_TREE_DEPTH = 12
    private const val MAX_TREE_NODES = 256

    enum class Status {
        ACCEPTED,
        NOT_FOUND,
        AMBIGUOUS,
        REJECTED
    }

    data class Result(
        val status: Status,
        val selectedViewId: String? = null
    ) {
        val accepted: Boolean
            get() = status == Status.ACCEPTED
    }

    private val certifiedEditorActionLabels = setOf(
        "go",
        "ir",
        "navigate",
        "navegar",
        "enter",
        "search",
        "pesquisar",
        "done",
        "concluído",
        "concluido"
    )

    /** Deliberately narrow: generic page search/submit ids are not certified. */
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
        httpsHandlerRecognized: Boolean
    ): Result {
        val preferClick = BrowserUiCapabilityPolicy.prefersClickAddressBarActivation(
            browserPackageName
        )
        val primary = if (preferClick) {
            BrowserUiCapabilityPolicy.NodeAction.CLICK
        } else {
            BrowserUiCapabilityPolicy.NodeAction.FOCUS
        }
        val secondary = if (preferClick) {
            BrowserUiCapabilityPolicy.NodeAction.FOCUS
        } else {
            BrowserUiCapabilityPolicy.NodeAction.CLICK
        }
        val first = performAddressBarAction(
            root,
            browserPackageName,
            expectedWindowId,
            primary,
            httpsHandlerRecognized = httpsHandlerRecognized
        )
        if (first.accepted || first.status == Status.AMBIGUOUS) return first
        return performAddressBarAction(
            root,
            browserPackageName,
            expectedWindowId,
            secondary,
            httpsHandlerRecognized = httpsHandlerRecognized
        )
    }

    fun selectAll(
        root: AccessibilityNodeInfo,
        browserPackageName: String,
        expectedWindowId: Int,
        httpsHandlerRecognized: Boolean
    ): Result {
        val nodes = collectActionableAddressBarNodes(
            root,
            browserPackageName,
            expectedWindowId,
            httpsHandlerRecognized
        )
        return try {
            val facts = nodes.toFactsOrReject() ?: return Result(Status.REJECTED)
            val selection = BrowserUiCapabilityPolicy.resolveUniqueAddressBarNode(
                nodes = facts,
                expectedBrowserPackage = browserPackageName,
                expectedWindowId = expectedWindowId,
                requiredAction = BrowserUiCapabilityPolicy.NodeAction.SET_SELECTION,
                httpsHandlerRecognized = httpsHandlerRecognized
            )
            val selectedIndex = selection.index ?: return selection.toResult()
            val selected = nodes[selectedIndex]
            val length = selected.text?.length ?: 0
            val arguments = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, length)
            }
            Result(
                status = if (runCatching {
                        selected.performAction(
                            AccessibilityNodeInfo.ACTION_SET_SELECTION,
                            arguments
                        )
                    }.getOrDefault(false)
                ) Status.ACCEPTED else Status.REJECTED,
                selectedViewId = selected.viewIdResourceName
            )
        } finally {
            nodes.forEach(::recycleSafely)
        }
    }

    fun setText(
        root: AccessibilityNodeInfo,
        browserPackageName: String,
        expectedWindowId: Int,
        text: String,
        httpsHandlerRecognized: Boolean
    ): Result {
        val arguments = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text
            )
        }
        return performAddressBarAction(
            root = root,
            browserPackageName = browserPackageName,
            expectedWindowId = expectedWindowId,
            requiredAction = BrowserUiCapabilityPolicy.NodeAction.SET_TEXT,
            arguments = arguments,
            httpsHandlerRecognized = httpsHandlerRecognized
        )
    }

    /** Clipboard contents must be prepared/restored by [ClipboardPasteFallback]. */
    fun paste(
        root: AccessibilityNodeInfo,
        browserPackageName: String,
        expectedWindowId: Int,
        httpsHandlerRecognized: Boolean
    ): Result = performAddressBarAction(
        root = root,
        browserPackageName = browserPackageName,
        expectedWindowId = expectedWindowId,
        requiredAction = BrowserUiCapabilityPolicy.NodeAction.PASTE,
        httpsHandlerRecognized = httpsHandlerRecognized
    )

    fun submitImeEnter(
        root: AccessibilityNodeInfo,
        browserPackageName: String,
        expectedWindowId: Int,
        textPredicate: ((String?) -> Boolean)? = null,
        httpsHandlerRecognized: Boolean
    ): Result {
        if (!BrowserUiCapabilityPolicy.canUseImeEnter(Build.VERSION.SDK_INT)) {
            return Result(Status.NOT_FOUND)
        }
        return performAddressBarAction(
            root = root,
            browserPackageName = browserPackageName,
            expectedWindowId = expectedWindowId,
            requiredAction = BrowserUiCapabilityPolicy.NodeAction.IME_ENTER,
            textPredicate = textPredicate,
            httpsHandlerRecognized = httpsHandlerRecognized
        )
    }

    /**
     * Executes a semantic editor action only when one focused address editor and
     * one certified action label are present. No keyboard geometry is inspected.
     */
    fun submitAnnouncedEditorAction(
        root: AccessibilityNodeInfo,
        browserPackageName: String,
        expectedWindowId: Int,
        textPredicate: ((String?) -> Boolean)? = null,
        httpsHandlerRecognized: Boolean
    ): Result {
        val nodes = collectActionableAddressBarNodes(
            root,
            browserPackageName,
            expectedWindowId,
            httpsHandlerRecognized
        )
        return try {
            val facts = nodes.toFactsOrReject() ?: return Result(Status.REJECTED)
            val candidates = facts.indices.filter { index ->
                val fact = facts[index]
                fact.editable && fact.focused &&
                    BrowserUiCapabilityPolicy.isActionableAddressBarNode(
                        fact,
                        browserPackageName,
                        expectedWindowId,
                        httpsHandlerRecognized
                    ) &&
                    (textPredicate == null || textPredicate(fact.text)) &&
                    nodes[index].actionList.any(::isCertifiedEditorAction)
            }
            if (candidates.isEmpty()) return Result(Status.NOT_FOUND)
            if (candidates.size != 1) return Result(Status.AMBIGUOUS)

            val selected = nodes[candidates.single()]
            val actions = selected.actionList.filter(::isCertifiedEditorAction)
            if (actions.size != 1) return Result(Status.AMBIGUOUS)
            Result(
                status = if (runCatching {
                        selected.performAction(actions.single().id)
                    }.getOrDefault(false)
                ) Status.ACCEPTED else Status.REJECTED,
                selectedViewId = selected.viewIdResourceName
            )
        } finally {
            nodes.forEach(::recycleSafely)
        }
    }

    /**
     * Last submit fallback: click one browser-owned, uniquely identified Go button.
     * Generic "search_button"/"submit" ids are intentionally excluded.
     */
    fun clickCertifiedGoButton(
        root: AccessibilityNodeInfo,
        browserPackageName: String,
        expectedWindowId: Int
    ): Result {
        if (!rootMatches(root, browserPackageName, expectedWindowId)) {
            return Result(Status.NOT_FOUND)
        }
        val nodes = certifiedGoButtonEntryNames.flatMap { entryName ->
            runCatching {
                root.findAccessibilityNodeInfosByViewId("$browserPackageName:id/$entryName")
            }.getOrDefault(emptyList())
        }
        return try {
            val candidates = nodes.filter { node ->
                runCatching {
                    node.isVisibleToUser &&
                        node.packageName?.toString() == browserPackageName &&
                        node.windowId == expectedWindowId &&
                        node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }
                }.getOrDefault(false)
            }
            when (candidates.size) {
                0 -> Result(Status.NOT_FOUND)
                1 -> {
                    val selected = candidates.single()
                    Result(
                        status = if (runCatching {
                                selected.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            }.getOrDefault(false)
                        ) Status.ACCEPTED else Status.REJECTED,
                        selectedViewId = selected.viewIdResourceName
                    )
                }
                else -> Result(Status.AMBIGUOUS)
            }
        } finally {
            nodes.forEach(::recycleSafely)
        }
    }

    private fun performAddressBarAction(
        root: AccessibilityNodeInfo,
        browserPackageName: String,
        expectedWindowId: Int,
        requiredAction: BrowserUiCapabilityPolicy.NodeAction,
        arguments: Bundle? = null,
        textPredicate: ((String?) -> Boolean)? = null,
        httpsHandlerRecognized: Boolean
    ): Result {
        if (!rootMatches(root, browserPackageName, expectedWindowId)) {
            return Result(Status.NOT_FOUND)
        }
        val nodes = collectActionableAddressBarNodes(
            root,
            browserPackageName,
            expectedWindowId,
            httpsHandlerRecognized
        )
        return try {
            val facts = nodes.toFactsOrReject() ?: return Result(Status.REJECTED)
            val selection = BrowserUiCapabilityPolicy.resolveUniqueAddressBarNode(
                nodes = facts,
                expectedBrowserPackage = browserPackageName,
                expectedWindowId = expectedWindowId,
                requiredAction = requiredAction,
                textPredicate = textPredicate,
                httpsHandlerRecognized = httpsHandlerRecognized
            )
            val selectedIndex = selection.index ?: return selection.toResult()
            val selected = nodes[selectedIndex]
            val accepted = if (
                requiredAction == BrowserUiCapabilityPolicy.NodeAction.FOCUS &&
                selected.isFocused
            ) {
                true
            } else {
                val actionId = requiredAction.androidActionId() ?: return Result(Status.REJECTED)
                runCatching { selected.performAction(actionId, arguments) }.getOrDefault(false)
            }
            Result(
                status = if (accepted) Status.ACCEPTED else Status.REJECTED,
                selectedViewId = selected.viewIdResourceName
            )
        } finally {
            nodes.forEach(::recycleSafely)
        }
    }

    private fun collectActionableAddressBarNodes(
        root: AccessibilityNodeInfo,
        browserPackageName: String,
        expectedWindowId: Int,
        httpsHandlerRecognized: Boolean
    ): MutableList<AccessibilityNodeInfo> {
        if (!rootMatches(root, browserPackageName, expectedWindowId)) return mutableListOf()
        val output = mutableListOf<AccessibilityNodeInfo>()
        BrowserUiCapabilityPolicy.strongAddressBarEntryNames.forEach { entryName ->
            output += runCatching {
                root.findAccessibilityNodeInfosByViewId("$browserPackageName:id/$entryName")
            }.getOrDefault(emptyList())
        }
        if (httpsHandlerRecognized) {
            collectSemanticNodes(
                node = root,
                browserPackageName = browserPackageName,
                expectedWindowId = expectedWindowId,
                output = output,
                depth = 0,
                visitedNodes = intArrayOf(0)
            )
        }
        return output
    }

    private fun collectSemanticNodes(
        node: AccessibilityNodeInfo,
        browserPackageName: String,
        expectedWindowId: Int,
        output: MutableList<AccessibilityNodeInfo>,
        depth: Int,
        visitedNodes: IntArray
    ) {
        if (depth >= MAX_TREE_DEPTH || visitedNodes[0] >= MAX_TREE_NODES) return
        for (index in 0 until node.childCount) {
            if (visitedNodes[0] >= MAX_TREE_NODES) return
            val child = node.getChild(index) ?: continue
            var retained = false
            try {
                visitedNodes[0] += 1
                val facts = child.toBrowserUiNode()
                val semantic = !BrowserUiCapabilityPolicy.isStrongAddressBarResource(
                    facts.viewIdResourceName,
                    browserPackageName
                ) && BrowserUiCapabilityPolicy.isSemanticActionableAddressBarNode(
                    node = facts,
                    expectedBrowserPackage = browserPackageName,
                    expectedWindowId = expectedWindowId,
                    httpsHandlerRecognized = true
                )
                if (semantic) {
                    output += child
                    retained = true
                } else {
                    collectSemanticNodes(
                        child,
                        browserPackageName,
                        expectedWindowId,
                        output,
                        depth + 1,
                        visitedNodes
                    )
                }
            } catch (_: RuntimeException) {
                // A transiently stale subtree is simply not certifiable.
            } finally {
                if (!retained) recycleSafely(child)
            }
        }
    }

    private fun List<AccessibilityNodeInfo>.toFactsOrReject(): List<BrowserUiCapabilityPolicy.Node>? =
        runCatching { map { it.toBrowserUiNode() } }.getOrNull()

    private fun AccessibilityNodeInfo.toBrowserUiNode(): BrowserUiCapabilityPolicy.Node {
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
                    action.id == AccessibilityNodeInfo.ACTION_SET_SELECTION ->
                        BrowserUiCapabilityPolicy.NodeAction.SET_SELECTION
                    action.id == AccessibilityNodeInfo.ACTION_SET_TEXT ->
                        BrowserUiCapabilityPolicy.NodeAction.SET_TEXT
                    action.id == AccessibilityNodeInfo.ACTION_PASTE ->
                        BrowserUiCapabilityPolicy.NodeAction.PASTE
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
            hintText = hintText?.toString()
        )
    }

    private fun BrowserUiCapabilityPolicy.NodeAction.androidActionId(): Int? = when (this) {
        BrowserUiCapabilityPolicy.NodeAction.FOCUS -> AccessibilityNodeInfo.ACTION_FOCUS
        BrowserUiCapabilityPolicy.NodeAction.SET_SELECTION -> AccessibilityNodeInfo.ACTION_SET_SELECTION
        BrowserUiCapabilityPolicy.NodeAction.SET_TEXT -> AccessibilityNodeInfo.ACTION_SET_TEXT
        BrowserUiCapabilityPolicy.NodeAction.PASTE -> AccessibilityNodeInfo.ACTION_PASTE
        BrowserUiCapabilityPolicy.NodeAction.IME_ENTER -> if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        ) AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id else null
        BrowserUiCapabilityPolicy.NodeAction.LONG_CLICK -> AccessibilityNodeInfo.ACTION_LONG_CLICK
        BrowserUiCapabilityPolicy.NodeAction.CLICK -> AccessibilityNodeInfo.ACTION_CLICK
    }

    private fun BrowserUiCapabilityPolicy.Selection.toResult(): Result = Result(
        when (status) {
            BrowserUiCapabilityPolicy.SelectionStatus.AMBIGUOUS -> Status.AMBIGUOUS
            BrowserUiCapabilityPolicy.SelectionStatus.NOT_FOUND -> Status.NOT_FOUND
            BrowserUiCapabilityPolicy.SelectionStatus.SELECTED -> Status.REJECTED
        }
    )

    private fun isCertifiedEditorAction(action: AccessibilityNodeInfo.AccessibilityAction): Boolean {
        val label = action.label?.toString()?.trim()?.lowercase(Locale.ROOT) ?: return false
        return label in certifiedEditorActionLabels
    }

    private fun rootMatches(
        root: AccessibilityNodeInfo,
        browserPackageName: String,
        expectedWindowId: Int
    ): Boolean = browserPackageName.isNotBlank() && expectedWindowId >= 0 && runCatching {
        root.packageName?.toString() == browserPackageName && root.windowId == expectedWindowId
    }.getOrDefault(false)

    private fun recycleSafely(node: AccessibilityNodeInfo?) {
        if (node == null) return
        @Suppress("DEPRECATION")
        runCatching { node.recycle() }
    }
}
