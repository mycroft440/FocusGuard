package com.focusguard.service

import android.os.Build
import android.os.SystemClock
import android.view.View

/** Measures the first submitted frame after the instant curtain is requested. */
internal object CurtainFrameCommitTelemetry {
    internal data class Sample(
        val eventToFrameMicros: Long,
        val curtainToFrameMicros: Long
    )

    fun register(
        curtain: View?,
        generation: Long,
        currentGeneration: () -> Long,
        eventDetectedAtNanos: Long,
        curtainReadyAtNanos: Long,
        onCommitted: (Sample) -> Unit
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || curtain == null) return false
        val observer = curtain.viewTreeObserver
        if (!observer.isAlive) return false

        return runCatching {
            observer.registerFrameCommitCallback {
                if (generation == currentGeneration()) {
                    onCommitted(
                        sample(
                            eventDetectedAtNanos = eventDetectedAtNanos,
                            curtainReadyAtNanos = curtainReadyAtNanos,
                            frameCommittedAtNanos = SystemClock.elapsedRealtimeNanos()
                        )
                    )
                }
            }
            // Force only a rendering pass; telemetry must never alter layout or blocking state.
            curtain.postInvalidateOnAnimation()
            true
        }.getOrDefault(false)
    }

    internal fun sample(
        eventDetectedAtNanos: Long,
        curtainReadyAtNanos: Long,
        frameCommittedAtNanos: Long
    ): Sample = Sample(
        eventToFrameMicros =
            (frameCommittedAtNanos - eventDetectedAtNanos).coerceAtLeast(0L) / 1_000L,
        curtainToFrameMicros =
            (frameCommittedAtNanos - curtainReadyAtNanos).coerceAtLeast(0L) / 1_000L
    )
}
