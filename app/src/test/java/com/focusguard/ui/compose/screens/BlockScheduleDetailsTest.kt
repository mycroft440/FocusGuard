package com.focusguard.ui.compose.screens

import com.focusguard.database.BlockSession
import com.focusguard.manager.BlockingSessionManager.BlockOverview
import com.google.common.truth.Truth.assertThat
import java.util.Calendar
import java.util.Locale
import org.junit.Test

class BlockScheduleDetailsTest {

    @Test
    fun `persisted days are parsed Monday first and invalid values are dropped`() {
        assertThat(BlockOverview.parseDaysOfWeek("1, 7,2,9,x,2"))
            .containsExactly(Calendar.MONDAY, Calendar.SATURDAY, Calendar.SUNDAY)
            .inOrder()
        assertThat(BlockOverview.parseDaysOfWeek("")).isEmpty()
    }

    @Test
    fun `daily period session exposes its blocked hours and days`() {
        val (window, days, start) = BlockOverview.scheduleOf(
            BlockSession(
                startTime = 1_000L,
                isRecurring = true,
                isFixed24h = false,
                recurringStartHour = 21,
                recurringStartMinute = 30,
                recurringEndHour = 7,
                recurringEndMinute = 0,
                recurringDaysOfWeek = "2,3,4,5,6",
                sessionType = "TIME"
            )
        )

        assertThat(window).isEqualTo(BlockOverview.DailyWindow(21 * 60 + 30, 7 * 60))
        assertThat(days).hasSize(5)
        assertThat(start).isEqualTo(1_000L)
    }

    @Test
    fun `all day session has no daily window`() {
        val (window, days, _) = BlockOverview.scheduleOf(
            BlockSession(isFixed24h = true, sessionType = "TIME")
        )

        assertThat(window).isNull()
        assertThat(days).isEmpty()
    }

    @Test
    fun `weekday names follow the locale`() {
        assertThat(weekdayNames(listOf(Calendar.MONDAY, Calendar.FRIDAY), Locale.US))
            .isEqualTo("Mon, Fri")
    }
}
