package com.focusguard.utils

import android.view.accessibility.AccessibilityNodeInfo
import com.focusguard.accessibility.website.compatibility.BrowserCompatibilityStore
import com.focusguard.accessibility.website.compatibility.BrowserIdentificationMethod

/**
 * Last-resort reader for exact browser-owned address resources.
 *
 * Normal semantic traversal intentionally stops at WebView/ContentView boundaries so arbitrary
 * HTML fields can never become the browser address. Some Chromium builds nevertheless nest native
 * toolbar nodes below such a container. This reader may cross that boundary only to locate an
 * exact, package-qualified strong address-bar resource. It never consumes text from unrelated
 * nodes. Once such a resource is found, only that certified subtree is inspected for its value.
 */
internal object StrongAddressBarFallbackReader {
    private const val MAX_TREE_DEPTH = 24
    private const val MAX_TREE_NODES = 384
    private const val MAX_CERTIFIED_SUBTREE_NODES = 48

    data class Evidence(
        val url: String?,
        val text: String?,
        val focusedAddressEditor: Boolean,
        val viewIdResourceName: String?
    )

    fun read(
        root: AccessibilityNodeInfo,
        browserPackageName: String,
        expectedWindowId: Int,
        isCurrent: () -> Boolean
    ): Evidence? {
        if (!isCurrent() || browserPackageName.isBlank() || expectedWindowId < 0 ||
            root.packageName?.toString() != browserPackageName ||
            root.windowId != expectedWindowId
        ) return null

        var visited = 0
        var result: Evidence? = null

        fun inspectCertifiedSubtree(node: AccessibilityNodeInfo): Evidence? {
            var subtreeVisited = 0
            var bestText: String? = null
            var url: String? = null
            var focusedEditor = false

            fun inspect(current: AccessibilityNodeInfo, depth: Int) {
                if (!isCurrent() || url != null || depth > MAX_TREE_DEPTH ||
                    subtreeVisited >= MAX_CERTIFIED_SUBTREE_NODES ||
                    !current.isVisibleToUser ||
                    current.packageName?.toString() != browserPackageName ||
                    current.windowId != expectedWindowId
                ) return
                subtreeVisited += 1

                val values = listOf(
                    current.text?.toString(),
                    current.contentDescription?.toString(),
                    current.hintText?.toString()
                ).filterNotNull().map(String::trim).filter(String::isNotBlank)
                values.forEach { value ->
                    if (bestText == null) bestText = value
                    if (url == null) url = WebsiteBlocker.extractUrlCandidate(value)
                }
                focusedEditor = focusedEditor || (current.isEditable && current.isFocused)

                for (index in 0 until current.childCount) {
                    if (!isCurrent() || url != null ||
                        subtreeVisited >= MAX_CERTIFIED_SUBTREE_NODES
                    ) break
                    val child = runCatching { current.getChild(index) }.getOrNull() ?: continue
                    try {
                        inspect(child, depth + 1)
                    } finally {
                        recycle(child)
                    }
                }
            }

            inspect(node, 0)
            if (!isCurrent()) return null
            return Evidence(
                url = url,
                text = url ?: bestText,
                focusedAddressEditor = focusedEditor,
                viewIdResourceName = node.viewIdResourceName
            ).takeIf { it.url != null || it.text != null }
        }

        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (!isCurrent() || result != null || depth > MAX_TREE_DEPTH ||
                visited >= MAX_TREE_NODES || !node.isVisibleToUser ||
                node.packageName?.toString() != browserPackageName ||
                node.windowId != expectedWindowId
            ) return
            visited += 1

            val viewId = node.viewIdResourceName.orEmpty()
            if (BrowserSurfaceInspector.isTrustedExactAddressBarResource(
                    viewIdResourceName = viewId,
                    packageName = browserPackageName
                )
            ) {
                inspectCertifiedSubtree(node)?.let { evidence ->
                    result = evidence
                    return
                }
            }

            for (index in 0 until node.childCount) {
                if (!isCurrent() || result != null || visited >= MAX_TREE_NODES) break
                val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
                try {
                    visit(child, depth + 1)
                } finally {
                    recycle(child)
                }
            }
        }

        runCatching { visit(root, 0) }
        val evidence = result?.takeIf { isCurrent() } ?: return null
        BrowserCompatibilityStore.recordIdentificationSuccess(
            packageName = browserPackageName,
            viewIdResourceName = evidence.viewIdResourceName,
            method = BrowserIdentificationMethod.STRONG_RESOURCE_ID,
            observedValue = evidence.url ?: evidence.text
        )
        return evidence
    }

    private fun recycle(node: AccessibilityNodeInfo?) {
        if (node == null) return
        @Suppress("DEPRECATION")
        runCatching { node.recycle() }
    }
}
