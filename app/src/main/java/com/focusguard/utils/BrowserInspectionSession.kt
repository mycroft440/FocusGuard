package com.focusguard.utils

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.focusguard.accessibility.website.compatibility.BrowserIdentificationMethod
import java.util.concurrent.atomic.AtomicLong

/**
 * Short-lived state shared by browser inspection stages running against the same
 * accessibility root. It prevents surface/URL/text/address-bar readers from
 * starting independent walks and gives the whole worker pass one shared budget.
 */
internal class BrowserInspectionBudget(
    private val maxNodes: Int = 512,
    private val maxChildQueries: Int = 640,
    private val maxIdQueries: Int = 24,
    private val maxAncestorQueries: Int = 128,
    timeoutMillis: Long = 80L
) {
    private val startedAtNanos = SystemClock.elapsedRealtimeNanos()
    private val deadlineNanos = startedAtNanos + timeoutMillis * 1_000_000L

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

    val elapsedMillis: Long
        get() = ((SystemClock.elapsedRealtimeNanos() - startedAtNanos).coerceAtLeast(0L)) / 1_000_000L

    val deadlineExceeded: Boolean
        get() = SystemClock.elapsedRealtimeNanos() >= deadlineNanos

    val isExhausted: Boolean
        get() = exhaustedReason != null || deadlineExceeded

    fun tryVisitNode(depth: Int, maxDepth: Int): Boolean {
        if (depth > maxDepth) {
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
}

internal class BrowserInspectionSession(
    val rootIdentity: Int,
    val windowId: Int,
    val browserPackage: String,
    val createdAtElapsedNanos: Long = SystemClock.elapsedRealtimeNanos(),
    val budget: BrowserInspectionBudget = BrowserInspectionBudget(),
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
}

internal object BrowserInspectionSessionStore {
    private const val SESSION_REUSE_MILLIS = 120L
    private const val SLOW_INSPECTION_MILLIS = 40L
    private const val LOG_INTERVAL_MILLIS = 2_000L

    private val sessionLocal = ThreadLocal<BrowserInspectionSession?>()
    private val lastPerfLogElapsed = AtomicLong(0L)

    /** Pins one budget to one synchronous pass; no predicate or node survives it. */
    fun <T> withInspection(
        root: AccessibilityNodeInfo,
        browserPackage: String,
        isCurrent: () -> Boolean,
        block: () -> T
    ): T {
        val previous = sessionLocal.get()
        val identity = System.identityHashCode(root)
        val session = if (previous?.scoped == true && previous.rootIdentity == identity &&
            previous.browserPackage == browserPackage && previous.windowId == root.windowId
        ) previous else BrowserInspectionSession(
            rootIdentity = identity,
            windowId = root.windowId,
            browserPackage = browserPackage,
            isCurrent = isCurrent,
            scoped = true
        )
        sessionLocal.set(session)
        return try { block() } finally {
            if (previous == null) sessionLocal.remove() else sessionLocal.set(previous)
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
