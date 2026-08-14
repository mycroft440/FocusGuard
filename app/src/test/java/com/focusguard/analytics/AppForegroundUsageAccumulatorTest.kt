package com.focusguard.analytics

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AppForegroundUsageAccumulatorTest {

    @Test
    fun `clips lookback time before requested range`() {
        val accumulator = AppForegroundUsageAccumulator(
            rangeStartMs = 1_000L,
            rangeEndMs = 10_000L,
            isEligibleApp = { true }
        )

        accumulator.onActivityResumed("app.a", "A", 500L)
        accumulator.onActivityPaused("app.a", "A", 4_000L)

        assertThat(accumulator.finish()).containsExactly("app.a", 3_000L)
    }

    @Test
    fun `activity transition inside same package does not double count`() {
        val accumulator = AppForegroundUsageAccumulator(
            rangeStartMs = 0L,
            rangeEndMs = 10_000L,
            isEligibleApp = { true }
        )

        accumulator.onActivityResumed("app.a", "A1", 1_000L)
        accumulator.onActivityPaused("app.a", "A1", 3_000L)
        accumulator.onActivityResumed("app.a", "A2", 3_100L)
        accumulator.onActivityPaused("app.a", "A2", 6_000L)

        assertThat(accumulator.finish()).containsExactly("app.a", 5_000L)
    }

    @Test
    fun `switching packages closes previous app at pending pause`() {
        val accumulator = AppForegroundUsageAccumulator(
            rangeStartMs = 0L,
            rangeEndMs = 10_000L,
            isEligibleApp = { true }
        )

        accumulator.onActivityResumed("app.a", "A", 1_000L)
        accumulator.onActivityPaused("app.a", "A", 4_000L)
        accumulator.onActivityResumed("app.b", "B", 4_500L)
        accumulator.onActivityPaused("app.b", "B", 8_000L)

        assertThat(accumulator.finish()).containsExactly(
            "app.a", 3_000L,
            "app.b", 3_500L
        )
    }

    @Test
    fun `screen off closes active app`() {
        val accumulator = AppForegroundUsageAccumulator(
            rangeStartMs = 0L,
            rangeEndMs = 10_000L,
            isEligibleApp = { true }
        )

        accumulator.onActivityResumed("app.a", "A", 2_000L)
        accumulator.onDeviceBecameInactive(5_500L)

        assertThat(accumulator.finish()).containsExactly("app.a", 3_500L)
    }

    @Test
    fun `non launchable package is ignored`() {
        val accumulator = AppForegroundUsageAccumulator(
            rangeStartMs = 0L,
            rangeEndMs = 10_000L,
            isEligibleApp = { it == "app.allowed" }
        )

        accumulator.onActivityResumed("app.system", "A", 1_000L)
        accumulator.onActivityPaused("app.system", "A", 4_000L)
        accumulator.onActivityResumed("app.allowed", "B", 5_000L)
        accumulator.onActivityPaused("app.allowed", "B", 8_000L)

        assertThat(accumulator.finish()).containsExactly("app.allowed", 3_000L)
    }
}
