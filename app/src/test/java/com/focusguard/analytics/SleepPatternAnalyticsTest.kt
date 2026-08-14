package com.focusguard.analytics

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class SleepPatternEstimatorTest {
    private val zoneId = ZoneId.of("UTC")

    @Test
    fun `three consistent nights produce a medium confidence sleep estimate`() {
        val observations = SleepPatternEstimator.extractObservations(
            intervals = regularScreenIntervals(5..8),
            completeDates = (5..8).map(::januaryDate),
            zoneId = zoneId
        )

        val estimate = SleepPatternEstimator.estimate(observations)

        assertThat(observations).hasSize(3)
        assertThat(estimate).isEqualTo(
            EstimatedSleepWindow(
                bedtimeMinuteOfDay = 23 * 60 + 5,
                wakeMinuteOfDay = 7 * 60,
                averageDurationMs = 7.hours + 55.minutes,
                confidence = SleepEstimateConfidence.MEDIUM,
                confidenceScore = 74,
                nightsAnalyzed = 3
            )
        )
    }

    @Test
    fun `brief midnight screen check is treated as an interruption`() {
        val observations = SleepPatternEstimator.extractObservations(
            intervals = regularScreenIntervals(5..8) + interval(
                "2026-01-07T03:00",
                "2026-01-07T03:03"
            ),
            completeDates = (5..8).map(::januaryDate),
            zoneId = zoneId
        )

        val interruptedNight = observations.single {
            it.nightDate == LocalDate.parse("2026-01-06")
        }

        assertThat(interruptedNight.bedtimeMinuteOfDay).isEqualTo(23 * 60 + 5)
        assertThat(interruptedNight.wakeMinuteOfDay).isEqualTo(7 * 60)
        assertThat(interruptedNight.interruptionCount).isEqualTo(1)
        assertThat(interruptedNight.interruptionDurationMs).isEqualTo(3.minutes)
    }

    @Test
    fun `long midnight usage is not silently merged into sleep`() {
        val observations = SleepPatternEstimator.extractObservations(
            intervals = regularScreenIntervals(5..6) + interval(
                "2026-01-06T03:00",
                "2026-01-06T03:30"
            ),
            completeDates = (5..6).map(::januaryDate),
            zoneId = zoneId
        )

        assertThat(observations).isEmpty()
    }

    @Test
    fun `brief evening sessions are not mistaken for an early bedtime`() {
        val observations = SleepPatternEstimator.extractObservations(
            intervals = regularScreenIntervals(5..6) + listOf(
                interval("2026-01-05T20:00", "2026-01-05T20:02"),
                interval("2026-01-05T22:00", "2026-01-05T22:02")
            ),
            completeDates = (5..6).map(::januaryDate),
            zoneId = zoneId
        )

        assertThat(observations.single().bedtimeMinuteOfDay).isEqualTo(23 * 60 + 5)
        assertThat(observations.single().wakeMinuteOfDay).isEqualTo(7 * 60)
    }

    @Test
    fun `fewer than three nights are not presented as a learned pattern`() {
        val observations = SleepPatternEstimator.extractObservations(
            intervals = regularScreenIntervals(5..7),
            completeDates = (5..7).map(::januaryDate),
            zoneId = zoneId
        )

        assertThat(observations).hasSize(2)
        assertThat(SleepPatternEstimator.estimate(observations)).isNull()
    }

    @Test
    fun `seven consistent nights produce high confidence`() {
        val observations = SleepPatternEstimator.extractObservations(
            intervals = regularScreenIntervals(1..8),
            completeDates = (1..8).map(::januaryDate),
            zoneId = zoneId
        )

        val estimate = SleepPatternEstimator.estimate(observations)

        assertThat(estimate?.confidence).isEqualTo(SleepEstimateConfidence.HIGH)
        assertThat(estimate?.confidenceScore).isEqualTo(100)
        assertThat(estimate?.nightsAnalyzed).isEqualTo(7)
    }

    private fun regularScreenIntervals(days: IntRange): List<ScreenInteractiveInterval> =
        days.flatMap { day ->
            listOf(
                interval(
                    "2026-01-%02dT07:00".format(day),
                    "2026-01-%02dT07:05".format(day)
                ),
                interval(
                    "2026-01-%02dT23:00".format(day),
                    "2026-01-%02dT23:05".format(day)
                )
            )
        }

    private fun interval(start: String, end: String) = ScreenInteractiveInterval(
        startTimeMs = timestamp(start),
        endTimeMs = timestamp(end)
    )

    private fun januaryDate(day: Int): LocalDate =
        LocalDate.parse("2026-01-%02d".format(day))

    private fun timestamp(value: String): Long =
        LocalDateTime.parse(value).atZone(zoneId).toInstant().toEpochMilli()

    private val Int.minutes: Long
        get() = this * 60_000L

    private val Int.hours: Long
        get() = this * 60L * 60_000L
}
