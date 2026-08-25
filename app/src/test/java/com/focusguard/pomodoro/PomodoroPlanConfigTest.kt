package com.focusguard.pomodoro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PomodoroPlanConfigTest {

    @Test
    fun `target sessions are capped at five`() {
        val normalized = PomodoroPlanConfig(targetSessions = 99).normalized()

        assertEquals(5, normalized.targetSessions)
    }

    @Test
    fun `strict blocking is retired from normalized config`() {
        val normalized = PomodoroPlanConfig(strictBlocking = true).normalized()

        assertFalse(normalized.strictBlocking)
    }
}
