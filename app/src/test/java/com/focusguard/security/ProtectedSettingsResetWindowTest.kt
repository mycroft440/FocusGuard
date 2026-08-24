package com.focusguard.security

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProtectedSettingsResetWindowTest {

    @Test
    fun `only the live matching curtain generation suppresses internal reset events`() {
        assertThat(
            ProtectedSettingsResetWindow.evaluate(
                expectedGeneration = 14L,
                storedGeneration = 14L,
                deadlineElapsed = 4_000L,
                nowElapsed = 3_999L
            )
        ).isTrue()
        assertThat(
            ProtectedSettingsResetWindow.evaluate(
                expectedGeneration = 15L,
                storedGeneration = 14L,
                deadlineElapsed = 4_000L,
                nowElapsed = 3_999L
            )
        ).isFalse()
        assertThat(
            ProtectedSettingsResetWindow.evaluate(
                expectedGeneration = 14L,
                storedGeneration = 14L,
                deadlineElapsed = 4_000L,
                nowElapsed = 4_000L
            )
        ).isFalse()
    }
}
