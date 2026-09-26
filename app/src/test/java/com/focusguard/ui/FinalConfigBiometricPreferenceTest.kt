package com.focusguard.ui

import com.focusguard.security.PasswordAppUnlockMode
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FinalConfigBiometricPreferenceTest {
    @Test
    fun `typed credential does not silently inherit global biometric preference`() {
        assertThat(
  effectiveTargetBiometricEnabled(
      mode = PasswordAppUnlockMode.PASSWORD,
      requestedForTypedCredential = false,
      globalBiometricUnlockEnabled = true,
      biometricAvailable = true
  )
        ).isFalse()
    }

    @Test
    fun `typed credential uses biometric only after explicit target opt in`() {
        assertThat(
  effectiveTargetBiometricEnabled(
      mode = PasswordAppUnlockMode.PATTERN,
      requestedForTypedCredential = true,
      globalBiometricUnlockEnabled = true,
      biometricAvailable = true
  )
        ).isTrue()
    }

    @Test
    fun `biometric only still requires global entitlement and enrolled biometric`() {
        assertThat(
  effectiveTargetBiometricEnabled(
      mode = PasswordAppUnlockMode.BIOMETRIC_ONLY,
      requestedForTypedCredential = false,
      globalBiometricUnlockEnabled = true,
      biometricAvailable = true
  )
        ).isTrue()
        assertThat(
  effectiveTargetBiometricEnabled(
      mode = PasswordAppUnlockMode.BIOMETRIC_ONLY,
      requestedForTypedCredential = false,
      globalBiometricUnlockEnabled = true,
      biometricAvailable = false
  )
        ).isFalse()
    }
}
