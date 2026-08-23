package com.focusguard.security

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TimedBlockProtectionPolicyTest {

    @Test
    fun `active finite time block requires uninstall protection`() {
        assertThat(
            TimedBlockProtectionPolicy.requiresUninstallProtection(
                sessionType = "TIME",
                isActive = true,
                endTimeMillis = 20_000L,
                nowMillis = 10_000L
            )
        ).isTrue()
    }

    @Test
    fun `open ended time block requires uninstall protection`() {
        assertThat(
            TimedBlockProtectionPolicy.requiresUninstallProtection(
                sessionType = "TIME",
                isActive = true,
                endTimeMillis = null,
                nowMillis = 10_000L
            )
        ).isTrue()
    }

    @Test
    fun `expired time block releases uninstall protection`() {
        assertThat(
            TimedBlockProtectionPolicy.requiresUninstallProtection(
                sessionType = "TIME",
                isActive = true,
                endTimeMillis = 10_000L,
                nowMillis = 10_000L
            )
        ).isFalse()
    }

    @Test
    fun `inactive time block releases uninstall protection`() {
        assertThat(
            TimedBlockProtectionPolicy.requiresUninstallProtection(
                sessionType = "TIME",
                isActive = false,
                endTimeMillis = 20_000L,
                nowMillis = 10_000L
            )
        ).isFalse()
    }

    @Test
    fun `password and pomodoro never opt into this uninstall guard`() {
        assertThat(
            TimedBlockProtectionPolicy.requiresUninstallProtection(
                sessionType = "PASSWORD",
                isActive = true,
                endTimeMillis = 20_000L,
                nowMillis = 10_000L
            )
        ).isFalse()
        assertThat(
            TimedBlockProtectionPolicy.requiresUninstallProtection(
                sessionType = "POMODORO",
                isActive = true,
                endTimeMillis = 20_000L,
                nowMillis = 10_000L
            )
        ).isFalse()
    }
}
