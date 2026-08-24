package com.focusguard.service

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CurtainFrameCommitTelemetryTest {
    @Test
    fun sampleReportsEventAndCurtainToFrameDurations() {
        val sample = CurtainFrameCommitTelemetry.sample(
            eventDetectedAtNanos = 1_000_000L,
            curtainReadyAtNanos = 1_500_000L,
            frameCommittedAtNanos = 3_500_000L
        )

        assertThat(sample.eventToFrameMicros).isEqualTo(2_500L)
        assertThat(sample.curtainToFrameMicros).isEqualTo(2_000L)
    }

    @Test
    fun sampleClampsClockOrderingAnomaliesToZero() {
        val sample = CurtainFrameCommitTelemetry.sample(
            eventDetectedAtNanos = 5_000_000L,
            curtainReadyAtNanos = 6_000_000L,
            frameCommittedAtNanos = 4_000_000L
        )

        assertThat(sample.eventToFrameMicros).isEqualTo(0L)
        assertThat(sample.curtainToFrameMicros).isEqualTo(0L)
    }
}
