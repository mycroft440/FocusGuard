package com.focusguard.analytics

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

class ScreenInteractiveSessionAccumulatorTest {

    @Test
    fun `interactive and non interactive events create a screen interval`() {
        val accumulator = ScreenInteractiveSessionAccumulator()

        accumulator.onScreenInteractive(1_000L)
        accumulator.onScreenNonInteractive(4_000L)

        assertThat(accumulator.finish(5_000L)).containsExactly(
            ScreenInteractiveInterval(1_000L, 4_000L)
        )
    }

    @Test
    fun `screen still interactive is counted until query end`() {
        val accumulator = ScreenInteractiveSessionAccumulator()

        accumulator.onScreenInteractive(1_000L)

        assertThat(accumulator.activeIntervalStartTimeMs).isEqualTo(1_000L)
        assertThat(accumulator.finish(5_000L)).containsExactly(
            ScreenInteractiveInterval(1_000L, 5_000L)
        )
    }

    @Test
    fun `duplicate interactive event does not reset the interval`() {
        val accumulator = ScreenInteractiveSessionAccumulator()

        accumulator.onScreenInteractive(1_000L)
        accumulator.onScreenInteractive(2_000L)
        accumulator.onScreenNonInteractive(4_000L)

        assertThat(accumulator.finish(5_000L)).containsExactly(
            ScreenInteractiveInterval(1_000L, 4_000L)
        )
    }

    @Test
    fun `non interactive event without a known start invents no usage`() {
        val accumulator = ScreenInteractiveSessionAccumulator()

        accumulator.onScreenNonInteractive(2_000L)

        assertThat(accumulator.finish(5_000L)).isEmpty()
        assertThat(accumulator.firstObservedEventTimeMs).isEqualTo(2_000L)
    }
}

class DetailedEventCoverageTest {
    private val zoneId = ZoneId.of("UTC")

    @Test
    fun `first partially retained day is excluded from detailed averages`() {
        val dates = completeDatesCoveredByDetailedEvents(
            firstObservedEventTimeMs = timestamp("2026-01-04T15:00"),
            lastObservedEventTimeMs = timestamp("2026-01-08T12:00"),
            eventLoopCompleted = true,
            periodStartDate = LocalDate.parse("2025-12-10"),
            today = LocalDate.parse("2026-01-08"),
            zoneId = zoneId
        )

        assertThat(dates).containsExactly(
            LocalDate.parse("2026-01-05"),
            LocalDate.parse("2026-01-06"),
            LocalDate.parse("2026-01-07")
        ).inOrder()
    }

    @Test
    fun `interrupted event iteration excludes the unfinished final day`() {
        val dates = completeDatesCoveredByDetailedEvents(
            firstObservedEventTimeMs = timestamp("2026-01-03T15:00"),
            lastObservedEventTimeMs = timestamp("2026-01-07T10:00"),
            eventLoopCompleted = false,
            periodStartDate = LocalDate.parse("2025-12-10"),
            today = LocalDate.parse("2026-01-08"),
            zoneId = zoneId
        )

        assertThat(dates).containsExactly(
            LocalDate.parse("2026-01-04"),
            LocalDate.parse("2026-01-05"),
            LocalDate.parse("2026-01-06")
        ).inOrder()
    }

    private fun timestamp(value: String): Long =
        LocalDateTime.parse(value).atZone(zoneId).toInstant().toEpochMilli()
}

class DailyScreenTimeResolutionTest {

    @Test
    fun `current active tail is added to completed aggregated time`() {
        val result = resolveDailyScreenTime(
            aggregatedTotalMs = 1.hours,
            detailedIntervals = emptyList(),
            rangeStartMs = 0L,
            rangeEndMs = 4.hours,
            activeIntervalStartTimeMs = 3.hours,
            includeActiveTail = true
        )

        assertThat(result).isEqualTo(2.hours)
    }

    @Test
    fun `missing aggregate falls back to detailed screen intervals`() {
        val result = resolveDailyScreenTime(
            aggregatedTotalMs = null,
            detailedIntervals = listOf(
                ScreenInteractiveInterval(1.hours, 2.hours),
                ScreenInteractiveInterval(3.hours, 5.hours)
            ),
            rangeStartMs = 0L,
            rangeEndMs = 4.hours,
            activeIntervalStartTimeMs = null,
            includeActiveTail = false
        )

        assertThat(result).isEqualTo(2.hours)
    }

    private val Int.hours: Long
        get() = this * 60L * 60_000L
}

class PhoneUsageInsightsCalculatorTest {
    private val zoneId = ZoneId.of("UTC")
    private val locale = Locale.US

    @Test
    fun `aggregated daily history remains correct without retained detailed events`() {
        val insights = calculate(
            dailyScreenTimeByDate = mapOf(
                date("2026-01-06") to 5.hours,
                date("2026-01-07") to 2.hours,
                date("2026-01-08") to 1.hours
            ),
            now = "2026-01-08T12:00",
            historyDays = 3
        )

        assertThat(insights.dailyHistory.map { it.totalTimeMs }).containsExactly(
            5.hours,
            2.hours,
            1.hours
        ).inOrder()
        assertThat(insights.dailyHistory.map { it.dateLabel }).containsExactly(
            "TUE",
            "WED",
            "THU"
        ).inOrder()
        assertThat(insights.periodSummary).isNull()
    }

    @Test
    fun `daily average uses complete days and excludes today`() {
        val insights = calculate(
            dailyScreenTimeByDate = mapOf(
                date("2026-01-06") to 4.hours,
                date("2026-01-07") to 2.hours,
                date("2026-01-08") to 30.minutes
            ),
            now = "2026-01-08T12:00",
            historyDays = 3
        )

        assertThat(insights.completeDaysAverageMs).isEqualTo(3.hours)
        assertThat(insights.completeDaysAnalyzed).isEqualTo(2)
    }

    @Test
    fun `busiest usage is calculated in one hour ranges`() {
        val insights = calculate(
            detailedIntervals = (5..7).flatMap { day ->
                listOf(
                    interval("2026-01-%02dT09:00".format(day), "2026-01-%02dT09:15".format(day)),
                    interval("2026-01-%02dT14:00".format(day), "2026-01-%02dT15:00".format(day))
                )
            },
            completePeriodDates = (5..7).map { date("2026-01-%02d".format(it)) },
            now = "2026-01-08T12:00"
        )

        assertThat(insights.periodSummary?.mostUsed).isEqualTo(
            PhoneUsagePeriodAverage(
                startHour = 14,
                endHour = 15,
                averageTimeMs = 1.hours
            )
        )
    }

    @Test
    fun `period average divides by available complete days instead of thirty`() {
        val insights = calculate(
            detailedIntervals = listOf(
                interval("2026-01-07T04:00", "2026-01-07T05:00")
            ),
            completePeriodDates = listOf(
                date("2026-01-05"),
                date("2026-01-06"),
                date("2026-01-07")
            ),
            now = "2026-01-08T12:00"
        )

        assertThat(insights.periodSummary?.daysAnalyzed).isEqualTo(3)
        assertThat(insights.periodSummary?.mostUsed).isEqualTo(
            PhoneUsagePeriodAverage(
                startHour = 4,
                endHour = 5,
                averageTimeMs = 1.hours / 3
            )
        )
    }

    @Test
    fun `quietest continuous window is joined across midnight`() {
        val insights = calculate(
            detailedIntervals = (5..7).map { day ->
                interval(
                    "2026-01-%02dT06:00".format(day),
                    "2026-01-%02dT21:00".format(day)
                )
            },
            completePeriodDates = (5..7).map { date("2026-01-%02d".format(it)) },
            now = "2026-01-08T12:00"
        )

        assertThat(insights.periodSummary?.leastUsed).isEqualTo(
            PhoneUsagePeriodAverage(
                startHour = 21,
                endHour = 6,
                averageTimeMs = 0L
            )
        )
    }

    @Test
    fun `usage pattern waits for three complete days`() {
        val insights = calculate(
            detailedIntervals = listOf(
                interval("2026-01-06T14:00", "2026-01-06T15:00"),
                interval("2026-01-07T14:00", "2026-01-07T15:00")
            ),
            completePeriodDates = listOf(
                date("2026-01-06"),
                date("2026-01-07")
            ),
            now = "2026-01-08T12:00"
        )

        assertThat(insights.periodSummary).isNull()
    }

    @Test
    fun `current incomplete day does not influence period averages`() {
        val insights = calculate(
            detailedIntervals = listOf(
                interval("2026-01-07T18:00", "2026-01-07T20:00"),
                interval("2026-01-08T00:00", "2026-01-08T10:00")
            ),
            completePeriodDates = listOf(
                date("2026-01-05"),
                date("2026-01-06"),
                date("2026-01-07"),
                date("2026-01-08")
            ),
            now = "2026-01-08T12:00"
        )

        assertThat(insights.periodSummary?.mostUsed?.startHour).isEqualTo(18)
        assertThat(insights.periodSummary?.mostUsed?.endHour).isEqualTo(19)
        assertThat(insights.periodSummary?.mostUsed?.averageTimeMs).isEqualTo(1.hours / 3)
        assertThat(insights.periodSummary?.daysAnalyzed).isEqualTo(3)
    }

    @Test
    fun `impossible daily total is clamped to elapsed day duration`() {
        val insights = calculate(
            dailyScreenTimeByDate = mapOf(date("2026-01-08") to 30.hours),
            now = "2026-01-08T12:00",
            historyDays = 1
        )

        assertThat(insights.dailyHistory.single().totalTimeMs).isEqualTo(12.hours)
        assertThat(insights.completeDaysAnalyzed).isEqualTo(0)
    }

    @Test
    fun `no detailed usage in complete days returns no period summary`() {
        val insights = calculate(
            dailyScreenTimeByDate = mapOf(date("2026-01-08") to 1.hours),
            now = "2026-01-08T12:00"
        )

        assertThat(insights.periodSummary).isNull()
    }

    private fun calculate(
        dailyScreenTimeByDate: Map<LocalDate, Long> = emptyMap(),
        detailedIntervals: List<ScreenInteractiveInterval> = emptyList(),
        completePeriodDates: List<LocalDate> = emptyList(),
        now: String,
        historyDays: Int = 7
    ) = PhoneUsageInsightsCalculator.calculate(
        dailyScreenTimeByDate = dailyScreenTimeByDate,
        detailedIntervals = detailedIntervals,
        completePeriodDates = completePeriodDates,
        nowMs = timestamp(now),
        historyDays = historyDays,
        zoneId = zoneId,
        locale = locale
    )

    private fun interval(start: String, end: String) = ScreenInteractiveInterval(
        startTimeMs = timestamp(start),
        endTimeMs = timestamp(end)
    )

    private fun date(value: String): LocalDate = LocalDate.parse(value)

    private fun timestamp(value: String): Long =
        LocalDateTime.parse(value).atZone(zoneId).toInstant().toEpochMilli()

    private val Int.minutes: Long
        get() = this * 60_000L

    private val Int.hours: Long
        get() = this * 60L * 60_000L
}
