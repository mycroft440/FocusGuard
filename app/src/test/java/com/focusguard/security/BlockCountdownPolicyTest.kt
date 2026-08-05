package com.focusguard.security

import com.focusguard.security.BlockCountdownPolicy.Remaining
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.TimeUnit
import org.junit.Test

class BlockCountdownPolicyTest {

    private val now = 1_000_000_000L

    private fun inDays(days: Long) = now + TimeUnit.DAYS.toMillis(days)
    private fun inHours(hours: Long) = now + TimeUnit.HOURS.toMillis(hours)
    private fun inMinutes(minutes: Long) = now + TimeUnit.MINUTES.toMillis(minutes)

    @Test
    fun `no deadline means an open-ended block`() {
        assertThat(BlockCountdownPolicy.remaining(null, now)).isNull()
        assertThat(BlockCountdownPolicy.isStillBlocking(null, now)).isTrue()
    }

    @Test
    fun `full days are reported in days`() {
        assertThat(BlockCountdownPolicy.remaining(inDays(100), now))
            .isEqualTo(Remaining.Days(100))
    }

    @Test
    fun `days round down so the wait is never understated`() {
        // 1 day 23 hours is "1 day left", not "2 days". Showing 2 would be a lie
        // the user catches the moment it unlocks early.
        val deadline = now + TimeUnit.DAYS.toMillis(1) + TimeUnit.HOURS.toMillis(23)

        assertThat(BlockCountdownPolicy.remaining(deadline, now))
            .isEqualTo(Remaining.Days(1))
    }

    @Test
    fun `just under a day falls to hours`() {
        assertThat(BlockCountdownPolicy.remaining(inHours(23), now))
            .isEqualTo(Remaining.Hours(23))
    }

    @Test
    fun `exactly one day is reported in days`() {
        assertThat(BlockCountdownPolicy.remaining(inDays(1), now))
            .isEqualTo(Remaining.Days(1))
    }

    @Test
    fun `exactly one hour is reported in hours`() {
        assertThat(BlockCountdownPolicy.remaining(inHours(1), now))
            .isEqualTo(Remaining.Hours(1))
    }

    @Test
    fun `hours round down`() {
        val deadline = now + TimeUnit.HOURS.toMillis(5) + TimeUnit.MINUTES.toMillis(59)

        assertThat(BlockCountdownPolicy.remaining(deadline, now))
            .isEqualTo(Remaining.Hours(5))
    }

    @Test
    fun `under an hour is not shown in minutes`() {
        // Counting down the last minutes turns the fast into a stopwatch to stare
        // at, which is the opposite of the point.
        assertThat(BlockCountdownPolicy.remaining(inMinutes(59), now))
            .isEqualTo(Remaining.LessThanAnHour)
        assertThat(BlockCountdownPolicy.remaining(inMinutes(1), now))
            .isEqualTo(Remaining.LessThanAnHour)
    }

    @Test
    fun `the exact deadline counts as elapsed`() {
        assertThat(BlockCountdownPolicy.remaining(now, now)).isEqualTo(Remaining.Elapsed)
        assertThat(BlockCountdownPolicy.isStillBlocking(now, now)).isFalse()
    }

    @Test
    fun `a past deadline no longer blocks`() {
        val deadline = now - TimeUnit.HOURS.toMillis(2)

        assertThat(BlockCountdownPolicy.remaining(deadline, now)).isEqualTo(Remaining.Elapsed)
        assertThat(BlockCountdownPolicy.isStillBlocking(deadline, now)).isFalse()
    }

    @Test
    fun `a future deadline still blocks at every granularity`() {
        listOf(inDays(30), inHours(4), inMinutes(10)).forEach { deadline ->
            assertThat(BlockCountdownPolicy.isStillBlocking(deadline, now)).isTrue()
        }
    }
}
