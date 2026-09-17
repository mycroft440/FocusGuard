package com.focusguard.utils

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.atomic.AtomicLong

/**
 * Short-lived state shared by the synchronous browser inspection stages that run
 * against the same accessibility root. It prevents URL/text/address-bar/surface
 * readers from starting independent unbounded walks during one callback.
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
    val budget: BrowserInspectionBudget = BrowserInspectionBudget()
) {
    var surface: BrowserSurfaceInspector.Surface? = null
    var addressComplete: Boolean = false
    var addressHttpsHandlerRecognized: Boolean = false
    var url: String? = null
    var addressText: String? = null
    var addressBarObservable: Boolean = false
}

internal object BrowserInspectionSessionStore {
    private const val SESSION_REUSE_MILLIS = 120L
    private const val SLOW_INSPECTION_MILLIS = 40L
    private const val LOG_INTERVAL_MILLIS = 2_000L

    private val sessionLocal = ThreadLocal<BrowserInspectionSession?>()
    private val lastPerfLogElapsed = AtomicLong(0L)

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
            nowNanos - current.createdAtElapsedNanos <= SESSION_REUSE_MILLIS * 1_000_000L
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
