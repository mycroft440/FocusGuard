package com.focusguard.pomodoro

import org.junit.Assert.assertEquals
import org.junit.Test

class PomodoroPlanConfigTest {

    @Test
    fun `target sessions are capped at five`() {
        val normalized = PomodoroPlanConfig(targetSessions = 99).normalized()

        assertEquals(5, normalized.targetSessions)
    }
}
