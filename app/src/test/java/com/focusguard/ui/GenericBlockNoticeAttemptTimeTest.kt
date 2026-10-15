package com.focusguard.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GenericBlockNoticeAttemptTimeTest {

    @Test
    fun `event uptime is converted to wall clock time`() {
        assertThat(
            GenericBlockNoticeActivity.attemptWallClockMillis(
                eventUptimeMillis = 9_000L,
                nowUptimeMillis = 10_000L,
                nowWallClockMillis = 1_700_000_000_000L
            )
        ).isEqualTo(1_699_999_999_000L)
    }

    @Test
    fun `missing or future event time falls back to now`() {
        assertThat(
            GenericBlockNoticeActivity.attemptWallClockMillis(0L, 10_000L, 5_000L)
        ).isEqualTo(5_000L)
        assertThat(
            GenericBlockNoticeActivity.attemptWallClockMillis(20_000L, 10_000L, 5_000L)
        ).isEqualTo(5_000L)
    }
}
