package com.focusguard.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PasswordUnlockPresentationStateTest {
    @Test
    fun `repeated intents keep the open credential surface through new curtains`() {
        val state = PasswordUnlockPresentationState()
        assertThat(state.present(accessAttemptId = 1L, curtainGeneration = 10L)).isTrue()
        assertThat(state.authenticationReady).isFalse()
        assertThat(state.acknowledgeCurtain()).isEqualTo(10L)
        state.finishCurtainSettle(state.curtainRequestId)

        for (generation in 11L..15L) {
            assertThat(state.present(1L, generation)).isFalse()
            assertThat(state.authenticationReady).isTrue()
            assertThat(state.acknowledgeCurtain()).isEqualTo(generation)
            assertThat(state.authenticationReady).isTrue()
        }
    }

    @Test
    fun `acknowledgement and repeated focus do not bypass curtain settling`() {
        val state = PasswordUnlockPresentationState()
        state.present(1L, 10L)
        val request = state.curtainRequestId
        assertThat(state.finishCurtainSettle(request)).isFalse()
        state.acknowledgeCurtain()
        assertThat(state.acknowledgeCurtain()).isEqualTo(0L)
        assertThat(state.authenticationReady).isFalse()
        assertThat(state.finishCurtainSettle(request)).isTrue()
        assertThat(state.authenticationReady).isTrue()
    }

    @Test
    fun `older settle callback cannot release a newer curtain in the same attempt`() {
        val state = PasswordUnlockPresentationState()
        state.present(1L, 10L)
        val oldRequest = state.curtainRequestId
        state.acknowledgeCurtain()
        state.present(1L, 11L)
        state.acknowledgeCurtain()

        assertThat(state.finishCurtainSettle(oldRequest)).isFalse()
        assertThat(state.authenticationReady).isFalse()
        assertThat(state.finishCurtainSettle(state.curtainRequestId)).isTrue()
    }

    @Test
    fun `duplicate intent without curtain preserves pending handshake and timer`() {
        val state = PasswordUnlockPresentationState()
        state.present(1L, 10L)
        val request = state.curtainRequestId

        assertThat(state.present(1L, 0L)).isFalse()
        assertThat(state.curtainRequestId).isEqualTo(request)
        assertThat(state.acknowledgeCurtain()).isEqualTo(10L)
        assertThat(state.present(1L, 0L)).isFalse()
        assertThat(state.authenticationReady).isFalse()
        assertThat(state.finishCurtainSettle(request)).isTrue()
    }

    @Test
    fun `new access resets presentation and rejects the previous settle callback`() {
        val state = PasswordUnlockPresentationState()
        state.present(1L, 10L)
        state.acknowledgeCurtain()
        val previousRequest = state.curtainRequestId
        state.finishCurtainSettle(previousRequest)

        // The Activity assigns a new access ID after backgrounding, success, or
        // changing the protected package, even if the Activity itself is reused.
        assertThat(state.present(2L, 11L)).isTrue()
        assertThat(state.authenticationReady).isFalse()
        assertThat(state.finishCurtainSettle(previousRequest)).isFalse()
    }

    @Test
    fun `new access without curtain is immediately ready`() {
        val state = PasswordUnlockPresentationState()
        assertThat(state.present(1L, 0L)).isTrue()
        assertThat(state.authenticationReady).isTrue()
    }
}
