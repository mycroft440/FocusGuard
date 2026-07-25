package com.focusguard.security

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AccessibilityProtectionGateTest {

    @Test
    fun `window remains open for exactly ten minutes`() {
        val remaining = AccessibilityProtectionGate.evaluateRemainingMillis(
            nowElapsedMillis = 1_000L,
            deadlineElapsedMillis =
                1_000L + AccessibilityProtectionGate.UNLOCK_DURATION_MILLIS,
            storedBootCount = 7,
            currentBootCount = 7
        )

        assertThat(remaining).isEqualTo(10 * 60 * 1_000L)
    }

    @Test
    fun `window expires at deadline`() {
        val remaining = AccessibilityProtectionGate.evaluateRemainingMillis(
            nowElapsedMillis = 601_000L,
            deadlineElapsedMillis = 601_000L,
            storedBootCount = 7,
            currentBootCount = 7
        )

        assertThat(remaining).isEqualTo(0L)
    }

    @Test
    fun `window uses monotonic time and does not depend on wall clock settings`() {
        val remaining = AccessibilityProtectionGate.evaluateRemainingMillis(
            nowElapsedMillis = 301_000L,
            deadlineElapsedMillis = 601_000L,
            storedBootCount = 7,
            currentBootCount = 7
        )

        assertThat(remaining).isEqualTo(300_000L)
    }

    @Test
    fun `reboot invalidates window`() {
        val remaining = AccessibilityProtectionGate.evaluateRemainingMillis(
            nowElapsedMillis = 1_000L,
            deadlineElapsedMillis = 601_000L,
            storedBootCount = 7,
            currentBootCount = 8
        )

        assertThat(remaining).isEqualTo(0L)
    }
}
