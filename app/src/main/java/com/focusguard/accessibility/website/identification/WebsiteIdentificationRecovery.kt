package com.focusguard.accessibility.website.identification

import android.view.accessibility.AccessibilityNodeInfo
import com.focusguard.accessibility.website.compatibility.BrowserCompatibilityStore
import com.focusguard.accessibility.website.compatibility.BrowserDetector
import com.focusguard.accessibility.website.compatibility.BrowserProfileRegistry
import com.focusguard.accessibility.website.compatibility.BrowserRecognitionPolicy
import com.focusguard.accessibility.website.compatibility.BrowserUrlRecoveryMethod
import com.focusguard.accessibility.website.diagnostics.WebsiteBlockingDiagnostics
import com.focusguard.utils.BrowserSurfaceInspector
import com.focusguard.utils.BrowserUiCapabilityPolicy
import com.focusguard.utils.FocusGuardLogger
import com.focusguard.utils.WebsiteBlocker
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal enum class BrowserRecoveryReason {
    URL_RECOVERED,
    NATIVE_UI_REJECTED,
    RECOVERY_EXHAUSTED,
    FAIL_CLOSED
}

/** Bounded recovery in one live window; never types, submits, presses Back or closes tabs. */
internal class WebsiteIdentificationRecovery(
    private val browserPackage: String,
    private val windowId: Int,
    private val httpsHandlerRecognized: Boolean,
    private val rootProvider: () -> AccessibilityNodeInfo?,
    private val isCurrent: () -> Boolean,
    private val readDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private fun rejected() = WebsiteIdentificationResult(WebsiteIdentificationStatus.REJECTED_CONTEXT)

    private suspend fun read(): WebsiteIdentificationResult = withContext(readDispatcher) {
        if (!isCurrent()) return@withContext rejected()
        val root = rootProvider() ?: return@withContext if (isCurrent()) {
            WebsiteIdentificationResult(
                status = WebsiteIdentificationStatus.UNOBSERVABLE,
                browserPackageName = browserPackage,
                windowId = windowId
            )
        } else rejected()
        try {
            if (!isCurrent()) return@withContext rejected()
            WebsiteIdentificationEngine.identifyFromRoot(
                root,
                browserPackage,
                windowId,
                httpsHandlerRecognized,
                isCurrent = isCurrent
            ).takeIf { isCurrent() } ?: rejected()
        } finally { recycle(root) }
    }

    suspend fun recover(): WebsiteIdentificationResult = recoveryMutex.withLock {
        if (!isCurrent()) return@withLock rejected()
        if (BrowserProfileRegistry.isKnownBrowserPackage(browserPackage)) {
            recoverKnownProfileSerially()
        } else {
            withTimeoutOrNull(GENERIC_RECOVERY_TOTAL_TIMEOUT_MILLIS) {
                recoverGenericSerially()
            } ?: genericTimeoutResult()
        }
    }

    /** Existing compatibility behavior remains untouched for exact shipped browser profiles. */
    private suspend fun recoverKnownProfileSerially(): WebsiteIdentificationResult {
        if (!isCurrent()) return rejected()
        var result = read()
        if (resolved(result)) {
            traceResolution(result)
            return result
        }
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
                    traceResolution(result)
                    return result
                }
                if (!isCurrent()) return rejected()
                val observationBaseline = BrowserObservationSignal.currentVersion(browserPackage, windowId)
                val root = rootProvider() ?: continue
                val accepted = try {
                    if (!validRecoveryRoot(root)) false else when (method) {
                        BrowserUrlRecoveryMethod.REVEAL_TOOLBAR ->
                            pass == 0 && isCurrent() && revealToolbar(root)
                        else -> performAddressBarRecoveryAction(root, method)
                    }
                } finally { recycle(root) }
                if (!isCurrent()) return rejected()
                if (accepted) {
                    lastAccepted = method
                    BrowserObservationSignal.awaitAfter(
                        browserPackage,
                        windowId,
                        observationBaseline,
                        ACTION_OBSERVATION_TIMEOUT_MILLIS
                    )
                }
                if (!isCurrent()) return rejected()
                result = read()
                if (resolved(result)) {
                    rememberIfCurrent(result, lastAccepted)
                    traceResolution(result)
                    return result
                }
            }
        }
        if (!isCurrent()) return rejected()
        val finalObservationBaseline = BrowserObservationSignal.currentVersion(browserPackage, windowId)
        BrowserObservationSignal.awaitAfter(
            browserPackage,
            windowId,
            finalObservationBaseline,
            FINAL_OBSERVATION_TIMEOUT_MILLIS
        )
        if (!isCurrent()) return rejected()
        val finalResult = read()
        rememberIfCurrent(finalResult, lastAccepted)
        return finalizeRecovery(finalResult)
    }

    /**
     * Generic v4 recovery is deliberately smaller than the browser-specific path:
     * at most three distinct techniques and at most 500 ms for the whole attempt.
     * The outer timeout includes fresh tree acquisition, actions and observation waits.
     */
    private suspend fun recoverGenericSerially(): WebsiteIdentificationResult {
        if (!isCurrent()) return rejected()
        var result = read()
        if (resolved(result)) {
            traceResolution(result)
            return result
        }

        val preferred = BrowserCompatibilityStore.preferredUrlRecoveryMethod(browserPackage)
        val activationOrder = if (BrowserUiCapabilityPolicy.prefersClickAddressBarActivation(browserPackage)) {
            listOf(BrowserUrlRecoveryMethod.CLICK, BrowserUrlRecoveryMethod.FOCUS)
        } else {
            listOf(BrowserUrlRecoveryMethod.FOCUS, BrowserUrlRecoveryMethod.CLICK)
        }
        val methods = (listOfNotNull(preferred) + activationOrder +
            BrowserUrlRecoveryMethod.REVEAL_TOOLBAR)
            .distinct()
            .take(GENERIC_MAX_RECOVERY_TECHNIQUES)
        var lastAccepted: BrowserUrlRecoveryMethod? = null

        for (method in methods) {
            if (!isCurrent()) return rejected()
            result = read()
            if (resolved(result)) {
                rememberIfCurrent(result, lastAccepted)
                traceResolution(result)
                return result
            }

            val observationBaseline = BrowserObservationSignal.currentVersion(browserPackage, windowId)
            val root = rootProvider() ?: continue
            val accepted = try {
                if (!validRecoveryRoot(root)) false else when (method) {
                    BrowserUrlRecoveryMethod.REVEAL_TOOLBAR -> revealToolbar(
                        root = root,
                        maxNodes = GENERIC_REVEAL_MAX_NODES,
                        maxDepth = GENERIC_REVEAL_MAX_DEPTH
                    )
                    else -> performAddressBarRecoveryAction(root, method)
                }
            } finally { recycle(root) }
            if (!isCurrent()) return rejected()

            if (accepted) {
                lastAccepted = method
                BrowserObservationSignal.awaitAfter(
                    browserPackage,
                    windowId,
                    observationBaseline,
                    GENERIC_ACTION_OBSERVATION_TIMEOUT_MILLIS
                )
            }
            if (!isCurrent()) return rejected()
            result = read()
            if (resolved(result)) {
                rememberIfCurrent(result, lastAccepted)
                traceResolution(result)
                return result
            }
        }

        rememberIfCurrent(result, lastAccepted)
        return finalizeRecovery(result)
    }

    private fun validRecoveryRoot(root: AccessibilityNodeInfo): Boolean =
        isCurrent() &&
            root.packageName?.toString() == browserPackage &&
            root.windowId == windowId &&
            !BrowserSurfaceInspector.inspect(root, browserPackage).isNativeUi &&
            isCurrent()

    private fun performAddressBarRecoveryAction(
        root: AccessibilityNodeInfo,
        method: BrowserUrlRecoveryMethod
    ): Boolean {
        if (!isCurrent()) return false
        return WebsiteBlocker.performUniqueAddressBarAction(
            root,
            browserPackage,
            windowId,
            if (method == BrowserUrlRecoveryMethod.CLICK) {
                BrowserUiCapabilityPolicy.NodeAction.CLICK
            } else {
                BrowserUiCapabilityPolicy.NodeAction.FOCUS
            },
            httpsHandlerRecognized = httpsHandlerRecognized,
            allowFallbacks = false,
            isCurrent = isCurrent
        ).accepted
    }

    private fun genericTimeoutResult(): WebsiteIdentificationResult {
        if (!isCurrent()) return rejected()
        return finalizeRecovery(
            WebsiteIdentificationResult(
                status = WebsiteIdentificationStatus.UNOBSERVABLE,
                browserPackageName = browserPackage,
                windowId = windowId,
                webContentObserved = true
            )
        )
    }

    private fun finalizeRecovery(result: WebsiteIdentificationResult): WebsiteIdentificationResult {
        if (!isCurrent()) return rejected()
        val postRecovery = BrowserSiteOnlyBlockingPolicy.applyAfterRecovery(result)
        when {
            postRecovery.urlCandidate != null -> trace(BrowserRecoveryReason.URL_RECOVERED)
            postRecovery.status == WebsiteIdentificationStatus.NATIVE_BROWSER_UI ->
                trace(BrowserRecoveryReason.NATIVE_UI_REJECTED)
            postRecovery.status == WebsiteIdentificationStatus.UNOBSERVABLE ->
                trace(BrowserRecoveryReason.RECOVERY_EXHAUSTED)
        }
        val detection = BrowserDetector.detect(browserPackage)
        return if (BrowserRecognitionPolicy.shouldFailClosedAfterRecovery(
                classification = detection.classification,
                identificationStatus = postRecovery.status,
                addressBarObservable = postRecovery.addressBarObservable,
                urlCandidatePresent = postRecovery.urlCandidate != null
            )
        ) {
            trace(BrowserRecoveryReason.FAIL_CLOSED)
            WebsiteBlockingDiagnostics.recordIdentificationFailure(
                browserPackageName = browserPackage,
                windowId = windowId,
                status = postRecovery.status.name,
                addressBarObservable = postRecovery.addressBarObservable,
                webContentObserved = postRecovery.webContentObserved,
                evidence = postRecovery.evidence.map { it.name }
            )
            postRecovery.copy(
                webContentObserved = true,
                evidence = postRecovery.evidence + WebsiteIdentificationLayer.FAIL_CLOSED
            )
        } else {
            postRecovery
        }
    }

    private fun resolved(result: WebsiteIdentificationResult): Boolean =
        result.urlCandidate != null || result.status == WebsiteIdentificationStatus.NATIVE_BROWSER_UI ||
            result.status == WebsiteIdentificationStatus.REJECTED_CONTEXT

    private fun traceResolution(result: WebsiteIdentificationResult) {
        when {
            result.urlCandidate != null -> trace(BrowserRecoveryReason.URL_RECOVERED)
            result.status == WebsiteIdentificationStatus.NATIVE_BROWSER_UI ->
                trace(BrowserRecoveryReason.NATIVE_UI_REJECTED)
        }
    }

    private fun trace(reason: BrowserRecoveryReason) {
        FocusGuardLogger.addBreadcrumb("BrowserRecovery[$browserPackage]: ${reason.name}")
    }

    private fun rememberIfCurrent(
        result: WebsiteIdentificationResult,
        method: BrowserUrlRecoveryMethod?
    ) {
        if (isCurrent() && result.urlCandidate != null && method != null) {
            BrowserCompatibilityStore.recordUrlRecoverySuccess(browserPackage, method)
        }
    }

    /** Scroll only a unique web viewport, never a page link, form or menu. */
    private fun revealToolbar(
        root: AccessibilityNodeInfo,
        maxNodes: Int = KNOWN_REVEAL_MAX_NODES,
        maxDepth: Int = KNOWN_REVEAL_MAX_DEPTH
    ): Boolean {
        if (!isCurrent()) return false
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        var visited = 0
        fun collect(node: AccessibilityNodeInfo, depth: Int) {
            if (!isCurrent() || ++visited > maxNodes || depth > maxDepth || !node.isVisibleToUser ||
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
                if (!isCurrent() || visited >= maxNodes) break
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
            if (!isCurrent()) return false
            val accepted = viewport.performAction(action)
            isCurrent() && accepted
        } catch (_: RuntimeException) {
            false
        } finally { candidates.forEach(::recycle) }
    }

    private fun recycle(node: AccessibilityNodeInfo?) {
        @Suppress("DEPRECATION")
        if (node != null) runCatching { node.recycle() }
    }

    internal companion object {
        /** Only one complementary browser-recovery pipeline may inspect at a time. */
        private val recoveryMutex = Mutex()

        const val ACTION_OBSERVATION_TIMEOUT_MILLIS = 180L
        const val FINAL_OBSERVATION_TIMEOUT_MILLIS = 160L

        const val GENERIC_MAX_RECOVERY_TECHNIQUES = 3
        const val GENERIC_RECOVERY_TOTAL_TIMEOUT_MILLIS = 500L
        const val GENERIC_ACTION_OBSERVATION_TIMEOUT_MILLIS = 100L
        const val GENERIC_REVEAL_MAX_NODES = 200
        const val GENERIC_REVEAL_MAX_DEPTH = 20
        private const val KNOWN_REVEAL_MAX_NODES = 256
        private const val KNOWN_REVEAL_MAX_DEPTH = 24
    }
}
