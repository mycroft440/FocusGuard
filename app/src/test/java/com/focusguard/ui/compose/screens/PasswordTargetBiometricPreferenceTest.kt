package com.focusguard.ui.compose.screens

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PasswordTargetBiometricPreferenceTest {

    @Test
    fun `global preference off disables biometric even when target allows it`() {
        assertThat(
            isPasswordTargetBiometricAllowed(
                globalBiometricUnlockEnabled = false,
                targetBiometricEnabled = true
            )
        ).isFalse()
    }

    @Test
    fun `target preference off disables biometric even when global preference is on`() {
        assertThat(
            isPasswordTargetBiometricAllowed(
                globalBiometricUnlockEnabled = true,
                targetBiometricEnabled = false
            )
        ).isFalse()
    }

    @Test
    fun `biometric is allowed only when both preferences are enabled`() {
        assertThat(
            isPasswordTargetBiometricAllowed(
                globalBiometricUnlockEnabled = true,
                targetBiometricEnabled = true
            )
        ).isTrue()
    }
}
