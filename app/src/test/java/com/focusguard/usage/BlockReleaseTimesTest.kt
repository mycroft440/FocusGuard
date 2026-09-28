package com.focusguard.usage

import com.focusguard.database.AppUsageLimit
import com.focusguard.database.BlockSession
import com.focusguard.utils.UsageLimitBehaviorPolicy
import com.google.common.truth.Truth.assertThat
import java.util.Calendar
import java.util.TimeZone
import org.junit.Test

class BlockReleaseTimesTest {

    private val utc = TimeZone.getTimeZone("UTC")

    private fun at(day: Int, hour: Int, minute: Int = 0): Long =
        Calendar.getInstance(utc).apply {
            clear()
            set(2025, Calendar.JANUARY, day, hour, minute)
        }.timeInMillis

    private fun scheduled(
        startHour: Int,
        endHour: Int,
        endTime: Long? = null,
        days: String = ""
    ) = BlockSession(
        sessionType = "TIME",
        isFixed24h = false,
        isRecurring = true,
        recurringStartHour = startHour,
        recurringEndHour = endHour,
        recurringDaysOfWeek = days,
        startTime = at(1, 0),
        endTime = endTime
    )

    @Test
    fun `daytime window releases the same day at its end`() {
        val session = scheduled(startHour = 9, endHour = 17)
        assertThat(BlockReleaseTimes.scheduledWindowEnd(session, at(6, 10), utc))
            .isEqualTo(at(6, 17))
    }

    @Test
    fun `overnight window in the evening releases the next morning`() {
        val session = scheduled(startHour = 21, endHour = 19)
        assertThat(BlockReleaseTimes.scheduledWindowEnd(session, at(6, 23), utc))
            .isEqualTo(at(7, 19))
    }

    @Test
    fun `overnight window after midnight releases the same day`() {
        val session = scheduled(startHour = 22, endHour = 7)
        assertThat(BlockReleaseTimes.scheduledWindowEnd(session, at(7, 2), utc))
            .isEqualTo(at(7, 7))
    }

    @Test
    fun `window end never passes the end of the rule`() {
        val session = scheduled(startHour = 21, endHour = 19, endTime = at(7, 8))
        assertThat(BlockReleaseTimes.scheduledWindowEnd(session, at(6, 23), utc))
            .isEqualTo(at(7, 8))
    }

    @Test
    fun `allowed window is the complement of the blocked window`() {
        val window = BlockReleaseTimes.allowedWindow(scheduled(startHour = 21, endHour = 19))
        assertThat(window).isEqualTo(
            BlockReleaseTimes.AllowedWindow(
                startHour = 19,
                startMinute = 0,
                endHour = 21,
                endMinute = 0,
                restrictedToDays = false
            )
        )
        assertThat(
            BlockReleaseTimes.allowedWindow(scheduled(9, 17, days = "2,3"))?.restrictedToDays
        ).isTrue()
    }

    @Test
    fun `continuous block has no allowed window and ends with the session`() {
        val continuous = BlockSession(sessionType = "TIME", isFixed24h = true, endTime = at(9, 5))
        assertThat(BlockReleaseTimes.allowedWindow(continuous)).isNull()
        assertThat(BlockReleaseTimes.scheduledWindowEnd(continuous, at(6, 10), utc))
            .isEqualTo(at(9, 5))
    }

    private fun limit(mode: String, until: Long?) = AppUsageLimit(
        packageName = "com.example.app",
        appName = "App",
        dailyLimitMinutes = 30,
        lockMode = mode,
        lockUntilTimestamp = until
    )

    @Test
    fun `block until tomorrow releases at the next midnight`() {
        val limit = limit(UsageLimitBehaviorPolicy.blockUntilTomorrowModeFor("app"), at(20, 23))
        assertThat(BlockReleaseTimes.usageLimitBlockedUntil(limit, at(6, 15), null, utc))
            .isEqualTo(at(7, 0))
    }

    @Test
    fun `block until tomorrow stops at the end of the rule`() {
        val limit = limit(UsageLimitBehaviorPolicy.blockUntilTomorrowModeFor("app"), at(6, 20))
        assertThat(BlockReleaseTimes.usageLimitBlockedUntil(limit, at(6, 15), null, utc))
            .isEqualTo(at(6, 20))
    }

    @Test
    fun `thirty minute pause releases when the pause ends`() {
        val limit = limit(UsageLimitBehaviorPolicy.pauseModeFor("app"), at(20, 23))
        assertThat(
            BlockReleaseTimes.usageLimitBlockedUntil(limit, at(6, 15), at(6, 15, 30), utc)
        ).isEqualTo(at(6, 15, 30))
    }
}
