package com.focusguard.utils

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.focusguard.accessibility.website.compatibility.BrowserIdentificationMethod
import com.focusguard.accessibility.website.compatibility.BrowserProfileRegistry
import java.util.concurrent.atomic.AtomicLong

/**
 * Short-lived state shared by browser inspection stages running against the same
 * accessibility root. It prevents surface/URL/text/address-bar readers from
 * starting independent walks and gives the whole worker pass one shared budget.
 */
internal class BrowserInspectionBudget(
    private val maxNodes: Int = 512,
    private val maxChildQueries: Int = 640,
    // The address compatibility catalog includes browser-specific selectors plus
    // read-only fallbacks. Keep enough bounded id probes for the full catalog so
    // Firefox/Fenix can still reach its semantic Compose traversal afterwards.
    private val maxIdQueries: Int = 32,
    private val maxAncestorQueries: Int = 128,
    private val absoluteMaxDepth: Int = Int.MAX_VALUE,
    timeoutMillis: Long = 80L
) {
    private val startedAtNanos = SystemClock.elapsedRealtimeNanos()
    private val timeoutBudgetMillis = timeoutMillis.coerceAtLeast(1L)
    private val deadlineNanos = startedAtNanos + timeoutBudgetMillis * 1_000_000L

    var nodesVisited: Int = 0
        private set
    var childQueries: Int = 0
        private set
    var idQueries: Int = 0
        private set
    var ancestorQueries: Int = 0
        private set
    var depthStops: Int = 0
        private set
    var exhaustedReason: String? = null
        private set

    internal val configuredMaxNodes: Int
        get() = maxNodes
    internal val configuredMaxDepth: Int
        get() = absoluteMaxDepth
    internal val configuredTimeoutMillis: Long
        get() = timeoutBudgetMillis

    val elapsedMillis: Long
        get() = ((SystemClock.elapsedRealtimeNanos() - startedAtNanos).coerceAtLeast(0L)) / 1_000_000L

    val deadlineExceeded: Boolean
        get() = SystemClock.elapsedRealtimeNanos() >= deadlineNanos

    val isExhausted: Boolean
        get() = exhaustedReason != null || deadlineExceeded

    fun tryVisitNode(depth: Int, maxDepth: Int): Boolean {
        val effectiveMaxDepth = minOf(maxDepth, absoluteMaxDepth)
        if (depth > effectiveMaxDepth) {
            depthStops += 1
            return false
        }
        if (!checkDeadline()) return false
        if (nodesVisited >= maxNodes) {
            exhaustedReason = exhaustedReason ?: "nodes"
            return false
        }
        nodesVisited += 1
        return true
    }

    fun tryChildQuery(): Boolean {
        if (!checkDeadline()) return false
        if (nodesVisited >= maxNodes) {
            exhaustedReason = exhaustedReason ?: "nodes"
            return false
        }
        if (childQueries >= maxChildQueries) {
            exhaustedReason = exhaustedReason ?: "children"
            return false
        }
        childQueries += 1
        return true
    }

    fun tryIdQuery(): Boolean {
        if (!checkDeadline()) return false
        if (idQueries >= maxIdQueries) {
            exhaustedReason = exhaustedReason ?: "ids"
            return false
        }
        idQueries += 1
        return true
    }

    fun tryAncestorQuery(): Boolean {
        if (!checkDeadline()) return false
        if (ancestorQueries >= maxAncestorQueries) {
            exhaustedReason = exhaustedReason ?: "ancestors"
            return false
        }
        ancestorQueries += 1
        return true
    }

    private fun checkDeadline(): Boolean {
        if (!deadlineExceeded) return true
        exhaustedReason = exhaustedReason ?: "deadline"
        return false
    }

    companion object {
        internal const val GENERIC_MAX_NODES = 200
        internal const val GENERIC_MAX_DEPTH = 20
        internal const val GENERIC_TIMEOUT_MILLIS = 20L

        /**
         * Exact registered browser profiles keep the existing compatibility budget.
         * Packages without a profile use the stricter v4 generic budget so an
         * unknown browser candidate cannot monopolize Accessibility tree work.
         */
        fun forBrowserPackage(browserPackage: String): BrowserInspectionBudget =
            if (BrowserProfileRegistry.isKnownBrowserPackage(browserPackage)) {
                BrowserInspectionBudget()
            } else {
                BrowserInspectionBudget(
                    maxNodes = GENERIC_MAX_NODES,
                    maxChildQueries = 240,
                    maxIdQueries = 20,
                    maxAncestorQueries = 64,
                    absoluteMaxDepth = GENERIC_MAX_DEPTH,
                    timeoutMillis = GENERIC_TIMEOUT_MILLIS
                )
            }
    }
}

internal class BrowserInspectionSession(
    val rootIdentity: Int,
    val windowId: Int,
    val browserPackage: String,
    val createdAtElapsedNanos: Long = SystemClock.elapsedRealtimeNanos(),
    val budget: BrowserInspectionBudget = BrowserInspectionBudget.forBrowserPackage(browserPackage),
    val isCurrent: () -> Boolean = { true },
    val scoped: Boolean = false
) {
    var surface: BrowserSurfaceInspector.Surface? = null
    var addressComplete: Boolean = false
    var addressHttpsHandlerRecognized: Boolean = false
    var url: String? = null
    var addressText: String? = null
    var addressBarObservable: Boolean = false
    var focusedAddressEditor: Boolean = false
    var strongAddressBarObserved: Boolean = false
    var identificationMethod: BrowserIdentificationMethod? = null

    /**
     * Keeps only immutable/primitive observations after a scoped pass. The scoped
     * currentness predicate and budget are deliberately not retained. This lets an
     * immediate consumer re-read the exact focus/surface observation that produced
     * the URL without allowing a stale predicate to escape its inspection scope.
     */
    fun detachedForReuse(): BrowserInspectionSession = BrowserInspectionSession(
        rootIdentity = rootIdentity,
        windowId = windowId,
        browserPackage = browserPackage
    ).also { copy ->
        copy.surface = surface
        copy.addressComplete = addressComplete
        copy.addressHttpsHandlerRecognized = addressHttpsHandlerRecognized
        copy.url = url
        copy.addressText = addressText
        copy.addressBarObservable = addressBarObservable
        copy.focusedAddressEditor = focusedAddressEditor
        copy.strongAddressBarObserved = strongAddressBarObserved
        copy.identificationMethod = identificationMethod
    }
}

internal object BrowserInspectionSessionStore {
    private const val SESSION_REUSE_MILLIS = 120L
    private const val SLOW_INSPECTION_MILLIS = 40L
    private const val LOG_INTERVAL_MILLIS = 2_000L

    private val sessionLocal = ThreadLocal<BrowserInspectionSession?>()
    private val lastPerfLogElapsed = AtomicLong(0L)

    /**
     * Pins one budget to one synchronous pass. When the outermost pass completes,
     * only a detached observation snapshot is retained briefly so callers that must
     * correlate URL + surface + editor focus do not fall back to default values.
     */
    fun <T> withInspection(
        root: AccessibilityNodeInfo,
        browserPackage: String,
        isCurrent: () -> Boolean,
        block: () -> T
    ): T {
        val previous = sessionLocal.get()
        // A detached observation is a short-lived result for an immediate consumer,
        // not an enclosing inspection scope. Only an actually scoped session may be
        // restored after a nested pass; otherwise an older detached observation could
        // overwrite the newer focus/URL facts produced by this pass.
        val enclosing = previous?.takeIf { it.scoped }
        val identity = System.identityHashCode(root)
        val session = if (enclosing != null && enclosing.rootIdentity == identity &&
            enclosing.browserPackage == browserPackage && enclosing.windowId == root.windowId
        ) enclosing else BrowserInspectionSession(
            rootIdentity = identity,
            windowId = root.windowId,
            browserPackage = browserPackage,
            isCurrent = isCurrent,
            scoped = true
        )
        sessionLocal.set(session)
        return try {
            block()
        } finally {
            if (enclosing == null) {
                sessionLocal.set(session.detachedForReuse())
            } else {
                sessionLocal.set(enclosing)
            }
        }
    }

    fun sessionFor(
        root: AccessibilityNodeInfo,
        browserPackage: String
    ): BrowserInspectionSession {
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        val identity = System.identityHashCode(root)
        val windowId = runCatching { root.windowId }.getOrDefault(-1)
        val current = sessionLocal.get()
        if (current != null &&
            current.rootIdentity == identity &&
            current.windowId == windowId &&
            current.browserPackage == browserPackage &&
            (current.scoped ||
                nowNanos - current.createdAtElapsedNanos <= SESSION_REUSE_MILLIS * 1_000_000L)
        ) {
            return current
        }

        return BrowserInspectionSession(
            rootIdentity = identity,
            windowId = windowId,
            browserPackage = browserPackage
        ).also(sessionLocal::set)
    }

    fun logIfNeeded(session: BrowserInspectionSession, stage: String) {
        val budget = session.budget
        if (budget.elapsedMillis < SLOW_INSPECTION_MILLIS && !budget.isExhausted) return

        val nowElapsed = SystemClock.elapsedRealtime()
        val previous = lastPerfLogElapsed.get()
        if (previous != 0L && nowElapsed - previous < LOG_INTERVAL_MILLIS) return
        if (!lastPerfLogElapsed.compareAndSet(previous, nowElapsed)) return

        FocusGuardLogger.log(
            "BrowserInspect",
            "stage=$stage elapsed=${budget.elapsedMillis}ms " +
                "nodes=${budget.nodesVisited} children=${budget.childQueries} " +
                "ids=${budget.idQueries} ancestors=${budget.ancestorQueries} " +
                "depthStops=${budget.depthStops} exhausted=${budget.exhaustedReason ?: "none"} " +
                "package=${session.browserPackage} window=${session.windowId}"
        )
    }
}
