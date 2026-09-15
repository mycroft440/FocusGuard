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
    private fun read(): WebsiteIdentificationResult {
        if (!isCurrent()) return WebsiteIdentificationResult(WebsiteIdentificationStatus.REJECTED_CONTEXT)
        val root = rootProvider()
        return try {
            WebsiteIdentificationEngine.identifyFromRoot(root, browserPackage, windowId, httpsHandlerRecognized)
        } finally { recycle(root) }
    }

    suspend fun recover(): WebsiteIdentificationResult {
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
                if (!isCurrent()) return WebsiteIdentificationResult(WebsiteIdentificationStatus.REJECTED_CONTEXT)
                result = read()
                if (resolved(result)) {
                    remember(result, lastAccepted)
                    return result
                }
                val root = rootProvider() ?: continue
                val accepted = try {
                    if (root.packageName?.toString() != browserPackage || root.windowId != windowId ||
                        BrowserSurfaceInspector.inspect(root, browserPackage).isNativeUi
                    ) false else when (method) {
                        BrowserUrlRecoveryMethod.REVEAL_TOOLBAR -> pass == 0 && revealToolbar(root)
                        else -> WebsiteBlocker.performUniqueAddressBarAction(
                            root, browserPackage, windowId,
                            if (method == BrowserUrlRecoveryMethod.CLICK) BrowserUiCapabilityPolicy.NodeAction.CLICK
                            else BrowserUiCapabilityPolicy.NodeAction.FOCUS,
                            httpsHandlerRecognized = httpsHandlerRecognized,
                            allowFallbacks = false
                        ).accepted
                    }
                } finally { recycle(root) }
                if (accepted) lastAccepted = method
                delay(120L)
                result = read()
                if (resolved(result)) {
                    remember(result, lastAccepted)
                    return result
                }
            }
        }
        delay(160L)
        return read().also { remember(it, lastAccepted) }
    }

    private fun resolved(result: WebsiteIdentificationResult): Boolean =
        result.urlCandidate != null || result.status == WebsiteIdentificationStatus.NATIVE_BROWSER_UI ||
            result.status == WebsiteIdentificationStatus.REJECTED_CONTEXT

    private fun remember(result: WebsiteIdentificationResult, method: BrowserUrlRecoveryMethod?) {
        if (result.urlCandidate != null && method != null) {
            BrowserCompatibilityStore.recordUrlRecoverySuccess(browserPackage, method)
        }
    }

    /** Scroll only a unique web viewport, never a page link, form or menu. */
    private fun revealToolbar(root: AccessibilityNodeInfo): Boolean {
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        var visited = 0
        fun collect(node: AccessibilityNodeInfo, depth: Int) {
            if (++visited > 256 || depth > 24 || !node.isVisibleToUser ||
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
                if (visited >= 256) break
                val child = node.getChild(index) ?: continue
                try { collect(child, depth + 1) } finally { recycle(child) }
            }
        }
        return try {
            collect(root, 0)
            val viewport = candidates.singleOrNull() ?: return false
            val action = if (viewport.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD }) {
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            } else AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id
            viewport.performAction(action)
        } catch (_: RuntimeException) {
            false
        } finally { candidates.forEach(::recycle) }
    }

    private fun recycle(node: AccessibilityNodeInfo?) {
        @Suppress("DEPRECATION")
        if (node != null) runCatching { node.recycle() }
    }
}
