package com.focusguard.security

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AppUpdateUnlockResetPolicyTest {

    @Test
    fun `password limit with temporary release is reset`() {
        assertThat(
            AppUpdateUnlockResetPolicy.shouldReset(
                lockMode = "PASSWORD",
                lockUntilTimestamp = 123_456L
            )
        ).isTrue()
    }

    @Test
    fun `password mode comparison is case insensitive`() {
        assertThat(
            AppUpdateUnlockResetPolicy.shouldReset(
                lockMode = "password",
                lockUntilTimestamp = 123_456L
            )
        ).isTrue()
    }

    @Test
    fun `password limit that is already locked has nothing to reset`() {
        assertThat(
            AppUpdateUnlockResetPolicy.shouldReset(
                lockMode = "PASSWORD",
                lockUntilTimestamp = null
            )
        ).isFalse()
    }

    @Test
    fun `time hardening deadline survives app update`() {
        assertThat(
            AppUpdateUnlockResetPolicy.shouldReset(
                lockMode = "TIME",
                lockUntilTimestamp = 123_456L
            )
        ).isFalse()
    }

    @Test
    fun `daily pause rule deadline survives app update`() {
        assertThat(
            AppUpdateUnlockResetPolicy.shouldReset(
                lockMode = "PAUSE_30:com.example.app",
                lockUntilTimestamp = 123_456L
            )
        ).isFalse()
    }

    @Test
    fun `block until tomorrow rule deadline survives app update`() {
        assertThat(
            AppUpdateUnlockResetPolicy.shouldReset(
                lockMode = "BLOCK_UNTIL_TOMORROW:com.example.app",
                lockUntilTimestamp = 123_456L
            )
        ).isFalse()
    }
}
