package com.focusguard.service

import com.focusguard.database.BlockSession
import com.google.common.truth.Truth.assertThat
import java.util.Calendar
import java.util.TimeZone
import org.junit.Test

class HierarchyBoundaryPolicyTest {
    private val utc = TimeZone.getTimeZone("UTC")

    @Test
    fun `fixed TIME end is the exact live handoff boundary`() {
        val now = instant(2026, 9, 13, 10, 0)
        val end = instant(2026, 9, 13, 10, 5)

        val next = HierarchyBoundaryPolicy.nextBoundary(
            sessions = listOf(BlockSession(endTime = end, isFixed24h = true)),
            nowMillis = now,
            timeZone = utc
        )

        assertThat(next).isEqualTo(end)
        assertThat(HierarchyBoundaryPolicy.delayMillis(end, now)).isEqualTo(300_000L)
    }

    @Test
    fun `recurring TIME window hands back to PASSWORD at its exact end`() {
        val now = instant(2026, 9, 14, 9, 30) // Monday
        val end = instant(2026, 9, 14, 10, 0)
        val session = BlockSession(
            isFixed24h = false,
            isRecurring = true,
            recurringStartHour = 9,
            recurringEndHour = 10,
            recurringDaysOfWeek = Calendar.MONDAY.toString()
        )

        assertThat(
            HierarchyBoundaryPolicy.nextBoundary(
                sessions = listOf(session),
                nowMillis = now,
                timeZone = utc
            )
        ).isEqualTo(end)
    }

    @Test
    fun `late callback reconciles immediately instead of waiting another interval`() {
        assertThat(
            HierarchyBoundaryPolicy.delayMillis(
                boundaryMillis = 1_000L,
                nowMillis = 1_050L
            )
        ).isEqualTo(1L)
    }

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
