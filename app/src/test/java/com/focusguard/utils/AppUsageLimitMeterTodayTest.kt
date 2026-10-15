package com.focusguard.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AppUsageLimitMeterTodayTest {

    @Test
    fun `only the part of each stretch inside today counts`() {
        val total = AppUsageLimitMeter.millisWithin(
            intervals = listOf(50L until 150L, 300L until 400L),
            fromMillis = 100L,
            untilMillis = 350L
        )

        assertThat(total).isEqualTo(100L)
    }

    @Test
    fun `usage events reach the collector with the same rules as the limits`() {
        val collector = ForegroundIntervalCollector()
        collector.onUsageEvent(1, "a", "Main", 1_000L) // ACTIVITY_RESUMED
        collector.onUsageEvent(2, "a", "Main", 3_000L) // ACTIVITY_PAUSED
        collector.onUsageEvent(1, "b", "Main", 3_500L)
        collector.onUsageEvent(16, null, null, 5_000L) // SCREEN_NON_INTERACTIVE

        assertThat(collector.packagesSeen()).containsExactly("a", "b")
        assertThat(collector.intervalsFor("a", 9_000L, true)).containsExactly(1_000L until 3_000L)
        assertThat(collector.intervalsFor("b", 9_000L, true)).containsExactly(3_500L until 5_000L)
    }
}
