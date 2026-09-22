package com.focusguard.service

import android.view.accessibility.AccessibilityEvent
import com.focusguard.accessibility.website.BrowserSurfaceIdentityRegistry
import com.focusguard.accessibility.website.identification.BrowserObservationSignal

/**
 * Serializes expensive browser accessibility inspections without retaining Android
 * accessibility objects. Only immutable event primitives cross the callback boundary.
 *
 * A generation identifies the foreground browser window. A surface epoch identifies
 * a meaningful document/window-state transition inside that same Accessibility window.
 * A sequence identifies the latest observation inside that epoch. Offering a new
 * snapshot while a worker is active replaces the pending snapshot, so ordinary
 * content-change storms are coalesced without allowing an old document to authorize
 * a newer tab/surface.
 */
internal class BrowserInspectionCoordinator {
    data class Token(
        val packageName: String,
        val windowId: Int,
        val generation: Long,
        val sequence: Long,
        val surfaceEpoch: Long = 0L
    ) {
        // Once an inspection has been freshly validated against the live root, async
        // follow-up work may survive later observations from the same surface epoch.
        // Window/package/surface changes still invalidate it unconditionally.
        @Volatile internal var allowSequenceAdvance: Boolean = false
            private set

        internal fun permitSequenceAdvance() {
            allowSequenceAdvance = true
        }

        internal fun revokeSequenceAdvance() {
            allowSequenceAdvance = false
        }
    }

    data class Snapshot(
        val token: Token,
        val eventType: Int,
        val eventUptimeMillis: Long,
        val receivedUptimeMillis: Long,
        val className: String,
        val directText: List<String>,
        val contentDescription: String?
    )

    data class Offer(
        val snapshot: Snapshot,
        val startWorker: Boolean
    )

    private val lock = Any()
    private var generation = 0L
    private var surfaceEpoch = 0L
    private var sequence = 0L
    private var currentPackage = ""
    private var currentWindowId = INVALID_WINDOW_ID
    private var pending: Snapshot? = null
    private var running: Snapshot? = null
    private var workerActive = false

    fun offer(
        packageName: String,
        windowId: Int,
        eventType: Int,
        eventUptimeMillis: Long,
        receivedUptimeMillis: Long,
        className: String,
        directText: List<String>,
        contentDescription: String?
    ): Offer = synchronized(lock) {
        val windowChanged = packageName != currentPackage || windowId != currentWindowId
        if (windowChanged) {
            BrowserObservationSignal.forget(currentPackage, currentWindowId)
            BrowserSurfaceIdentityRegistry.forget(currentPackage, currentWindowId)
            generation += 1L
            surfaceEpoch += 1L
            currentPackage = packageName
            currentWindowId = windowId
        } else if (isSurfaceBoundaryEvent(eventType)) {
            // Window-state transitions are sparse, semantic boundaries rather than
            // ordinary content churn. An already-running read may finish, but its
            // token can no longer authorize effects on the new surface.
            surfaceEpoch += 1L
            running?.token?.revokeSequenceAdvance()
        }
        sequence += 1L
        BrowserSurfaceIdentityRegistry.publish(
            packageName = packageName,
            windowId = windowId,
            generation = generation,
            surfaceEpoch = surfaceEpoch
        )
        val snapshot = Snapshot(
            token = Token(
                packageName = packageName,
                windowId = windowId,
                generation = generation,
                sequence = sequence,
                surfaceEpoch = surfaceEpoch
            ),
            eventType = eventType,
            eventUptimeMillis = eventUptimeMillis,
            receivedUptimeMillis = receivedUptimeMillis,
            className = className,
            directText = directText.toList(),
            contentDescription = contentDescription
        )
        pending = snapshot
        BrowserObservationSignal.markObserved(packageName, windowId)
        val start = !workerActive
        if (start) workerActive = true
        Offer(snapshot, start)
    }

    /** Advances the window generation even when no browser inspection is scheduled. */
    fun observeWindow(packageName: String, windowId: Int): Long = synchronized(lock) {
        if (packageName != currentPackage || windowId != currentWindowId) {
            BrowserObservationSignal.forget(currentPackage, currentWindowId)
            BrowserSurfaceIdentityRegistry.forget(currentPackage, currentWindowId)
            generation += 1L
            surfaceEpoch += 1L
            currentPackage = packageName
            currentWindowId = windowId
            pending = null
            running?.token?.revokeSequenceAdvance()
        }
        BrowserSurfaceIdentityRegistry.publish(
            packageName = currentPackage,
            windowId = currentWindowId,
            generation = generation,
            surfaceEpoch = surfaceEpoch
        )
        generation
    }

    fun takePending(): Snapshot? = synchronized(lock) {
        pending.also {
            pending = null
            running = it
            // A running pass owns the live package/window/surface epoch, not one quiet
            // sequence number. Let same-surface event storms coalesce behind it instead
            // of invalidating the first inspection before it can publish a result.
            it?.token?.permitSequenceAdvance()
        }
    }

    /**
     * Returns the newest coalesced snapshot, or marks the serial worker idle.
     *
     * A completed pass has already re-read and validated the live browser root. Async
     * recovery/destination confirmation started from that pass may therefore accept a
     * newer sequence in the same surface epoch. A later surface boundary still rejects it.
     */
    fun finishPass(): Snapshot? = synchronized(lock) {
        running?.token?.permitSequenceAdvance()
        pending.also {
            pending = null
            running = it
            if (it == null) workerActive = false
            else it.token.permitSequenceAdvance()
        }
    }

    fun isCurrent(token: Token, requireLatestSequence: Boolean = false): Boolean =
        synchronized(lock) {
            token.packageName == currentPackage &&
                token.windowId == currentWindowId &&
                token.generation == generation &&
                token.surfaceEpoch == surfaceEpoch &&
                (!requireLatestSequence || token.sequence == sequence ||
                    (token.allowSequenceAdvance && token.sequence <= sequence))
        }

    fun isCurrentWindow(packageName: String, windowId: Int, expectedGeneration: Long): Boolean =
        synchronized(lock) {
            packageName == currentPackage &&
                windowId == currentWindowId &&
                expectedGeneration == generation
        }

    fun currentToken(packageName: String): Token? = synchronized(lock) {
        if (packageName != currentPackage || currentWindowId < 0) null
        else Token(
            packageName = currentPackage,
            windowId = currentWindowId,
            generation = generation,
            sequence = sequence,
            surfaceEpoch = surfaceEpoch
        )
    }

    fun currentGeneration(packageName: String, windowId: Int): Long? = synchronized(lock) {
        generation.takeIf { packageName == currentPackage && windowId == currentWindowId }
    }

    fun invalidate() = synchronized(lock) {
        BrowserObservationSignal.forget(currentPackage, currentWindowId)
        BrowserSurfaceIdentityRegistry.forget(currentPackage, currentWindowId)
        generation += 1L
        surfaceEpoch += 1L
        currentPackage = ""
        currentWindowId = INVALID_WINDOW_ID
        pending = null
        running?.token?.revokeSequenceAdvance()
        running = null
    }

    private fun isSurfaceBoundaryEvent(eventType: Int): Boolean =
        eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED

    internal companion object {
        const val INVALID_WINDOW_ID = -1
    }
}
