package com.focusguard.security

import android.os.Trace
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Lock-free bounded hand-off for Accessibility latency samples. The callback only
 * enqueues; formatting/logging is intentionally performed later by a coroutine.
 */
object AccessibilityHotPathTelemetry {
    data class Sample(
        val generation: Long,
        val eventReceivedNanos: Long,
        val decisionStartNanos: Long,
        val decisionCompleteNanos: Long,
        val sourceRequestedNanos: Long = 0L,
        val sourceReturnedNanos: Long = 0L,
        val treeFallbackStartNanos: Long = 0L,
        val treeFallbackEndNanos: Long = 0L,
        val overlayRequestedNanos: Long = 0L,
        val overlayUpdatedNanos: Long = 0L,
        val overlayFrameCommittedNanos: Long = 0L,
        val homeCallStartNanos: Long = 0L,
        val homeCallReturnedNanos: Long = 0L,
        val gateRequestedNanos: Long = 0L
    )

    class Attempt(
        val generation: Long,
        val eventReceivedNanos: Long
    ) {
        var decisionStartNanos: Long = eventReceivedNanos
        var decisionCompleteNanos: Long = 0L
        var sourceRequestedNanos: Long = 0L
        var sourceReturnedNanos: Long = 0L
        var treeFallbackStartNanos: Long = 0L
        var treeFallbackEndNanos: Long = 0L
        var overlayRequestedNanos: Long = 0L
        var overlayUpdatedNanos: Long = 0L
        var overlayFrameCommittedNanos: Long = 0L
        var homeCallStartNanos: Long = 0L
        var homeCallReturnedNanos: Long = 0L
        var gateRequestedNanos: Long = 0L

        fun snapshot(): Sample = Sample(
            generation = generation,
            eventReceivedNanos = eventReceivedNanos,
            decisionStartNanos = decisionStartNanos,
            decisionCompleteNanos = decisionCompleteNanos,
            sourceRequestedNanos = sourceRequestedNanos,
            sourceReturnedNanos = sourceReturnedNanos,
            treeFallbackStartNanos = treeFallbackStartNanos,
            treeFallbackEndNanos = treeFallbackEndNanos,
            overlayRequestedNanos = overlayRequestedNanos,
            overlayUpdatedNanos = overlayUpdatedNanos,
            overlayFrameCommittedNanos = overlayFrameCommittedNanos,
            homeCallStartNanos = homeCallStartNanos,
            homeCallReturnedNanos = homeCallReturnedNanos,
            gateRequestedNanos = gateRequestedNanos
        )
    }

    private const val MAX_PENDING = 128
    private val queue = ConcurrentLinkedQueue<Sample>()
    private val count = AtomicInteger(0)

    fun record(sample: Sample) {
        queue.offer(sample)
        val current = count.incrementAndGet()
        if (current > MAX_PENDING) {
            if (queue.poll() != null) count.decrementAndGet()
        }
    }

    fun drain(max: Int = MAX_PENDING): List<Sample> {
        val result = ArrayList<Sample>(minOf(max, count.get().coerceAtLeast(0)))
        repeat(max) {
            val sample = queue.poll() ?: return result
            count.decrementAndGet()
            result += sample
        }
        return result
    }

    fun <T> trace(section: String, block: () -> T): T {
        Trace.beginSection(section)
        return try {
            block()
        } finally {
            Trace.endSection()
        }
    }

    internal fun pendingCountForTest(): Int = count.get()
}
