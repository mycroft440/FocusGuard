package com.focusguard.receiver

import com.focusguard.database.BlockSession
import com.google.common.truth.Truth.assertThat
import java.util.Calendar
import java.util.TimeZone
import org.junit.Test

class BlockingScheduleCalculatorTest {

    private val utc = TimeZone.getTimeZone("UTC")

    @Test
    fun `fixed session schedules its expiration`() {
        val now = instant(2025, 1, 6, 10, 0)
        val end = instant(2025, 1, 6, 11, 0)

        val next = BlockingScheduleCalculator.nextBoundary(
            sessions = listOf(BlockSession(endTime = end, isFixed24h = true)),
            additionalBoundaries = emptyList(),
            nowMillis = now,
            timeZone = utc
        )

        assertThat(next).isEqualTo(end)
    }

    @Test
    fun `recurring session schedules the next allowed start`() {
        val now = instant(2025, 1, 6, 8, 0) // Monday
        val start = instant(2025, 1, 6, 9, 0)
        val session = recurringSession(
            startHour = 9,
            endHour = 17,
            allowedDay = Calendar.MONDAY
        )

        val next = BlockingScheduleCalculator.nextBoundary(
            sessions = listOf(session),
            additionalBoundaries = emptyList(),
            nowMillis = now,
            timeZone = utc
        )

        assertThat(next).isEqualTo(start)
    }

    @Test
    fun `overnight session schedules the end from the previous logical day`() {
        val now = instant(2025, 1, 7, 1, 0) // Tuesday, inside Monday's window
        val end = instant(2025, 1, 7, 6, 0)
        val session = recurringSession(
            startHour = 22,
            endHour = 6,
            allowedDay = Calendar.MONDAY
        )

        val next = BlockingScheduleCalculator.nextBoundary(
            sessions = listOf(session),
            additionalBoundaries = emptyList(),
            nowMillis = now,
            timeZone = utc
        )

        assertThat(next).isEqualTo(end)
    }

    @Test
    fun `disabled weekdays are skipped`() {
        val now = instant(2025, 1, 6, 18, 0) // Monday after the window
        val nextMondayStart = instant(2025, 1, 13, 9, 0)
        val session = recurringSession(
            startHour = 9,
            endHour = 17,
            allowedDay = Calendar.MONDAY
        )

        val next = BlockingScheduleCalculator.nextBoundary(
            sessions = listOf(session),
            additionalBoundaries = emptyList(),
            nowMillis = now,
            timeZone = utc
        )

        assertThat(next).isEqualTo(nextMondayStart)
    }

    @Test
    fun `usage limit expiration wins when it is the next boundary`() {
        val now = instant(2025, 1, 6, 10, 0)
        val limitExpiration = instant(2025, 1, 6, 10, 30)
        val sessionEnd = instant(2025, 1, 6, 11, 0)

        val next = BlockingScheduleCalculator.nextBoundary(
            sessions = listOf(BlockSession(endTime = sessionEnd, isFixed24h = true)),
            additionalBoundaries = listOf(limitExpiration),
            nowMillis = now,
            timeZone = utc
        )

        assertThat(next).isEqualTo(limitExpiration)
    }

    @Test
    fun `daily limits schedule the next local midnight`() {
        val now = instant(2025, 1, 6, 23, 45)

        val next = BlockingScheduleCalculator.nextLocalMidnight(now, utc)

        assertThat(next).isEqualTo(instant(2025, 1, 7, 0, 0))
    }

    @Test
    fun `indefinite fixed session has no time boundary`() {
        val next = BlockingScheduleCalculator.nextBoundary(
            sessions = listOf(BlockSession(endTime = null, isFixed24h = true)),
            additionalBoundaries = emptyList(),
            nowMillis = instant(2025, 1, 6, 10, 0),
            timeZone = utc
        )

        assertThat(next).isNull()
    }

    @Test
    fun `overnight window yields the previous night's tail and tonight's start`() {
        val session = BlockSession(
            isFixed24h = false,
            isRecurring = true,
            recurringStartHour = 21,
            recurringEndHour = 19
        )

        val intervals = BlockingScheduleCalculator.blockingIntervals(
            session = session,
            rangeStartMillis = instant(2025, 1, 6, 0, 0),
            rangeEndMillis = instant(2025, 1, 7, 0, 0),
            timeZone = utc
        )

        assertThat(intervals).containsExactly(
            instant(2025, 1, 6, 0, 0) until instant(2025, 1, 6, 19, 0),
            instant(2025, 1, 6, 21, 0) until instant(2025, 1, 7, 0, 0)
        ).inOrder()
    }

    @Test
    fun `window intervals respect weekdays and the session end`() {
        val session = recurringSession(
            startHour = 9,
            endHour = 17,
            allowedDay = Calendar.MONDAY
        ).copy(endTime = instant(2025, 1, 6, 12, 0))

        val monday = BlockingScheduleCalculator.blockingIntervals(
            session = session,
            rangeStartMillis = instant(2025, 1, 6, 0, 0),
            rangeEndMillis = instant(2025, 1, 7, 0, 0),
            timeZone = utc
        )
        val tuesday = BlockingScheduleCalculator.blockingIntervals(
            session = session.copy(endTime = null),
            rangeStartMillis = instant(2025, 1, 7, 0, 0),
            rangeEndMillis = instant(2025, 1, 8, 0, 0),
            timeZone = utc
        )

        assertThat(monday)
            .containsExactly(instant(2025, 1, 6, 9, 0) until instant(2025, 1, 6, 12, 0))
        assertThat(tuesday).isEmpty()
    }

    @Test
    fun `fixed session blocks from its own start`() {
        val session = BlockSession(
            startTime = instant(2025, 1, 6, 14, 0),
            endTime = instant(2025, 1, 6, 16, 0),
            isFixed24h = true
        )

        val intervals = BlockingScheduleCalculator.blockingIntervals(
            session = session,
            rangeStartMillis = instant(2025, 1, 6, 0, 0),
            rangeEndMillis = instant(2025, 1, 6, 15, 0),
            timeZone = utc
        )

        assertThat(intervals)
            .containsExactly(instant(2025, 1, 6, 14, 0) until instant(2025, 1, 6, 15, 0))
    }

    private fun recurringSession(
        startHour: Int,
        endHour: Int,
        allowedDay: Int
    ) = BlockSession(
        isFixed24h = false,
        isRecurring = true,
        recurringStartHour = startHour,
        recurringEndHour = endHour,
        recurringDaysOfWeek = allowedDay.toString()
    )

    private fun instant(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int
    ): Long = Calendar.getInstance(utc).apply {
        clear()
        set(year, month - 1, day, hour, minute)
    }.timeInMillis
}
