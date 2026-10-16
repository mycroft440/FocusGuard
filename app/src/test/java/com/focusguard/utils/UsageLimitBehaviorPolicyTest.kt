package com.focusguard.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageLimitBehaviorPolicyTest {

    @Test
    fun `pause starts once when daily limit is first exceeded`() {
        val now = 1_000_000L
        val ruleEnd = now + 10L * UsageLimitBehaviorPolicy.PAUSE_DURATION_MILLIS

        val result = UsageLimitBehaviorPolicy.evaluatePause(
            previous = null,
            ruleEndMillis = ruleEnd,
            nowMillis = now,
            dayKey = 2026240L
        )

        assertTrue(result.shouldBlock)
        assertTrue(result.startedPause)
        assertNotNull(result.state)
        assertEquals(
            now + UsageLimitBehaviorPolicy.PAUSE_DURATION_MILLIS,
            result.state?.blockedUntilMillis
        )
    }

    @Test
    fun `pause remains blocked for thirty minutes`() {
        val start = 2_000_000L
        val ruleEnd = start + 10L * UsageLimitBehaviorPolicy.PAUSE_DURATION_MILLIS
        val state = UsageLimitBehaviorPolicy.PauseState(
            ruleEndMillis = ruleEnd,
            dayKey = 2026240L,
            blockedUntilMillis = start + UsageLimitBehaviorPolicy.PAUSE_DURATION_MILLIS
        )

        val result = UsageLimitBehaviorPolicy.evaluatePause(
            previous = state,
            ruleEndMillis = ruleEnd,
            nowMillis = start + 10L * 60L * 1_000L,
            dayKey = 2026240L
        )

        assertTrue(result.shouldBlock)
        assertFalse(result.startedPause)
    }

    @Test
    fun `pause does not repeat after thirty minutes on same day`() {
        val start = 3_000_000L
        val ruleEnd = start + 10L * UsageLimitBehaviorPolicy.PAUSE_DURATION_MILLIS
        val state = UsageLimitBehaviorPolicy.PauseState(
            ruleEndMillis = ruleEnd,
            dayKey = 2026240L,
            blockedUntilMillis = start + UsageLimitBehaviorPolicy.PAUSE_DURATION_MILLIS
        )

        val result = UsageLimitBehaviorPolicy.evaluatePause(
            previous = state,
            ruleEndMillis = ruleEnd,
            nowMillis = start + UsageLimitBehaviorPolicy.PAUSE_DURATION_MILLIS + 1L,
            dayKey = 2026240L
        )

        assertFalse(result.shouldBlock)
        assertFalse(result.startedPause)
        assertEquals(state, result.state)
    }

    @Test
    fun `pause starts again on next local day`() {
        val now = 4_000_000L
        val ruleEnd = now + 20L * UsageLimitBehaviorPolicy.PAUSE_DURATION_MILLIS
        val previous = UsageLimitBehaviorPolicy.PauseState(
            ruleEndMillis = ruleEnd,
            dayKey = 2026240L,
            blockedUntilMillis = now - 1L
        )

        val result = UsageLimitBehaviorPolicy.evaluatePause(
            previous = previous,
            ruleEndMillis = ruleEnd,
            nowMillis = now,
            dayKey = 2026241L
        )

        assertTrue(result.shouldBlock)
        assertTrue(result.startedPause)
        assertEquals(2026241L, result.state?.dayKey)
    }

    @Test
    fun `expired overall rule cannot start another pause`() {
        val now = 5_000_000L

        val result = UsageLimitBehaviorPolicy.evaluatePause(
            previous = null,
            ruleEndMillis = now,
            nowMillis = now,
            dayKey = 2026240L
        )

        assertFalse(result.shouldBlock)
        assertFalse(result.startedPause)
        assertEquals(null, result.state)
    }

    @Test
    fun `new modes include app identifier and are recognized`() {
        val packageName = "com.example.social"
        val pause = UsageLimitBehaviorPolicy.pauseModeFor(packageName)
        val tomorrow = UsageLimitBehaviorPolicy.blockUntilTomorrowModeFor(packageName)

        assertTrue(UsageLimitBehaviorPolicy.isPauseMode(pause))
        assertTrue(UsageLimitBehaviorPolicy.isBlockUntilTomorrowMode(tomorrow))
        assertEquals(packageName, UsageLimitBehaviorPolicy.identifierFrom(pause))
        assertEquals(packageName, UsageLimitBehaviorPolicy.identifierFrom(tomorrow))
    }

    @Test
    fun `editing only allowance or behavior preserves exact existing deadline`() {
        val existingDeadline = 9_876_543_210L
        val roundedReplacement = 9_999_999_999L

        assertEquals(
            existingDeadline,
            UsageLimitBehaviorPolicy.resolveRuleEndForEdit(
                existingRuleEndMillis = existingDeadline,
                durationEdited = false,
                calculatedRuleEndMillis = roundedReplacement
            )
        )
    }

    @Test
    fun `editing duration uses recalculated deadline`() {
        val existingDeadline = 9_876_543_210L
        val replacement = 10_123_456_789L

        assertEquals(
            replacement,
            UsageLimitBehaviorPolicy.resolveRuleEndForEdit(
                existingRuleEndMillis = existingDeadline,
                durationEdited = true,
                calculatedRuleEndMillis = replacement
            )
        )
    }

    @Test
    fun `new rule uses calculated deadline`() {
        val calculated = 10_123_456_789L

        assertEquals(
            calculated,
            UsageLimitBehaviorPolicy.resolveRuleEndForEdit(
                existingRuleEndMillis = null,
                durationEdited = false,
                calculatedRuleEndMillis = calculated
            )
        )
    }

    @Test
    fun `restored active pause still exposes its alarm deadline`() {
        val now = 7_000_000L
        val deadline = now + 10L * 60L * 1_000L
        val evaluation = UsageLimitBehaviorPolicy.PauseEvaluation(
            shouldBlock = true,
            state = UsageLimitBehaviorPolicy.PauseState(
                ruleEndMillis = deadline + 60_000L,
                dayKey = 2026240L,
                blockedUntilMillis = deadline
            ),
            startedPause = false
        )

        assertEquals(
            deadline,
            UsageLimitBehaviorPolicy.pauseEndDeadline(evaluation, now)
        )
    }

    @Test
    fun `a daily rule past its end is no longer in force`() {
        val now = 8_000_000L
        val pause = UsageLimitBehaviorPolicy.pauseModeFor("com.example.feed")
        val tomorrow = UsageLimitBehaviorPolicy.blockUntilTomorrowModeFor("com.example.feed")

        assertTrue(UsageLimitBehaviorPolicy.isInForce(true, pause, now + 1L, now))
        assertFalse(UsageLimitBehaviorPolicy.isInForce(true, pause, now, now))
        assertFalse(UsageLimitBehaviorPolicy.isInForce(true, tomorrow, now - 1L, now))
        assertFalse(UsageLimitBehaviorPolicy.isInForce(false, tomorrow, now + 1L, now))
        // O modo antigo sem fim de regra vale enquanto estiver ligado.
        assertTrue(UsageLimitBehaviorPolicy.isInForce(true, "NONE", null, now))
    }

    @Test
    fun `a pause moves with its rule when the clock is changed by hand`() {
        val state = UsageLimitBehaviorPolicy.PauseState(
            ruleEndMillis = 50_000_000L,
            dayKey = 2026240L,
            blockedUntilMillis = 9_000_000L
        )
        val jump = 2L * 60L * 60L * 1_000L

        val shifted = UsageLimitBehaviorPolicy.shiftedPause(state) { it + jump }

        assertEquals(50_000_000L + jump, shifted.ruleEndMillis)
        assertEquals(9_000_000L + jump, shifted.blockedUntilMillis)
        assertEquals(state.dayKey, shifted.dayKey)
        // Adiantado o relógio pelo mesmo tanto, a pausa ainda segura o app.
        val evaluation = UsageLimitBehaviorPolicy.evaluatePause(
            previous = shifted,
            ruleEndMillis = shifted.ruleEndMillis,
            nowMillis = 8_000_000L + jump,
            dayKey = state.dayKey
        )
        assertTrue(evaluation.shouldBlock)
        assertFalse(evaluation.startedPause)
    }

    @Test
    fun `an exhausted day is recorded once with the next midnight`() {
        val now = 9_000_000L
        val ruleEnd = now + 10L * DAY
        val recorded = UsageLimitBehaviorPolicy.dailyBlockToRecord(
            previous = null,
            ruleEndMillis = ruleEnd,
            nowMillis = now,
            dayKey = 2026240L
        )

        assertNotNull(recorded)
        assertTrue(recorded!!.blockedUntilMillis!! in (now + 1L)..(now + DAY))
        assertEquals(
            null,
            UsageLimitBehaviorPolicy.dailyBlockToRecord(recorded, ruleEnd, now + 1_000L, 2026240L)
        )
        // Registro antigo, só do aviso: ganha o fim do bloqueio sem repetir o aviso.
        val legacy = recorded.copy(blockedUntilMillis = null)
        assertNotNull(
            UsageLimitBehaviorPolicy.dailyBlockToRecord(legacy, ruleEnd, now, 2026240L)
                ?.blockedUntilMillis
        )
        // Um bloqueio já levado para a frente pelo relógio mudado não encurta.
        val shifted = recorded.copy(dayKey = 2026239L, blockedUntilMillis = now + 5L * DAY)
        assertEquals(
            now + 5L * DAY,
            UsageLimitBehaviorPolicy.dailyBlockToRecord(shifted, ruleEnd, now, 2026240L)
                ?.blockedUntilMillis
        )
    }

    @Test
    fun `an exhausted rule keeps holding even when the count goes back to zero`() {
        val now = 10_000_000L
        val ruleEnd = now + 10L * DAY
        val tomorrow = UsageLimitBehaviorPolicy.blockUntilTomorrowModeFor("com.example.feed")
        val pause = UsageLimitBehaviorPolicy.pauseModeFor("com.example.feed")
        val daily = UsageLimitBehaviorPolicy.DailyBlockState(ruleEnd, 2026240L, now + HOUR)
        val pauseState = UsageLimitBehaviorPolicy.PauseState(ruleEnd, 2026239L, now + 60_000L)

        assertEquals(
            now + HOUR,
            UsageLimitBehaviorPolicy.heldUntil(null, daily, tomorrow, ruleEnd, now)
        )
        // A pausa em curso vale até o fim mesmo depois da virada do dia.
        assertEquals(
            now + 60_000L,
            UsageLimitBehaviorPolicy.heldUntil(pauseState, null, pause, ruleEnd, now)
        )
        // Outra regra, bloqueio vencido ou regra encerrada não seguram nada.
        assertEquals(
            null,
            UsageLimitBehaviorPolicy.heldUntil(null, daily, tomorrow, ruleEnd + 1L, now)
        )
        assertEquals(
            null,
            UsageLimitBehaviorPolicy.heldUntil(null, daily, tomorrow, ruleEnd, now + HOUR)
        )
        assertEquals(null, UsageLimitBehaviorPolicy.heldUntil(null, daily, "NONE", ruleEnd, now))
        // Nunca passa do fim da própria regra.
        val endsSoon = now + 30L * 60_000L
        assertEquals(
            endsSoon,
            UsageLimitBehaviorPolicy.heldUntil(
                null,
                daily.copy(ruleEndMillis = endsSoon),
                tomorrow,
                endsSoon,
                now
            )
        )
    }

    @Test
    fun `daily allowance accepts up to a full day`() {
        assertTrue(UsageLimitBehaviorPolicy.isValidDailyLimitMinutes(1))
        assertTrue(UsageLimitBehaviorPolicy.isValidDailyLimitMinutes(24 * 60))
        assertFalse(UsageLimitBehaviorPolicy.isValidDailyLimitMinutes(0))
        assertFalse(UsageLimitBehaviorPolicy.isValidDailyLimitMinutes(24 * 60 + 1))
    }

    @Test
    fun `rule ends and recorded holds are reconciliation boundaries`() {
        val now = 11_000_000L
        val tomorrow = UsageLimitBehaviorPolicy.blockUntilTomorrowModeFor("com.example.feed")

        assertEquals(
            listOf(now + DAY, now + HOUR),
            UsageLimitBehaviorPolicy.stateChangeBoundaries(tomorrow, now + DAY, now + HOUR, now)
        )
        assertEquals(
            listOf(now + DAY),
            UsageLimitBehaviorPolicy.stateChangeBoundaries("TIME", now + DAY, null, now)
        )
        // A liberação do modo PASSWORD até a meia-noite já cai na virada do dia.
        assertEquals(
            emptyList<Long>(),
            UsageLimitBehaviorPolicy.stateChangeBoundaries("PASSWORD", now + DAY, null, now)
        )
        assertEquals(
            emptyList<Long>(),
            UsageLimitBehaviorPolicy.stateChangeBoundaries(tomorrow, now - 1L, now, now)
        )
    }

    private companion object {
        const val HOUR = 60L * 60L * 1_000L
        const val DAY = 24L * HOUR
    }
}
