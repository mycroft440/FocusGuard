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

    @Test
    fun `unknown boot identity never authorizes a persisted window`() {
        assertThat(
            AuthenticatedRemovalWindow.evaluate(
                nowElapsedMillis = 1_000L,
                deadlineElapsedMillis = 2_000L,
                storedBootCount = -1,
                currentBootCount = -1
            )
        ).isFalse()
        assertThat(
            AuthenticatedRemovalWindow.evaluate(
                nowElapsedMillis = 1_000L,
                deadlineElapsedMillis = 2_000L,
                storedBootCount = Int.MIN_VALUE,
                currentBootCount = Int.MIN_VALUE
            )
        ).isFalse()
    }

    @Test
    fun `only a known boot count may persist authorization across process death`() {
        assertThat(AuthenticatedRemovalWindow.canPersistAcrossProcess(0)).isTrue()
        assertThat(AuthenticatedRemovalWindow.canPersistAcrossProcess(7)).isTrue()
        assertThat(AuthenticatedRemovalWindow.canPersistAcrossProcess(-1)).isFalse()
        assertThat(AuthenticatedRemovalWindow.canPersistAcrossProcess(Int.MIN_VALUE)).isFalse()
    }
}
