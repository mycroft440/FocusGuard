package com.focusguard.security

import com.focusguard.database.AppUsageLimit
import com.focusguard.database.BlockSession
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Relógio mudado à mão não encurta nem alonga um bloqueio por tempo. */
class ClockJumpPolicyTest {
    private val hour = 60L * 60L * 1_000L
    private val day = 24L * hour

    @Test
    fun `a manual jump forward in the same boot is compensated`() {
        val jump = ClockJumpPolicy.compensatedJumpMillis(
            anchor = anchor(bootWall = 1_000_000L, autoTime = true),
            // A pessoa desligou a hora automática e adiantou três dias.
            current = anchor(bootWall = 1_000_000L + 3 * day, autoTime = false)
        )
        assertThat(jump).isEqualTo(3 * day)
    }

    @Test
    fun `going back to automatic time after a manual change undoes the compensation`() {
        val jump = ClockJumpPolicy.compensatedJumpMillis(
            anchor = anchor(bootWall = 1_000_000L + 3 * day, autoTime = false),
            current = anchor(bootWall = 1_000_000L, autoTime = true)
        )
        assertThat(jump).isEqualTo(-3 * day)
    }

    @Test
    fun `a network correction with automatic time on is the right time`() {
        // Inclui o aparelho que ligou com a hora errada e foi acertado pela rede.
        assertThat(
            ClockJumpPolicy.compensatedJumpMillis(
                anchor = anchor(bootWall = 1_000_000L, autoTime = true),
                current = anchor(bootWall = 1_000_000L + 2 * day, autoTime = true)
            )
        ).isEqualTo(0L)
    }

    @Test
    fun `drift and other boots are never compensated`() {
        assertThat(
            ClockJumpPolicy.compensatedJumpMillis(
                anchor = anchor(bootWall = 1_000_000L, autoTime = false),
                current = anchor(bootWall = 1_000_000L + 59_000L, autoTime = false)
            )
        ).isEqualTo(0L)
        assertThat(
            ClockJumpPolicy.compensatedJumpMillis(
                anchor = anchor(bootCount = 7, bootWall = 1_000_000L, autoTime = false),
                current = anchor(bootCount = 8, bootWall = 1_000_000L + day, autoTime = false)
            )
        ).isEqualTo(0L)
        assertThat(ClockJumpPolicy.compensatedJumpMillis(null, anchor(1L, false))).isEqualTo(0L)
        assertThat(ClockJumpPolicy.compensatedJumpMillis(anchor(1L, false), null)).isEqualTo(0L)
    }

    @Test
    fun `a time block keeps its real remaining time and cancel window`() {
        val session = BlockSession(
            id = 3,
            startTime = 10 * day,
            endTime = 12 * day,
            sessionType = "TIME",
            cancelableUntil = 10 * day + 48 * hour
        )

        val shifted = ClockJumpPolicy.shiftSession(session, 3 * day)

        assertThat(shifted?.startTime).isEqualTo(13 * day)
        assertThat(shifted?.endTime).isEqualTo(15 * day)
        assertThat(shifted?.cancelableUntil).isEqualTo(13 * day + 48 * hour)
    }

    @Test
    fun `password blocks and ended sessions have no deadline to move`() {
        assertThat(
            ClockJumpPolicy.shiftSession(BlockSession(sessionType = "PASSWORD"), day)
        ).isNull()
        assertThat(
            ClockJumpPolicy.shiftSession(
                BlockSession(endTime = 5 * day, isActive = false, sessionType = "TIME"),
                day
            )
        ).isNull()
    }

    @Test
    fun `a hardened limit keeps its end and its edit lock in real time`() {
        val limit = AppUsageLimit(
            packageName = "com.example.feed",
            appName = "Feed",
            dailyLimitMinutes = 30,
            lockMode = "TIME",
            lockUntilTimestamp = 20 * day,
            lockAfter = 12 * day
        )

        val shifted = ClockJumpPolicy.shiftLimit(limit, -2 * day)

        assertThat(shifted?.lockUntilTimestamp).isEqualTo(18 * day)
        // Atrasar o relógio não reabre o limite para edição ou remoção.
        assertThat(shifted?.lockAfter).isEqualTo(10 * day)
    }

    @Test
    fun `a password release until midnight is not a deadline of the limit`() {
        val limit = AppUsageLimit(
            packageName = "com.example.feed",
            appName = "Feed",
            dailyLimitMinutes = 30,
            lockMode = "PASSWORD",
            lockUntilTimestamp = 20 * day,
            lockAfter = 12 * day
        )

        val shifted = ClockJumpPolicy.shiftLimit(limit, day)

        assertThat(shifted?.lockUntilTimestamp).isEqualTo(20 * day)
        assertThat(shifted?.lockAfter).isEqualTo(13 * day)
        assertThat(
            ClockJumpPolicy.shiftLimit(limit.copy(lockAfter = null), day)?.lockAfter
        ).isNull()
    }

    @Test
    fun `a daily rule keeps its real end so it cannot be ended early and removed`() {
        val limit = AppUsageLimit(
            packageName = "com.example.feed",
            appName = "Feed",
            dailyLimitMinutes = 30,
            lockMode = "BLOCK_UNTIL_TOMORROW:com.example.feed",
            lockUntilTimestamp = 20 * day,
            lockAfter = 12 * day,
            createdAt = 10 * day
        )

        val shifted = ClockJumpPolicy.shiftLimit(limit, 30 * day)

        assertThat(shifted?.lockUntilTimestamp).isEqualTo(50 * day)
        assertThat(shifted?.lockAfter).isEqualTo(42 * day)
        assertThat(
            ClockJumpPolicy.shiftLimit(
                limit.copy(lockMode = "PAUSE_30:com.example.feed"),
                -day
            )?.lockUntilTimestamp
        ).isEqualTo(19 * day)
    }

    @Test
    fun `the activation moves with the usage history`() {
        // Criado hoje às 9 h: atrasar o relógio para antes disso zerava o uso contado.
        val limit = AppUsageLimit(
            packageName = "com.example.feed",
            appName = "Feed",
            dailyLimitMinutes = 30,
            createdAt = 10 * day + 9 * hour
        )

        assertThat(ClockJumpPolicy.shiftLimit(limit, -3 * hour)?.createdAt)
            .isEqualTo(10 * day + 6 * hour)
        assertThat(ClockJumpPolicy.shiftLimit(limit, 0L)).isNull()
    }

    @Test
    fun `blocks and limits created after the last reconciliation already use the new clock`() {
        val anchorWallClock = 10 * day
        val created = BlockSession(
            startTime = 13 * day,
            endTime = 14 * day,
            sessionType = "TIME",
            createdAt = 13 * day
        )
        val existing = created.copy(startTime = 9 * day, endTime = 11 * day, createdAt = 9 * day)
        val limit = AppUsageLimit(
            packageName = "com.example.feed",
            appName = "Feed",
            dailyLimitMinutes = 30,
            lockAfter = 15 * day,
            createdAt = 13 * day
        )

        assertThat(ClockJumpPolicy.shiftSession(created, 3 * day, anchorWallClock)).isNull()
        assertThat(ClockJumpPolicy.shiftLimit(limit, 3 * day, anchorWallClock)).isNull()
        assertThat(ClockJumpPolicy.shiftSession(existing, 3 * day, anchorWallClock)?.endTime)
            .isEqualTo(14 * day)
    }

    @Test
    fun `huge jumps saturate instead of wrapping around`() {
        val session = BlockSession(endTime = Long.MAX_VALUE - 10L, sessionType = "TIME")
        assertThat(ClockJumpPolicy.shiftSession(session, day)?.endTime)
            .isEqualTo(Long.MAX_VALUE)
    }

    private fun anchor(
        bootWall: Long,
        autoTime: Boolean,
        bootCount: Int = 7
    ) = ClockJumpPolicy.Anchor(
        bootCount = bootCount,
        bootWallClockMillis = bootWall,
        autoTimeEnabled = autoTime
    )
}
