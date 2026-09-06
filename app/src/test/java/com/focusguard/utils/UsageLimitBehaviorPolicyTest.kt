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
}
