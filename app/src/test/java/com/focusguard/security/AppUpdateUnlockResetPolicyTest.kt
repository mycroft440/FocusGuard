package com.focusguard.security

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AppUpdateUnlockResetPolicyTest {

    @Test
    fun `password limit with temporary release is reset`() {
        assertThat(
            AppUpdateUnlockResetPolicy.shouldResetPasswordRelease(
                lockMode = "PASSWORD",
                lockUntilTimestamp = 123_456L
            )
        ).isTrue()
    }

    @Test
    fun `password mode comparison is case insensitive`() {
        assertThat(
            AppUpdateUnlockResetPolicy.shouldResetPasswordRelease(
                lockMode = "password",
                lockUntilTimestamp = 123_456L
            )
        ).isTrue()
    }

    @Test
    fun `password limit that is already locked has nothing to reset`() {
        assertThat(
            AppUpdateUnlockResetPolicy.shouldResetPasswordRelease(
                lockMode = "PASSWORD",
                lockUntilTimestamp = null
            )
        ).isFalse()
    }

    @Test
    fun `time hardening deadline survives app update`() {
        assertThat(
            AppUpdateUnlockResetPolicy.shouldResetPasswordRelease(
                lockMode = "TIME",
                lockUntilTimestamp = 123_456L
            )
        ).isFalse()
    }

    @Test
    fun `daily pause rule deadline survives while its release state is reset`() {
        val mode = "PAUSE_30:com.example.app"

        assertThat(
            AppUpdateUnlockResetPolicy.shouldResetPasswordRelease(
                lockMode = mode,
                lockUntilTimestamp = 123_456L
            )
        ).isFalse()
        assertThat(AppUpdateUnlockResetPolicy.shouldResetPauseRelease(mode)).isTrue()
    }

    @Test
    fun `block until tomorrow rule deadline and behavior survive update`() {
        val mode = "BLOCK_UNTIL_TOMORROW:com.example.app"

        assertThat(
            AppUpdateUnlockResetPolicy.shouldResetPasswordRelease(
                lockMode = mode,
                lockUntilTimestamp = 123_456L
            )
        ).isFalse()
        assertThat(AppUpdateUnlockResetPolicy.shouldResetPauseRelease(mode)).isFalse()
    }

    @Test
    fun `password and time modes are not mistaken for pause state`() {
        assertThat(AppUpdateUnlockResetPolicy.shouldResetPauseRelease("PASSWORD")).isFalse()
        assertThat(AppUpdateUnlockResetPolicy.shouldResetPauseRelease("TIME")).isFalse()
    }
}
