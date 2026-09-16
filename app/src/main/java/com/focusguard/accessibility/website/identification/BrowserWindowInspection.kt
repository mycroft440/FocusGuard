package com.focusguard.accessibility.website.identification

import android.os.Build
import android.os.SystemClock
import android.text.InputType
import android.view.accessibility.AccessibilityNodeInfo
import com.focusguard.accessibility.website.compatibility.BrowserCompatibilityStore
import com.focusguard.accessibility.website.compatibility.BrowserIdentificationMethod
import com.focusguard.utils.BrowserSurfaceInspector
import com.focusguard.utils.BrowserUiCapabilityPolicy
import com.focusguard.utils.FocusGuardLogger
import com.focusguard.utils.WebsiteBlocker
import java.util.concurrent.atomic.AtomicLong

/**
 * One bounded observation of a browser window.
 *
 * The old identification path could classify the surface and then independently
 * scan the same tree for URL, raw address text and address-bar presence. This
 * inspector keeps those facts in one snapshot. Exact browser-owned resource ids
 * are still probed first so toolbars buried below large WebView trees remain
 * discoverable, followed by a single bounded tree walk for surface and semantic
 * evidence.
 */
internal object BrowserWindowInspection {
    private const val MAX_TREE_DEPTH = 32
    private const val MAX_TREE_NODES = 512
    private const val MAX_CHILD_READS = 512
    private const val MAX_ID_LOOKUPS = 24
    private const val MAX_INSPECTION_MILLIS = 120L
    private const val SLOW_INSPECTION_MILLIS = 80L
    private const val SLOW_LOG_INTERVAL_MILLIS = 5_000L

    private val lastSlowLogElapsed = AtomicLong(0L)

    private val nativePanelEntryNames = setOf(
        "app_menu_list", "app_menu_layout", "menu_panel", "menu_list",
        "tab_switcher", "tab_switcher_view", "tab_switcher_container",
        "tab_list_recycler_view", "tab_grid_recycler_view", "tab_overview",
        "bookmark_manager", "bookmarks_list", "history_list", "download_manager",
        "preferences", "preference_list", "settings_container",
        "new_tab_page", "new_tab_page_layout", "ntp_content"
    )

    internal data class Metrics(
        val elapsedMillis: Long,
        val nodeVisits: Int,
        val childReads: Int,
        val idLookups: Int,
        val exhausted: Boolean
    )

    internal data class Snapshot(
        val surface: BrowserSurfaceInspector.Surface,
        val rawAddressText: String?,
        val urlCandidate: String?,
        val addressBarObservable: Boolean,
        val strongAddressBarObserved: Boolean,
        val metrics: Metrics
    )

    internal class Budget(
        private val maxNodeVisits: Int = MAX_TREE_NODES,
        private val maxChildReads: Int = MAX_CHILD_READS,
        private val maxIdLookups: Int = MAX_ID_LOOKUPS,
        maxDurationMillis: Long = MAX_INSPECTION_MILLIS,
        private val clockNanos: () -> Long = SystemClock::elapsedRealtimeNanos
    ) {
        private val startedAtNanos = clockNanos()
        private val deadlineNanos = startedAtNanos + maxDurationMillis.coerceAtLeast(1L) * 1_000_000L
        private var nodeVisits = 0
        private var childReads = 0
        private var idLookups = 0
        private var limitHit = false

        fun tryVisitNode(): Boolean = consume(
            used = nodeVisits,
            limit = maxNodeVisits,
            increment = { nodeVisits += 1 }
        )

        fun tryReadChild(): Boolean = consume(
            used = childReads,
            limit = maxChildReads,
            increment = { childReads += 1 }
        )

        fun tryLookupId(): Boolean = consume(
            used = idLookups,
            limit = maxIdLookups,
            increment = { idLookups += 1 }
        )

        fun canContinue(): Boolean {
            if (limitHit) return false
            if (clockNanos() >= deadlineNanos) {
                limitHit = true
                return false
            }
            return true
        }

        fun metrics(): Metrics {
            val elapsed = ((clockNanos() - startedAtNanos).coerceAtLeast(0L) / 1_000_000L)
            return Metrics(
                elapsedMillis = elapsed,
                nodeVisits = nodeVisits,
                childReads = childReads,
                idLookups = idLookups,
                exhausted = limitHit
            )
        }

        private inline fun consume(
            used: Int,
            limit: Int,
            increment: () -> Unit
        ): Boolean {
            if (!canContinue() || used >= limit) {
                limitHit = true
                return false
            }
            increment()
            return true
        }
    }

    private data class Accumulator(
        var rawAddressText: String? = null,
        var urlCandidate: String? = null,
        var addressBarObservable: Boolean = false,
        var strongAddressBarObserved: Boolean = false,
        var selectedViewId: String? = null,
        var selectedMethod: BrowserIdentificationMethod? = null,
        var nativeNodes: Int = 0,
        var webContent: Boolean = false,
        var nativePanel: Boolean = false,
        var complete: Boolean = true
    )

    fun inspect(
        root: AccessibilityNodeInfo?,
        browserPackageName: String,
        expectedWindowId: Int,
        httpsHandlerRecognized: Boolean,
        budget: Budget = Budget()
    ): Snapshot {
        if (root == null || browserPackageName.isBlank() || expectedWindowId < 0) {
            return Snapshot(
                surface = BrowserSurfaceInspector.Surface.UNKNOWN,
                rawAddressText = null,
                urlCandidate = null,
                addressBarObservable = false,
                strongAddressBarObserved = false,
                metrics = budget.metrics()
            )
        }

        val rootMatches = runCatching {
            root.packageName?.toString() == browserPackageName && root.windowId == expectedWindowId
        }.getOrDefault(false)
        if (!rootMatches) {
            return Snapshot(
                surface = BrowserSurfaceInspector.Surface.UNKNOWN,
                rawAddressText = null,
                urlCandidate = null,
                addressBarObservable = false,
                strongAddressBarObserved = false,
                metrics = budget.metrics()
            )
        }

        val accumulator = Accumulator()
        probeStrongResourceIds(
            root = root,
            browserPackageName = browserPackageName,
            expectedWindowId = expectedWindowId,
            budget = budget,
            accumulator = accumulator
        )

        visit(
            node = root,
            browserPackageName = browserPackageName,
            expectedWindowId = expectedWindowId,
            httpsHandlerRecognized = httpsHandlerRecognized,
            insideWebContent = false,
            depth = 0,
            budget = budget,
            accumulator = accumulator
        )

        if (!budget.canContinue()) accumulator.complete = false

        val surface = when {
            accumulator.nativePanel -> BrowserSurfaceInspector.Surface.NATIVE_PANEL
            accumulator.webContent -> BrowserSurfaceInspector.Surface.WEB_CONTENT
            accumulator.complete && accumulator.nativeNodes > 1 ->
                BrowserSurfaceInspector.Surface.NATIVE_UI
            else -> BrowserSurfaceInspector.Surface.UNKNOWN
        }

        if (surface != BrowserSurfaceInspector.Surface.NATIVE_PANEL &&
            accumulator.addressBarObservable
        ) {
            BrowserCompatibilityStore.recordIdentificationSuccess(
                packageName = browserPackageName,
                viewIdResourceName = accumulator.selectedViewId,
                method = accumulator.selectedMethod ?: BrowserIdentificationMethod.SEMANTIC_TREE,
                observedValue = accumulator.rawAddressText
            )
        }

        val metrics = budget.metrics()
        maybeLogSlowInspection(
            browserPackageName = browserPackageName,
            windowId = expectedWindowId,
            surface = surface,
            metrics = metrics
        )

        return Snapshot(
            surface = surface,
            rawAddressText = accumulator.rawAddressText,
            urlCandidate = accumulator.urlCandidate,
            addressBarObservable = accumulator.addressBarObservable,
            strongAddressBarObserved = accumulator.strongAddressBarObserved,
            metrics = metrics
        )
    }

    private fun probeStrongResourceIds(
        root: AccessibilityNodeInfo,
        browserPackageName: String,
        expectedWindowId: Int,
        budget: Budget,
        accumulator: Accumulator
    ) {
        val strongEntries = BrowserCompatibilityStore.prioritizeUrlEntryNames(
            browserPackageName,
            BrowserUiCapabilityPolicy.strongAddressBarEntryNames
        ).filter { entryName ->
            BrowserUiCapabilityPolicy.isStrongAddressBarResource(
                "$browserPackageName:id/$entryName",
                browserPackageName
            )
        }

        for (entryName in strongEntries) {
            if (!budget.tryLookupId()) {
                accumulator.complete = false
                return
            }
            val nodes = runCatching {
                root.findAccessibilityNodeInfosByViewId("$browserPackageName:id/$entryName")
            }.getOrDefault(emptyList())
            try {
                for (node in nodes) {
                    if (!budget.canContinue()) {
                        accumulator.complete = false
                        return
                    }
                    val valid = runCatching {
                        node.isVisibleToUser &&
                            node.packageName?.toString() == browserPackageName &&
                            node.windowId == expectedWindowId &&
                            BrowserUiCapabilityPolicy.isStrongAddressBarResource(
                                node.viewIdResourceName.orEmpty(),
                                browserPackageName
                            )
                    }.getOrDefault(false)
                    if (!valid) continue
                    observeAddressBar(
                        node = node,
                        browserPackageName = browserPackageName,
                        expectedWindowId = expectedWindowId,
                        httpsHandlerRecognized = false,
                        inWebContent = true,
                        accumulator = accumulator
                    )
                    if (accumulator.urlCandidate != null) return
                }
            } finally {
                nodes.forEach(::recycleSafely)
            }
        }
    }

    private fun visit(
        node: AccessibilityNodeInfo,
        browserPackageName: String,
        expectedWindowId: Int,
        httpsHandlerRecognized: Boolean,
        insideWebContent: Boolean,
        depth: Int,
        budget: Budget,
        accumulator: Accumulator
    ) {
        if (depth > MAX_TREE_DEPTH || !budget.tryVisitNode()) {
            accumulator.complete = false
            return
        }

        try {
            if (!node.isVisibleToUser) return
            if (node.packageName?.toString() != browserPackageName ||
                node.windowId != expectedWindowId
            ) {
                accumulator.complete = false
                return
            }

            val isWebContainer = BrowserSurfaceInspector.isWebContainer(node)
            val inWebContent = insideWebContent || isWebContainer
            if (isWebContainer) accumulator.webContent = true

            if (!inWebContent) {
                accumulator.nativeNodes += 1
                if (isNativePanelResource(node.viewIdResourceName.orEmpty(), browserPackageName)) {
                    accumulator.nativePanel = true
                }
            }

            observeAddressBar(
                node = node,
                browserPackageName = browserPackageName,
                expectedWindowId = expectedWindowId,
                httpsHandlerRecognized = httpsHandlerRecognized,
                inWebContent = inWebContent,
                accumulator = accumulator
            )

            for (index in 0 until node.childCount) {
                if (!budget.tryReadChild()) {
                    accumulator.complete = false
                    return
                }
                val child = node.getChild(index)
                if (child == null) {
                    accumulator.complete = false
                    continue
                }
                try {
                    visit(
                        node = child,
                        browserPackageName = browserPackageName,
                        expectedWindowId = expectedWindowId,
                        httpsHandlerRecognized = httpsHandlerRecognized,
                        insideWebContent = inWebContent,
                        depth = depth + 1,
                        budget = budget,
                        accumulator = accumulator
                    )
                } finally {
                    recycleSafely(child)
                }
                if (!budget.canContinue()) {
                    accumulator.complete = false
                    return
                }
            }
        } catch (_: RuntimeException) {
            accumulator.complete = false
        }
    }

    private fun observeAddressBar(
        node: AccessibilityNodeInfo,
        browserPackageName: String,
        expectedWindowId: Int,
        httpsHandlerRecognized: Boolean,
        inWebContent: Boolean,
        accumulator: Accumulator
    ) {
        val fact = runCatching {
            val variation = node.inputType and InputType.TYPE_MASK_VARIATION
            BrowserUiCapabilityPolicy.Node(
                packageName = node.packageName?.toString().orEmpty(),
                windowId = node.windowId,
                viewIdResourceName = node.viewIdResourceName.orEmpty(),
                visible = node.isVisibleToUser,
                editable = node.isEditable,
                focused = node.isFocused,
                focusable = node.isFocusable,
                uriInput = variation == InputType.TYPE_TEXT_VARIATION_URI,
                text = node.text?.toString(),
                contentDescription = node.contentDescription?.toString(),
                actions = node.actionList.mapNotNull { action ->
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
                hintText = node.hintText?.toString(),
                inWebContent = inWebContent
            )
        }.getOrNull() ?: return

        if (!BrowserUiCapabilityPolicy.isReadOnlyAddressBarNode(
                node = fact,
                expectedBrowserPackage = browserPackageName,
                expectedWindowId = expectedWindowId,
                httpsHandlerRecognized = httpsHandlerRecognized
            )
        ) return

        accumulator.addressBarObservable = true
        val strong = BrowserUiCapabilityPolicy.isStrongAddressBarResource(
            fact.viewIdResourceName,
            browserPackageName
        )
        if (strong) accumulator.strongAddressBarObserved = true

        val text = sanitizeText(fact.text).takeIf(String::isNotEmpty)
            ?: sanitizeText(fact.contentDescription).takeIf(String::isNotEmpty)
        if (accumulator.rawAddressText == null && text != null) {
            accumulator.rawAddressText = text
        }
        if (accumulator.urlCandidate == null && text != null) {
            accumulator.urlCandidate = WebsiteBlocker.extractUrlCandidate(text)
        }

        val method = identificationMethod(
            browserPackageName = browserPackageName,
            viewIdResourceName = fact.viewIdResourceName,
            strong = strong
        )
        if (accumulator.selectedViewId == null ||
            (accumulator.selectedMethod == BrowserIdentificationMethod.SEMANTIC_TREE && strong)
        ) {
            accumulator.selectedViewId = fact.viewIdResourceName.takeIf(String::isNotBlank)
            accumulator.selectedMethod = method
        }
    }

    private fun identificationMethod(
        browserPackageName: String,
        viewIdResourceName: String,
        strong: Boolean
    ): BrowserIdentificationMethod {
        val entryName = viewIdResourceName.substringAfter(":id/", "")
        return when {
            entryName.isNotBlank() &&
                BrowserCompatibilityStore.preferredAddressBarEntryName(browserPackageName) ==
                entryName -> BrowserIdentificationMethod.CACHED_RESOURCE_ID
            strong -> BrowserIdentificationMethod.STRONG_RESOURCE_ID
            else -> BrowserIdentificationMethod.SEMANTIC_TREE
        }
    }

    private fun isNativePanelResource(viewIdResourceName: String, browserPackageName: String): Boolean {
        val prefix = "$browserPackageName:id/"
        return viewIdResourceName.startsWith(prefix) &&
            viewIdResourceName.removePrefix(prefix) in nativePanelEntryNames
    }

    private fun sanitizeText(value: String?): String = value.orEmpty()
        .replace(INVISIBLE_CHARACTER_REGEX, "")
        .trim()

    private fun maybeLogSlowInspection(
        browserPackageName: String,
        windowId: Int,
        surface: BrowserSurfaceInspector.Surface,
        metrics: Metrics
    ) {
        if (!metrics.exhausted && metrics.elapsedMillis < SLOW_INSPECTION_MILLIS) return
        val now = SystemClock.elapsedRealtime()
        val previous = lastSlowLogElapsed.get()
        if (previous != 0L && now - previous < SLOW_LOG_INTERVAL_MILLIS) return
        if (!lastSlowLogElapsed.compareAndSet(previous, now)) return
        FocusGuardLogger.log(
            "A11yPerf",
            "Inspeção navegador: ${metrics.elapsedMillis}ms, package=$browserPackageName, " +
                "window=$windowId, surface=$surface, nodes=${metrics.nodeVisits}, " +
                "children=${metrics.childReads}, ids=${metrics.idLookups}, " +
                "budgetExhausted=${metrics.exhausted}"
        )
    }

    @Suppress("DEPRECATION")
    private fun recycleSafely(node: AccessibilityNodeInfo?) {
        if (node != null) runCatching { node.recycle() }
    }

    private val INVISIBLE_CHARACTER_REGEX =
        Regex("[\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u206F\\uFEFF]")
}
