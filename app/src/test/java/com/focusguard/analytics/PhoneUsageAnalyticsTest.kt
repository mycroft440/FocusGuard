package com.focusguard.analytics

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

class PhoneUsageSessionAccumulatorTest {

    @Test
    fun `resume and pause create one foreground interval`() {
        val accumulator = accumulator()

        accumulator.onActivityResumed(APP_A, "MainActivity", 1_000L)
        accumulator.onActivityPaused(APP_A, "MainActivity", 4_000L)

        assertThat(accumulator.finish(5_000L)).containsExactly(
            ForegroundUsageInterval(APP_A, 1_000L, 4_000L)
        )
    }

    @Test
    fun `app still visible is counted until query end`() {
        val accumulator = accumulator()

        accumulator.onActivityResumed(APP_A, "MainActivity", 1_000L)

        assertThat(accumulator.finish(5_000L)).containsExactly(
            ForegroundUsageInterval(APP_A, 1_000L, 5_000L)
        )
    }

    @Test
    fun `activity transition inside same app remains one continuous session`() {
        val accumulator = accumulator()

        accumulator.onActivityResumed(APP_A, "MainActivity", 1_000L)
        accumulator.onActivityPaused(APP_A, "MainActivity", 2_000L)
        accumulator.onActivityResumed(APP_A, "DetailsActivity", 2_100L)
        accumulator.onActivityPaused(APP_A, "DetailsActivity", 4_000L)

        assertThat(accumulator.finish(5_000L)).containsExactly(
            ForegroundUsageInterval(APP_A, 1_000L, 4_000L)
        )
    }

    @Test
    fun `app switch ends previous session at its pause`() {
        val accumulator = accumulator()

        accumulator.onActivityResumed(APP_A, "MainActivity", 1_000L)
        accumulator.onActivityPaused(APP_A, "MainActivity", 3_000L)
        accumulator.onActivityResumed(APP_B, "MainActivity", 4_000L)
        accumulator.onActivityPaused(APP_B, "MainActivity", 5_000L)

        assertThat(accumulator.finish(6_000L)).containsExactly(
            ForegroundUsageInterval(APP_A, 1_000L, 3_000L),
            ForegroundUsageInterval(APP_B, 4_000L, 5_000L)
        ).inOrder()
    }

    @Test
    fun `late pause from old activity does not end visible activity`() {
        val accumulator = accumulator()

        accumulator.onActivityResumed(APP_A, "MainActivity", 1_000L)
        accumulator.onActivityResumed(APP_A, "DetailsActivity", 2_000L)
        accumulator.onActivityPaused(APP_A, "MainActivity", 3_000L)
        accumulator.onActivityPaused(APP_A, "DetailsActivity", 4_000L)

        assertThat(accumulator.finish(5_000L)).containsExactly(
            ForegroundUsageInterval(APP_A, 1_000L, 4_000L)
        )
    }

    @Test
    fun `screen off ends current usage`() {
        val accumulator = accumulator()

        accumulator.onActivityResumed(APP_A, "MainActivity", 1_000L)
        accumulator.onDeviceBecameInactive(3_500L)

        assertThat(accumulator.finish(5_000L)).containsExactly(
            ForegroundUsageInterval(APP_A, 1_000L, 3_500L)
        )
    }

    @Test
    fun `ineligible package ends user app but is not counted`() {
        val accumulator = PhoneUsageSessionAccumulator { it != SYSTEM_UI }

        accumulator.onActivityResumed(APP_A, "MainActivity", 1_000L)
        accumulator.onActivityResumed(SYSTEM_UI, "SystemUiActivity", 3_000L)

        assertThat(accumulator.finish(5_000L)).containsExactly(
            ForegroundUsageInterval(APP_A, 1_000L, 3_000L)
        )
    }

    private fun accumulator() = PhoneUsageSessionAccumulator { true }

    private companion object {
        const val APP_A = "com.example.alpha"
        const val APP_B = "com.example.beta"
        const val SYSTEM_UI = "com.android.systemui"
    }
}

class PhoneUsageInsightsCalculatorTest {
    private val zoneId = ZoneId.of("UTC")
    private val locale = Locale.US

    @Test
    fun `history contains zero days and splits usage across midnight`() {
        val insights = calculate(
            intervals = listOf(
                interval("2026-01-06T23:30", "2026-01-07T00:30")
            ),
            now = "2026-01-08T12:00",
            historyDays = 3
        )

        assertThat(insights.dailyHistory.map { it.totalTimeMs }).containsExactly(
            30.minutes,
            30.minutes,
            0L
        ).inOrder()
        assertThat(insights.dailyHistory.map { it.dateLabel }).containsExactly(
            "TUE",
            "WED",
            "THU"
        ).inOrder()
    }

    @Test
    fun `period average splits an interval at a three hour boundary`() {
        val insights = calculate(
            intervals = listOf(
                interval("2026-01-01T02:30", "2026-01-01T03:30"),
                interval("2026-01-01T04:00", "2026-01-01T05:00")
            ),
            now = "2026-01-08T12:00"
        )

        assertThat(insights.periodSummary?.mostUsed).isEqualTo(
            PhoneUsagePeriodAverage(
                startHour = 3,
                endHour = 6,
                averageTimeMs = 90.minutes / 7
            )
        )
        assertThat(insights.periodSummary?.leastUsed).isEqualTo(
            PhoneUsagePeriodAverage(
                startHour = 6,
                endHour = 9,
                averageTimeMs = 0L
            )
        )
    }

    @Test
    fun `current incomplete day does not influence period averages`() {
        val insights = calculate(
            intervals = listOf(
                interval("2026-01-07T18:00", "2026-01-07T20:00"),
                interval("2026-01-08T00:00", "2026-01-08T10:00")
            ),
            now = "2026-01-08T12:00"
        )

        assertThat(insights.periodSummary?.mostUsed?.startHour).isEqualTo(18)
        assertThat(insights.periodSummary?.mostUsed?.averageTimeMs)
            .isEqualTo(2.hours / 7)
    }

    @Test
    fun `monthly period analysis uses thirty complete days`() {
        val insights = calculate(
            intervals = listOf(
                interval("2026-01-02T04:00", "2026-01-02T05:00")
            ),
            now = "2026-02-01T12:00",
            periodAverageDays = PHONE_USAGE_PERIOD_ANALYSIS_DAYS
        )

        assertThat(PHONE_USAGE_PERIOD_ANALYSIS_DAYS).isEqualTo(30)
        assertThat(insights.periodSummary?.daysAnalyzed).isEqualTo(30)
        assertThat(insights.periodSummary?.mostUsed).isEqualTo(
            PhoneUsagePeriodAverage(
                startHour = 3,
                endHour = 6,
                averageTimeMs = 1.hours / 30
            )
        )
    }

    @Test
    fun `session beginning before history is clipped to first displayed day`() {
        val insights = calculate(
            intervals = listOf(
                interval("2026-01-05T23:00", "2026-01-06T01:00")
            ),
            now = "2026-01-08T12:00",
            historyDays = 3
        )

        assertThat(insights.dailyHistory.first().totalTimeMs).isEqualTo(1.hours)
    }

    @Test
    fun `no usage in complete days returns no period summary`() {
        val insights = calculate(
            intervals = listOf(
                interval("2026-01-08T08:00", "2026-01-08T09:00")
            ),
            now = "2026-01-08T12:00"
        )

        assertThat(insights.periodSummary).isNull()
    }

    private fun calculate(
        intervals: List<ForegroundUsageInterval>,
        now: String,
        historyDays: Int = 7,
        periodAverageDays: Int = 7
    ) = PhoneUsageInsightsCalculator.calculate(
        intervals = intervals,
        nowMs = timestamp(now),
        historyDays = historyDays,
        periodAverageDays = periodAverageDays,
        zoneId = zoneId,
        locale = locale
    )

    private fun interval(start: String, end: String) = ForegroundUsageInterval(
        packageName = "com.example.app",
        startTimeMs = timestamp(start),
        endTimeMs = timestamp(end)
    )

    private fun timestamp(value: String): Long =
        LocalDateTime.parse(value).atZone(zoneId).toInstant().toEpochMilli()

    private val Int.minutes: Long
        get() = this * 60_000L

    private val Int.hours: Long
        get() = this * 60L * 60_000L
}
