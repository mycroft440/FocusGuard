package com.focusguard.receiver

import com.focusguard.database.BlockSession
import com.google.common.truth.Truth.assertThat
import java.util.Calendar
import java.util.TimeZone
import org.junit.Test

class DopamineRecurringScheduleTest {

    private val utc = TimeZone.getTimeZone("UTC")

    @Test
    fun `configured daytime window schedules its next start`() {
        val mondayNoon = instant(2025, 1, 6, 12, 0)
        val mondayStart = instant(2025, 1, 6, 15, 0)
        val session = recurringSession(
            startHour = 15,
            endHour = 22,
            allowedDay = Calendar.MONDAY
        )

        val next = BlockingScheduleCalculator.nextBoundary(
            sessions = listOf(session),
            additionalBoundaries = emptyList(),
            nowMillis = mondayNoon,
            timeZone = utc
        )

        assertThat(next).isEqualTo(mondayStart)
    }

    @Test
    fun `configured daytime window schedules its end while active`() {
        val mondayEvening = instant(2025, 1, 6, 18, 0)
        val mondayEnd = instant(2025, 1, 6, 22, 0)
        val session = recurringSession(
            startHour = 15,
            endHour = 22,
            allowedDay = Calendar.MONDAY
        )

        val next = BlockingScheduleCalculator.nextBoundary(
            sessions = listOf(session),
            additionalBoundaries = emptyList(),
            nowMillis = mondayEvening,
            timeZone = utc
        )

        assertThat(next).isEqualTo(mondayEnd)
    }

    @Test
    fun `commitment expiration wins before a later recurring window`() {
        val mondayEvening = instant(2025, 1, 6, 18, 0)
        val commitmentEnd = instant(2025, 1, 6, 20, 0)
        val session = BlockSession(
            endTime = commitmentEnd,
            isFixed24h = false,
            isRecurring = true,
            recurringStartHour = 15,
            recurringStartMinute = 0,
            recurringEndHour = 22,
            recurringEndMinute = 0,
            recurringDaysOfWeek = Calendar.TUESDAY.toString()
        )

        val next = BlockingScheduleCalculator.nextBoundary(
            sessions = listOf(session),
            additionalBoundaries = emptyList(),
            nowMillis = mondayEvening,
            timeZone = utc
        )

        assertThat(next).isEqualTo(commitmentEnd)
    }

    @Test
    fun `legacy full day ending at 24 remains valid`() {
        assertThat(
            BlockingScheduleCalculator.isValidRecurringWindow(
                startHour = 0,
                startMinute = 0,
                endHour = 24,
                endMinute = 0
            )
        ).isTrue()
    }

    @Test
    fun `same start and end time is rejected`() {
        assertThat(
            BlockingScheduleCalculator.isValidRecurringWindow(
                startHour = 15,
                startMinute = 0,
                endHour = 15,
                endMinute = 0
            )
        ).isFalse()
    }

    @Test
    fun `overnight window is valid`() {
        assertThat(
            BlockingScheduleCalculator.isValidRecurringWindow(
                startHour = 22,
                startMinute = 0,
                endHour = 6,
                endMinute = 0
            )
        ).isTrue()
    }

    private fun recurringSession(
        startHour: Int,
        endHour: Int,
        allowedDay: Int
    ) = BlockSession(
        isFixed24h = false,
        isRecurring = true,
        recurringStartHour = startHour,
        recurringStartMinute = 0,
        recurringEndHour = endHour,
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
