package com.focusguard.security

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProtectedSettingsResetWindowTest {

    @Test
    fun `close revokes reset exemption immediately`() {
        ProtectedSettingsResetWindow.open(91L)
        assertThat(ProtectedSettingsResetWindow.isActive(91L)).isTrue()

        ProtectedSettingsResetWindow.close(91L)

        assertThat(ProtectedSettingsResetWindow.isActive(91L)).isFalse()
    }

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
