package com.focusguard.ui.compose.screens

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PasswordProtectionToggleStateTest {

    @Test
    fun `resume refresh reads newly enabled biometric preference`() {
        var biometricEnabled = false
        var selfieEnabled = false

        val initial = readPasswordProtectionToggleState(
            biometricEnabled = { biometricEnabled },
            selfieEnabled = { selfieEnabled }
        )

        biometricEnabled = true

        val resumed = readPasswordProtectionToggleState(
            biometricEnabled = { biometricEnabled },
            selfieEnabled = { selfieEnabled }
        )

        assertThat(initial.biometricEnabled).isFalse()
        assertThat(resumed.biometricEnabled).isTrue()
        assertThat(resumed.selfieEnabled).isFalse()
    }

    @Test
    fun `resume refresh reads newly enabled selfie preference`() {
        var biometricEnabled = false
        var selfieEnabled = false

        val initial = readPasswordProtectionToggleState(
            biometricEnabled = { biometricEnabled },
            selfieEnabled = { selfieEnabled }
        )

        selfieEnabled = true

        val resumed = readPasswordProtectionToggleState(
            biometricEnabled = { biometricEnabled },
            selfieEnabled = { selfieEnabled }
        )

        assertThat(initial.selfieEnabled).isFalse()
        assertThat(resumed.selfieEnabled).isTrue()
        assertThat(resumed.biometricEnabled).isFalse()
    }
}
