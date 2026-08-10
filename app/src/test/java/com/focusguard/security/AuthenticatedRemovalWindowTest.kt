package com.focusguard.security

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AuthenticatedRemovalWindowTest {
    @Test
    fun `window is active only before deadline in same boot`() {
        assertThat(
            AuthenticatedRemovalWindow.evaluate(
                nowElapsedMillis = 1_000L,
                deadlineElapsedMillis = 2_000L,
                storedBootCount = 7,
                currentBootCount = 7
            )
        ).isTrue()
        assertThat(
            AuthenticatedRemovalWindow.evaluate(
                nowElapsedMillis = 2_000L,
                deadlineElapsedMillis = 2_000L,
                storedBootCount = 7,
                currentBootCount = 7
            )
        ).isFalse()
        assertThat(
            AuthenticatedRemovalWindow.evaluate(
                nowElapsedMillis = 1_000L,
                deadlineElapsedMillis = 2_000L,
                storedBootCount = 7,
                currentBootCount = 8
            )
        ).isFalse()
    }
}
