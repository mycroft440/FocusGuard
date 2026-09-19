package com.focusguard.service

import com.focusguard.accessibility.website.identification.BrowserObservationSignal

/**
 * Serializes expensive browser accessibility inspections without retaining Android
 * accessibility objects. Only immutable event primitives cross the callback boundary.
 *
 * A generation identifies the foreground browser window. A sequence identifies the
 * latest observation inside that generation. Offering a new snapshot while a worker
 * is active replaces the pending snapshot, so content-change storms are coalesced.
 */
internal class BrowserInspectionCoordinator {
    data class Token(
        val packageName: String,
        val windowId: Int,
        val generation: Long,
        val sequence: Long
    ) {
        // Once an inspection has been freshly validated against the live root, async
        // follow-up work may survive later observations from the same window/generation.
        // Window/package/generation changes still invalidate it unconditionally.
        @Volatile internal var allowSequenceAdvance: Boolean = false
            private set

        internal fun permitSequenceAdvance() {
            allowSequenceAdvance = true
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
        if (packageName != currentPackage || windowId != currentWindowId) {
            BrowserObservationSignal.forget(currentPackage, currentWindowId)
            generation += 1L
            currentPackage = packageName
            currentWindowId = windowId
        }
        sequence += 1L
        val snapshot = Snapshot(
            token = Token(packageName, windowId, generation, sequence),
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
            generation += 1L
            currentPackage = packageName
            currentWindowId = windowId
            pending = null
        }
        generation
    }

    fun takePending(): Snapshot? = synchronized(lock) {
        pending.also {
            pending = null
            running = it
            // A running pass owns the live package/window/generation, not one quiet
            // sequence number. Let same-window event storms coalesce behind it instead
            // of invalidating the first inspection before it can publish a result.
            // A real package/window generation change still invalidates the token.
            it?.token?.permitSequenceAdvance()
        }
    }

    /**
     * Returns the newest coalesced snapshot, or marks the serial worker idle.
     *
     * A completed pass has already re-read and validated the live browser root. Async
     * recovery/destination confirmation started from that pass may therefore accept a
     * newer sequence in the same generation instead of requiring a 120 ms quiet gap.
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
        else Token(currentPackage, currentWindowId, generation, sequence)
    }

    fun currentGeneration(packageName: String, windowId: Int): Long? = synchronized(lock) {
        generation.takeIf { packageName == currentPackage && windowId == currentWindowId }
    }

    fun invalidate() = synchronized(lock) {
        BrowserObservationSignal.forget(currentPackage, currentWindowId)
        generation += 1L
        currentPackage = ""
        currentWindowId = INVALID_WINDOW_ID
        pending = null
        running = null
    }

    internal companion object {
        const val INVALID_WINDOW_ID = -1
    }
}
