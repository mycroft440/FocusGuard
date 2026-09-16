from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    file_path = Path(path)
    text = file_path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"Expected exactly one match in {path}, found {count}")
    file_path.write_text(text.replace(old, new, 1), encoding="utf-8")


def replace_exact_count(path: str, old: str, new: str, expected: int) -> None:
    file_path = Path(path)
    text = file_path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != expected:
        raise RuntimeError(f"Expected {expected} matches in {path}, found {count}")
    file_path.write_text(text.replace(old, new), encoding="utf-8")


policy_path = "app/src/main/java/com/focusguard/utils/BrowserUiCapabilityPolicy.kt"
blocker_path = "app/src/main/java/com/focusguard/utils/WebsiteBlocker.kt"
test_path = Path("app/src/test/java/com/focusguard/utils/BrowserNestedAddressBarPolicyTest.kt")

old_read_only = '''    fun isReadOnlyAddressBarNode(
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
'''
new_read_only = '''    fun isReadOnlyAddressBarNode(
        node: Node,
        expectedBrowserPackage: String,
        expectedWindowId: Int,
        httpsHandlerRecognized: Boolean
    ): Boolean {
        if (!node.visible ||
            node.packageName != expectedBrowserPackage ||
            node.windowId != expectedWindowId
        ) return false

        // Exact browser-owned resources are allowed to remain readable even when
        // a browser exposes its toolbar below a WebView/ContentView/GeckoView.
        // This is observation only; action authorization remains stricter below.
        if (isStrongAddressBarResource(node.viewIdResourceName, expectedBrowserPackage)) {
            return !isDuckDuckGoNativeInput(node) || isDuckDuckGoAddressInput(node)
        }

        if (node.inWebContent) {
            if (!httpsHandlerRecognized) return false
            return isNestedBrowserOwnedAddressBarEvidence(node, expectedBrowserPackage)
        }

        // Native browser menus/settings are a legitimate no-address-bar surface.
        // Never pretend this node is an address bar or authorize automation
        // against it. Whole-window classification is handled separately.
        if (isNativeBrowserUiNode(node, expectedBrowserPackage, expectedWindowId)) {
            return false
        }

        if (!httpsHandlerRecognized) return false

        val prefix = "$expectedBrowserPackage:id/"
'''
replace_once(policy_path, old_read_only, new_read_only)

semantic_anchor = '''    fun isSemanticActionableAddressBarNode(
        node: Node,
        expectedBrowserPackage: String,
        expectedWindowId: Int,
        httpsHandlerRecognized: Boolean
    ): Boolean {
'''
helper = '''    /**
     * Read-only escape hatch for browsers that place native address chrome below
     * their web container. A candidate must still be an Android resource owned by
     * the browser package and its id must explicitly describe URL/address chrome.
     * HTML labels, arbitrary URI inputs and generic navigation fields stay rejected.
     */
    private fun isNestedBrowserOwnedAddressBarEvidence(
        node: Node,
        expectedBrowserPackage: String
    ): Boolean {
        val prefix = "$expectedBrowserPackage:id/"
        val viewId = node.viewIdResourceName
        if (!viewId.startsWith(prefix) || viewId.length <= prefix.length) return false

        val entryName = viewId.substring(prefix.length).lowercase(Locale.ROOT)
        val idLooksAddressLike = entryName.contains("url") ||
            entryName.contains("uri") ||
            entryName.contains("omnibox") ||
            entryName.contains("address") ||
            entryName.contains("location_bar")
        if (!idLooksAddressLike) return false

        if (node.editable) return node.uriInput || hasAddressBarLabel(node) || idLooksAddressLike

        val displayLooksAddressLike = sequenceOf(node.text, node.contentDescription)
            .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
            .any { value ->
                value.none(Char::isWhitespace) &&
                    (value.contains("://") || value.contains('.'))
            }
        return displayLooksAddressLike || hasAddressBarLabel(node)
    }

'''
replace_once(policy_path, semantic_anchor, helper + semantic_anchor)

replace_once(
    blocker_path,
    '''    private const val MAX_TREE_DEPTH = 12
    private const val MAX_TREE_NODES = 256
''',
    '''    // Browser toolbars can be nested below a virtualized WebView/GeckoView.
    // Keep the walk bounded, but large enough to reach browser-owned chrome after
    // entering those containers without turning arbitrary page text into URL evidence.
    private const val MAX_TREE_DEPTH = 24
    private const val MAX_TREE_NODES = 512
'''
)

web_container_guard = '''        if (node == null || depth > MAX_TREE_DEPTH ||
            BrowserSurfaceInspector.isWebContainer(node)
        ) return null
'''
bounded_guard = '''        if (node == null || depth > MAX_TREE_DEPTH) return null
'''
replace_exact_count(blocker_path, web_container_guard, bounded_guard, 3)

nodes_anchor = '''        addressBarEntryNamesFor(browserPackageName).forEach { entryName ->
            val expectedId = "$browserPackageName:id/$entryName"
            val matches = runCatching {
                root.findAccessibilityNodeInfosByViewId(expectedId)
            }.getOrDefault(emptyList())
            nodes += matches
        }
        if (httpsHandlerRecognized) {
'''
nodes_replacement = '''        addressBarEntryNamesFor(browserPackageName).forEach { entryName ->
            val expectedId = "$browserPackageName:id/$entryName"
            val matches = runCatching {
                root.findAccessibilityNodeInfosByViewId(expectedId)
            }.getOrDefault(emptyList())
            nodes += matches
        }
        // Some browsers expose a genuine native toolbar below their web container,
        // while findAccessibilityNodeInfosByViewId() does not return that descendant.
        // Traverse through the container only for exact strong browser resources.
        // Semantic page fields never enter this action candidate list.
        collectStrongActionAddressBarNodes(
            node = root,
            browserPackageName = browserPackageName,
            expectedWindowId = expectedWindowId,
            output = nodes,
            depth = 0,
            visitedNodes = intArrayOf(0)
        )
        if (httpsHandlerRecognized) {
'''
replace_once(blocker_path, nodes_anchor, nodes_replacement)

collector_anchor = '''    private fun collectSemanticActionAddressBarNodes(
        node: AccessibilityNodeInfo,
        browserPackageName: String,
        expectedWindowId: Int,
        output: MutableList<AccessibilityNodeInfo>,
        depth: Int,
        visitedNodes: IntArray
    ) {
'''
collector = '''    private fun collectStrongActionAddressBarNodes(
        node: AccessibilityNodeInfo,
        browserPackageName: String,
        expectedWindowId: Int,
        output: MutableList<AccessibilityNodeInfo>,
        depth: Int,
        visitedNodes: IntArray
    ) {
        if (depth > MAX_TREE_DEPTH || visitedNodes[0] >= MAX_TREE_NODES ||
            !node.isVisibleToUser || node.windowId != expectedWindowId
        ) return
        visitedNodes[0] += 1

        val belongsToBrowser = node.packageName?.toString() == browserPackageName
        if (belongsToBrowser && BrowserUiCapabilityPolicy.isStrongAddressBarResource(
                node.viewIdResourceName.orEmpty(),
                browserPackageName
            )
        ) {
            if (output.none { existing -> existing == node }) {
                @Suppress("DEPRECATION")
                output += AccessibilityNodeInfo.obtain(node)
            }
            return
        }

        for (index in 0 until node.childCount) {
            if (visitedNodes[0] >= MAX_TREE_NODES) break
            val child = node.getChild(index) ?: continue
            try {
                collectStrongActionAddressBarNodes(
                    node = child,
                    browserPackageName = browserPackageName,
                    expectedWindowId = expectedWindowId,
                    output = output,
                    depth = depth + 1,
                    visitedNodes = visitedNodes
                )
            } finally {
                recycleSafely(child)
            }
        }
    }

'''
replace_once(blocker_path, collector_anchor, collector + collector_anchor)

if test_path.exists():
    raise RuntimeError(f"Test file already exists: {test_path}")

test_path.write_text('''package com.focusguard.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BrowserNestedAddressBarPolicyTest {

    @Test
    fun `strong firefox and via bars remain readable below web containers`() {
        listOf(
            BrowserUiCapabilityPolicy.Node(
                packageName = "org.mozilla.firefox",
                windowId = 3,
                viewIdResourceName =
                    "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
                visible = true,
                editable = false,
                focused = false,
                focusable = true,
                uriInput = false,
                text = "https://blocked.example",
                actions = setOf(BrowserUiCapabilityPolicy.NodeAction.CLICK),
                inWebContent = true
            ),
            BrowserUiCapabilityPolicy.Node(
                packageName = "mark.via.gp",
                windowId = 3,
                viewIdResourceName = "mark.via.gp:id/url_text",
                visible = true,
                editable = false,
                focused = false,
                focusable = true,
                uriInput = false,
                text = "blocked.example",
                actions = setOf(BrowserUiCapabilityPolicy.NodeAction.CLICK),
                inWebContent = true
            )
        ).forEach { node ->
            assertThat(
                BrowserUiCapabilityPolicy.isReadOnlyAddressBarNode(
                    node = node,
                    expectedBrowserPackage = node.packageName,
                    expectedWindowId = 3,
                    httpsHandlerRecognized = false
                )
            ).isTrue()
        }
    }

    @Test
    fun `uc and phoenix browser owned address resources can identify nested urls`() {
        listOf(
            BrowserUiCapabilityPolicy.Node(
                packageName = "com.UCMobile.intl",
                windowId = 8,
                viewIdResourceName = "com.UCMobile.intl:id/current_url_text",
                visible = true,
                editable = false,
                focused = false,
                focusable = false,
                uriInput = false,
                text = "https://blocked.example/path",
                actions = emptySet(),
                inWebContent = true
            ),
            BrowserUiCapabilityPolicy.Node(
                packageName = "com.transsion.phoenix",
                windowId = 8,
                viewIdResourceName = "com.transsion.phoenix:id/address_input",
                visible = true,
                editable = true,
                focused = true,
                focusable = true,
                uriInput = true,
                text = "blocked.example",
                actions = setOf(BrowserUiCapabilityPolicy.NodeAction.SET_TEXT),
                inWebContent = true
            )
        ).forEach { node ->
            assertThat(
                BrowserUiCapabilityPolicy.isReadOnlyAddressBarNode(
                    node = node,
                    expectedBrowserPackage = node.packageName,
                    expectedWindowId = 8,
                    httpsHandlerRecognized = true
                )
            ).isTrue()
        }
    }

    @Test
    fun `nested semantic fields remain read only and cannot gain browser actions`() {
        val phoenixAddress = BrowserUiCapabilityPolicy.Node(
            packageName = "com.transsion.phoenix",
            windowId = 9,
            viewIdResourceName = "com.transsion.phoenix:id/address_input",
            visible = true,
            editable = true,
            focused = true,
            focusable = true,
            uriInput = true,
            text = "blocked.example",
            actions = setOf(BrowserUiCapabilityPolicy.NodeAction.SET_TEXT),
            inWebContent = true
        )
        assertThat(
            BrowserUiCapabilityPolicy.isReadOnlyAddressBarNode(
                phoenixAddress,
                "com.transsion.phoenix",
                9,
                httpsHandlerRecognized = true
            )
        ).isTrue()
        assertThat(
            BrowserUiCapabilityPolicy.isActionableAddressBarNode(
                phoenixAddress,
                "com.transsion.phoenix",
                9,
                httpsHandlerRecognized = true
            )
        ).isFalse()
    }

    @Test
    fun `html and generic navigation fields inside web content stay rejected`() {
        val pageField = BrowserUiCapabilityPolicy.Node(
            packageName = "mark.via.gp",
            windowId = 11,
            viewIdResourceName = "mark.via.gp:id/navigation_input",
            visible = true,
            editable = true,
            focused = true,
            focusable = true,
            uriInput = true,
            text = "https://blocked.example",
            actions = setOf(BrowserUiCapabilityPolicy.NodeAction.SET_TEXT),
            inWebContent = true
        )
        val foreignResource = pageField.copy(
            viewIdResourceName = "com.example.page:id/current_url_text"
        )

        assertThat(
            BrowserUiCapabilityPolicy.isReadOnlyAddressBarNode(
                pageField,
                "mark.via.gp",
                11,
                httpsHandlerRecognized = true
            )
        ).isFalse()
        assertThat(
            BrowserUiCapabilityPolicy.isReadOnlyAddressBarNode(
                foreignResource,
                "mark.via.gp",
                11,
                httpsHandlerRecognized = true
            )
        ).isFalse()
    }
}
''', encoding="utf-8")

print("Nested browser address-bar detection patch applied")
