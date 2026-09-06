package com.focusguard.receiver

import com.focusguard.database.BlockSession
import com.google.common.truth.Truth.assertThat
import java.util.Calendar
import java.util.TimeZone
import org.junit.Test

/** Regression coverage for the Dopamine Fast 00:00 -> 24:00 weekday schedule. */
class DopamineFullDayScheduleTest {

    private val utc = TimeZone.getTimeZone("UTC")

    @Test
    fun `selected full day ends at next midnight`() {
        val mondayNoon = instant(2025, 1, 6, 12, 0)
        val tuesdayMidnight = instant(2025, 1, 7, 0, 0)
        val session = fullDaySession(Calendar.MONDAY)

        val next = BlockingScheduleCalculator.nextBoundary(
            sessions = listOf(session),
            additionalBoundaries = emptyList(),
            nowMillis = mondayNoon,
            timeZone = utc
        )

        assertThat(next).isEqualTo(tuesdayMidnight)
    }

    @Test
    fun `unselected day waits until next selected midnight start`() {
        val sundayLate = instant(2025, 1, 5, 23, 0)
        val mondayMidnight = instant(2025, 1, 6, 0, 0)
        val session = fullDaySession(Calendar.MONDAY)

        val next = BlockingScheduleCalculator.nextBoundary(
            sessions = listOf(session),
            additionalBoundaries = emptyList(),
            nowMillis = sundayLate,
            timeZone = utc
        )

        assertThat(next).isEqualTo(mondayMidnight)
    }

    private fun fullDaySession(allowedDay: Int) = BlockSession(
        isFixed24h = false,
        isRecurring = true,
        recurringStartHour = 0,
        recurringStartMinute = 0,
        recurringEndHour = 24,
        recurringEndMinute = 0,
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
