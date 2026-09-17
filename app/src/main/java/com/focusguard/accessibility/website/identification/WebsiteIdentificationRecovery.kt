package com.focusguard.accessibility.website.identification

import android.view.accessibility.AccessibilityNodeInfo
import com.focusguard.accessibility.website.compatibility.BrowserCompatibilityStore
import com.focusguard.accessibility.website.compatibility.BrowserUrlRecoveryMethod
import com.focusguard.utils.BrowserSurfaceInspector
import com.focusguard.utils.BrowserUiCapabilityPolicy
import com.focusguard.utils.WebsiteBlocker
import kotlinx.coroutines.delay

/** Bounded recovery in one live window; never types, submits, presses Back or closes tabs. */
internal class WebsiteIdentificationRecovery(
    private val browserPackage: String,
    private val windowId: Int,
    private val httpsHandlerRecognized: Boolean,
    private val rootProvider: () -> AccessibilityNodeInfo?,
    private val isCurrent: () -> Boolean
) {
    private fun rejected() = WebsiteIdentificationResult(WebsiteIdentificationStatus.REJECTED_CONTEXT)

    private fun read(): WebsiteIdentificationResult {
        if (!isCurrent()) return rejected()
        val root = rootProvider() ?: return if (isCurrent()) {
            WebsiteIdentificationResult(
                status = WebsiteIdentificationStatus.UNOBSERVABLE,
                browserPackageName = browserPackage,
                windowId = windowId
            )
        } else rejected()
        return try {
            if (!isCurrent()) return rejected()
            WebsiteIdentificationEngine.identifyFromRoot(
                root,
                browserPackage,
                windowId,
                httpsHandlerRecognized
            ).takeIf { isCurrent() } ?: rejected()
        } finally { recycle(root) }
    }

    suspend fun recover(): WebsiteIdentificationResult {
        if (!isCurrent()) return rejected()
        var result = read()
        if (resolved(result)) return result
        val preferred = BrowserCompatibilityStore.preferredUrlRecoveryMethod(browserPackage)
        val activationOrder = if (BrowserUiCapabilityPolicy.prefersClickAddressBarActivation(browserPackage)) {
            listOf(BrowserUrlRecoveryMethod.CLICK, BrowserUrlRecoveryMethod.FOCUS)
        } else {
            listOf(BrowserUrlRecoveryMethod.FOCUS, BrowserUrlRecoveryMethod.CLICK)
        }
        val methods = (listOfNotNull(preferred) + activationOrder +
            BrowserUrlRecoveryMethod.REVEAL_TOOLBAR).distinct()
        var lastAccepted: BrowserUrlRecoveryMethod? = null
        // Reinspection after each action can expose a different editor/id. A
        // second bounded pass also retries activation after revealing the toolbar.
        repeat(2) { pass ->
            for (method in methods) {
                if (!isCurrent()) return rejected()
                result = read()
                if (resolved(result)) {
                    rememberIfCurrent(result, lastAccepted)
                    return result
                }
                if (!isCurrent()) return rejected()
                val root = rootProvider() ?: continue
                val accepted = try {
                    if (!isCurrent() || root.packageName?.toString() != browserPackage ||
                        root.windowId != windowId ||
                        BrowserSurfaceInspector.inspect(root, browserPackage).isNativeUi ||
                        !isCurrent()
                    ) false else when (method) {
                        BrowserUrlRecoveryMethod.REVEAL_TOOLBAR ->
                            pass == 0 && isCurrent() && revealToolbar(root)
                        else -> if (!isCurrent()) false else WebsiteBlocker.performUniqueAddressBarAction(
                            root,
                            browserPackage,
                            windowId,
                            if (method == BrowserUrlRecoveryMethod.CLICK) {
                                BrowserUiCapabilityPolicy.NodeAction.CLICK
                            } else {
                                BrowserUiCapabilityPolicy.NodeAction.FOCUS
                            },
                            httpsHandlerRecognized = httpsHandlerRecognized,
                            allowFallbacks = false
                        ).accepted
                    }
                } finally { recycle(root) }
                if (!isCurrent()) return rejected()
                if (accepted) lastAccepted = method
                delay(120L)
                if (!isCurrent()) return rejected()
                result = read()
                if (resolved(result)) {
                    rememberIfCurrent(result, lastAccepted)
                    return result
                }
            }
        }
        delay(160L)
        if (!isCurrent()) return rejected()
        val finalResult = read()
        rememberIfCurrent(finalResult, lastAccepted)
        return if (isCurrent()) {
            BrowserSiteOnlyBlockingPolicy.applyAfterRecovery(finalResult)
        } else rejected()
    }

    private fun resolved(result: WebsiteIdentificationResult): Boolean =
        result.urlCandidate != null || result.status == WebsiteIdentificationStatus.NATIVE_BROWSER_UI ||
            result.status == WebsiteIdentificationStatus.REJECTED_CONTEXT

    private fun rememberIfCurrent(
        result: WebsiteIdentificationResult,
        method: BrowserUrlRecoveryMethod?
    ) {
        if (isCurrent() && result.urlCandidate != null && method != null) {
            BrowserCompatibilityStore.recordUrlRecoverySuccess(browserPackage, method)
        }
    }

    /** Scroll only a unique web viewport, never a page link, form or menu. */
    private fun revealToolbar(root: AccessibilityNodeInfo): Boolean {
        if (!isCurrent()) return false
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        var visited = 0
        fun collect(node: AccessibilityNodeInfo, depth: Int) {
            if (!isCurrent() || ++visited > 256 || depth > 24 || !node.isVisibleToUser ||
                node.packageName?.toString() != browserPackage || node.windowId != windowId
            ) return
            if (BrowserSurfaceInspector.isWebContainer(node)) {
                if (node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD ||
                        it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id }) {
                    @Suppress("DEPRECATION")
                    candidates += AccessibilityNodeInfo.obtain(node)
                }
                return
            }
            for (index in 0 until node.childCount) {
                if (!isCurrent() || visited >= 256) break
                val child = node.getChild(index) ?: continue
                try { collect(child, depth + 1) } finally { recycle(child) }
            }
        }
        return try {
            collect(root, 0)
            if (!isCurrent()) return false
            val viewport = candidates.singleOrNull() ?: return false
            val action = if (viewport.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD }) {
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            } else AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id
            isCurrent() && viewport.performAction(action)
        } catch (_: RuntimeException) {
            false
        } finally { candidates.forEach(::recycle) }
    }

    private fun recycle(node: AccessibilityNodeInfo?) {
        @Suppress("DEPRECATION")
        if (node != null) runCatching { node.recycle() }
    }
}
