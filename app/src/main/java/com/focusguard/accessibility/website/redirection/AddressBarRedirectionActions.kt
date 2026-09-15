package com.focusguard.accessibility.website.redirection

import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.accessibility.AccessibilityNodeInfo
import com.focusguard.utils.BrowserUiCapabilityPolicy
import com.focusguard.utils.WebsiteBlocker
import java.util.Locale

/** Browser-owned, fail-closed actions used by the same-tab redirect pipeline. */
internal object AddressBarRedirectionActions {
    private const val MAX_TREE_DEPTH = 12
    private const val MAX_TREE_NODES = 256

    enum class Status { ACCEPTED, NOT_FOUND, AMBIGUOUS, REJECTED }

    data class Result(val status: Status, val selectedViewId: String? = null) {
        val accepted: Boolean get() = status == Status.ACCEPTED
    }

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
        httpsHandlerRecognized: Boolean
    ): Result {
        val preferClick = BrowserUiCapabilityPolicy.prefersClickAddressBarActivation(
            browserPackageName
        )
        val primary = if (preferClick) BrowserUiCapabilityPolicy.NodeAction.CLICK
            else BrowserUiCapabilityPolicy.NodeAction.FOCUS
        val secondary = if (preferClick) BrowserUiCapabilityPolicy.NodeAction.FOCUS
            else BrowserUiCapabilityPolicy.NodeAction.CLICK
        val first = legacyAction(
            root, browserPackageName, expectedWindowId, primary,
            httpsHandlerRecognized = httpsHandlerRecognized
        )
        if (first.accepted || first.status == Status.AMBIGUOUS) return first
        return legacyAction(
            root, browserPackageName, expectedWindowId, secondary,
            httpsHandlerRecognized = httpsHandlerRecognized
        )
    }

    fun selectAll(
        root: AccessibilityNodeInfo,
        browserPackageName: String,
        expectedWindowId: Int,
        httpsHandlerRecognized: Boolean
    ): Result = rawEditorAction(
        root, browserPackageName, expectedWindowId,
        AccessibilityNodeInfo.ACTION_SET_SELECTION, httpsHandlerRecognized
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
        httpsHandlerRecognized: Boolean
    ): Result = legacyAction(
        root = root,
        browserPackageName = browserPackageName,
        expectedWindowId = expectedWindowId,
        action = BrowserUiCapabilityPolicy.NodeAction.SET_TEXT,
        arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        },
        httpsHandlerRecognized = httpsHandlerRecognized
    )

    fun paste(
        root: AccessibilityNodeInfo,
        browserPackageName: String,
        expectedWindowId: Int,
        httpsHandlerRecognized: Boolean
    ): Result = rawEditorAction(
        root, browserPackageName, expectedWindowId,
        AccessibilityNodeInfo.ACTION_PASTE, httpsHandlerRecognized
    ) { null }

    fun submitImeEnter(
        root: AccessibilityNodeInfo,
        browserPackageName: String,
        expectedWindowId: Int,
        textPredicate: ((String?) -> Boolean)?,
        httpsHandlerRecognized: Boolean
    ): Result {
        if (!BrowserUiCapabilityPolicy.canUseImeEnter(Build.VERSION.SDK_INT)) {
            return Result(Status.NOT_FOUND)
        }
        return legacyAction(
            root = root,
            browserPackageName = browserPackageName,
            expectedWindowId = expectedWindowId,
            action = BrowserUiCapabilityPolicy.NodeAction.IME_ENTER,
            textPredicate = textPredicate,
            httpsHandlerRecognized = httpsHandlerRecognized
        )
    }

    fun submitAnnouncedEditorAction(
        root: AccessibilityNodeInfo,
        browserPackageName: String,
        expectedWindowId: Int,
        textPredicate: ((String?) -> Boolean)?,
        httpsHandlerRecognized: Boolean
    ): Result {
        val nodes = collectAddressBarNodes(
            root, browserPackageName, expectedWindowId, httpsHandlerRecognized
        )
        return try {
            val candidates = nodes.filter { node ->
                val fact = runCatching { node.toFact() }.getOrNull() ?: return@filter false
                node.isEditable && node.isFocused &&
                    BrowserUiCapabilityPolicy.isActionableAddressBarNode(
                        fact, browserPackageName, expectedWindowId, httpsHandlerRecognized
                    ) &&
                    (textPredicate == null || textPredicate(node.text?.toString())) &&
                    node.actionList.count(::isCertifiedEditorAction) == 1
            }
            when (candidates.size) {
                0 -> Result(Status.NOT_FOUND)
                1 -> {
                    val selected = candidates.single()
                    val action = selected.actionList.single(::isCertifiedEditorAction)
                    Result(
                        if (runCatching { selected.performAction(action.id) }.getOrDefault(false))
                            Status.ACCEPTED else Status.REJECTED,
                        selected.viewIdResourceName
                    )
                }
                else -> Result(Status.AMBIGUOUS)
            }
        } finally {
            nodes.forEach(::recycleSafely)
        }
    }

    fun clickCertifiedGoButton(
        root: AccessibilityNodeInfo,
        browserPackageName: String,
        expectedWindowId: Int
    ): Result {
        if (!rootMatches(root, browserPackageName, expectedWindowId)) {
            return Result(Status.NOT_FOUND)
        }
        val nodes = certifiedGoButtonEntryNames.flatMap { entry ->
            runCatching {
                root.findAccessibilityNodeInfosByViewId("$browserPackageName:id/$entry")
            }.getOrDefault(emptyList())
        }
        return try {
            val candidates = nodes.filter { node ->
                node.isVisibleToUser &&
                    node.packageName?.toString() == browserPackageName &&
                    node.windowId == expectedWindowId &&
                    node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }
            }
            when (candidates.size) {
                0 -> Result(Status.NOT_FOUND)
                1 -> {
                    val selected = candidates.single()
                    Result(
                        if (runCatching {
                                selected.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            }.getOrDefault(false)
                        ) Status.ACCEPTED else Status.REJECTED,
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
        httpsHandlerRecognized: Boolean
    ): Result {
        val result = WebsiteBlocker.performUniqueAddressBarAction(
            root = root,
            browserPackageName = browserPackageName,
            expectedWindowId = expectedWindowId,
            requiredAction = action,
            arguments = arguments,
            textPredicate = textPredicate,
            httpsHandlerRecognized = httpsHandlerRecognized
        )
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
        arguments: (AccessibilityNodeInfo) -> Bundle?
    ): Result {
        val nodes = collectAddressBarNodes(
            root, browserPackageName, expectedWindowId, httpsHandlerRecognized
        )
        return try {
            val candidates = nodes.mapNotNull { node ->
                val fact = runCatching { node.toFact() }.getOrNull() ?: return@mapNotNull null
                if (!node.isEditable || !node.isFocused ||
                    node.actionList.none { it.id == actionId } ||
                    !BrowserUiCapabilityPolicy.isActionableAddressBarNode(
                        fact, browserPackageName, expectedWindowId, httpsHandlerRecognized
                    )
                ) null else node to editorRank(node.viewIdResourceName.orEmpty())
            }
            if (candidates.isEmpty()) return Result(Status.NOT_FOUND)
            val bestRank = candidates.maxOf { it.second }
            val winners = candidates.filter { it.second == bestRank }
            if (winners.size != 1) return Result(Status.AMBIGUOUS)
            val selected = winners.single().first
            Result(
                if (runCatching {
                        selected.performAction(actionId, arguments(selected))
                    }.getOrDefault(false)
                ) Status.ACCEPTED else Status.REJECTED,
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
        BrowserUiCapabilityPolicy.strongAddressBarEntryNames.forEach { entry ->
            output += runCatching {
                root.findAccessibilityNodeInfosByViewId("$browserPackageName:id/$entry")
            }.getOrDefault(emptyList())
        }
        if (httpsHandlerRecognized) {
            collectSemanticNodes(
                root, browserPackageName, expectedWindowId, output, 0, intArrayOf(0)
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
        visited: IntArray
    ) {
        if (depth >= MAX_TREE_DEPTH || visited[0] >= MAX_TREE_NODES) return
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
                    output += child
                    retained = true
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
            hintText = hintText?.toString()
        )
    }

    private fun editorRank(viewId: String): Int = when (viewId.substringAfter(":id/", "")) {
        "url_bar_edit_text", "location_bar_edit_text", "url_edit_text",
        "omnibarTextInput", "omnibox_text", "inputField",
        "mozac_browser_toolbar_edit_url_view" -> 50
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
