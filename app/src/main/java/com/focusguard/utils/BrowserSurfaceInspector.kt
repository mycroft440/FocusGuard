package com.focusguard.utils

import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

/** Current-window evidence only. No package-wide exemption or remembered URL. */
internal object BrowserSurfaceInspector {
    enum class Surface {
        NATIVE_UI, NATIVE_PANEL, WEB_CONTENT, UNKNOWN;

        val isNativeUi: Boolean get() = this == NATIVE_UI || this == NATIVE_PANEL
    }

    private const val MAX_NODES = 512
    private const val MAX_DEPTH = 32
    private val webContainerClassMarkers = setOf(
        "webview", "webcontent", "contentview", "geckoview", "renderwidgethostview"
    )
    private val nativePanels = setOf(
        "app_menu_list", "app_menu_layout", "menu_panel", "menu_list",
        "tab_switcher", "tab_switcher_view", "tab_switcher_container",
        "tab_list_recycler_view", "tab_grid_recycler_view", "tab_overview",
        "bookmark_manager", "bookmarks_list", "history_list", "download_manager",
        "preferences", "preference_list", "settings_container",
        "new_tab_page", "new_tab_page_layout", "ntp_content"
    )

    internal fun isWebContentSignal(className: String, actionIds: Set<Int>): Boolean {
        val normalizedClass = className.lowercase(Locale.ROOT)
        if (webContainerClassMarkers.any(normalizedClass::contains)) return true
        return AccessibilityNodeInfo.ACTION_NEXT_HTML_ELEMENT in actionIds ||
            AccessibilityNodeInfo.ACTION_PREVIOUS_HTML_ELEMENT in actionIds
    }

    fun isWebContainer(node: AccessibilityNodeInfo): Boolean = isWebContentSignal(
        className = node.className?.toString().orEmpty(),
        actionIds = node.actionList.mapTo(linkedSetOf()) { it.id }
    )

    /**
     * Exact browser-owned address-bar resources remain browser chrome even when a
     * browser nests its toolbar below WebView/ContentView/GeckoView in the
     * accessibility hierarchy. Every non-exact node below those containers stays
     * page content, so semantic page fields never gain browser UI privileges.
     */
    fun isNativeNode(node: AccessibilityNodeInfo): Boolean {
        var ancestor: AccessibilityNodeInfo? = null
        return try {
            if (isTrustedExactAddressBarResource(
                    viewIdResourceName = node.viewIdResourceName.orEmpty(),
                    packageName = node.packageName?.toString().orEmpty()
                )
            ) return true
            if (isWebContainer(node)) return false
            ancestor = node.parent
            var depth = 0
            while (ancestor != null && depth++ < MAX_DEPTH) {
                val current = ancestor
                if (isWebContainer(current)) return false
                ancestor = current.parent
                recycle(current)
            }
            ancestor == null
        } catch (_: RuntimeException) {
            false
        } finally {
            recycle(ancestor)
        }
    }

    internal fun isTrustedExactAddressBarResource(
        viewIdResourceName: String,
        packageName: String
    ): Boolean = BrowserUiCapabilityPolicy.isStrongAddressBarResource(
        viewIdResourceName = viewIdResourceName,
        expectedBrowserPackage = packageName
    )

    fun inspect(root: AccessibilityNodeInfo?, browserPackage: String): Surface {
        if (root == null || browserPackage.isBlank()) return Surface.UNKNOWN
        return try {
            if (root.packageName?.toString() != browserPackage || root.windowId < 0) {
                return Surface.UNKNOWN
            }
            val windowId = root.windowId
            var visited = 0
            var complete = true
            var nativeNodes = 0
            var webContent = false
            var nativePanel = false
            fun visit(node: AccessibilityNodeInfo, depth: Int) {
                if (++visited > MAX_NODES || depth > MAX_DEPTH) {
                    complete = false
                    return
                }
                if (!node.isVisibleToUser) return
                if (node.packageName?.toString() != browserPackage || node.windowId != windowId) {
                    complete = false
                    return
                }
                if (isWebContainer(node)) {
                    webContent = true
                    return // Never classify HTML labels/ids as browser chrome.
                }
                nativeNodes++
                val id = node.viewIdResourceName.orEmpty()
                val prefix = "$browserPackage:id/"
                if (id.startsWith(prefix) && id.removePrefix(prefix) in nativePanels) {
                    nativePanel = true
                }
                for (index in 0 until node.childCount) {
                    if (visited >= MAX_NODES) {
                        complete = false
                        break
                    }
                    val child = node.getChild(index)
                    if (child == null) {
                        complete = false
                        continue
                    }
                    try { visit(child, depth + 1) } finally { recycle(child) }
                }
            }
            visit(root, 0)
            when {
                nativePanel -> Surface.NATIVE_PANEL
                webContent -> Surface.WEB_CONTENT
                complete && nativeNodes > 1 -> Surface.NATIVE_UI
                else -> Surface.UNKNOWN
            }
        } catch (_: RuntimeException) {
            Surface.UNKNOWN
        }
    }

    private fun recycle(node: AccessibilityNodeInfo?) {
        @Suppress("DEPRECATION")
        if (node != null) runCatching { node.recycle() }
    }
}
