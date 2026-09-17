package com.focusguard.service

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
    )

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
        val start = !workerActive
        if (start) workerActive = true
        Offer(snapshot, start)
    }

    /** Advances the window generation even when no browser inspection is scheduled. */
    fun observeWindow(packageName: String, windowId: Int): Long = synchronized(lock) {
        if (packageName != currentPackage || windowId != currentWindowId) {
            generation += 1L
            currentPackage = packageName
            currentWindowId = windowId
            pending = null
        }
        generation
    }

    fun takePending(): Snapshot? = synchronized(lock) {
        pending.also { pending = null }
    }

    /** Returns the newest coalesced snapshot, or marks the serial worker idle. */
    fun finishPass(): Snapshot? = synchronized(lock) {
        pending.also {
            pending = null
            if (it == null) workerActive = false
        }
    }

    fun isCurrent(token: Token, requireLatestSequence: Boolean = false): Boolean =
        synchronized(lock) {
            token.packageName == currentPackage &&
                token.windowId == currentWindowId &&
                token.generation == generation &&
                (!requireLatestSequence || token.sequence == sequence)
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
        generation += 1L
        currentPackage = ""
        currentWindowId = INVALID_WINDOW_ID
        pending = null
    }

    internal companion object {
        const val INVALID_WINDOW_ID = -1
    }
}
