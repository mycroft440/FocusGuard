package com.focusguard.security

import com.focusguard.security.AccessibilityMaintenanceCredentialManager.VerificationResult
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AccessibilityMaintenanceCredentialManagerTest {

    @Test
    fun `maintenance password is independent and accepted`() {
        val passwordVerifier =
            AccessibilityMaintenanceCredentialManager.serializeSecret(
                secret = "maintenance-123",
                salt = "00112233445566778899aabbccddeeff"
            )
        val recoveryVerifier =
            AccessibilityMaintenanceCredentialManager.serializeSecret(
                secret = "ABCD2345EFGH6789",
                salt = "ffeeddccbbaa99887766554433221100"
            )

        val result = AccessibilityMaintenanceCredentialManager.classifyStoredCredential(
            credential = "maintenance-123",
            passwordVerifier = passwordVerifier,
            recoveryVerifier = recoveryVerifier
        )

        assertThat(result).isEqualTo(VerificationResult.PASSWORD_ACCEPTED)
    }

    @Test
    fun `formatted recovery code is normalized and accepted`() {
        val recoveryVerifier =
            AccessibilityMaintenanceCredentialManager.serializeSecret(
                secret = "ABCD2345EFGH6789",
                salt = "ffeeddccbbaa99887766554433221100"
            )

        val result = AccessibilityMaintenanceCredentialManager.classifyStoredCredential(
            credential = "abcd-2345-efgh-6789",
            passwordVerifier = AccessibilityMaintenanceCredentialManager.serializeSecret(
                secret = "different-password",
                salt = "00112233445566778899aabbccddeeff"
            ),
            recoveryVerifier = recoveryVerifier
        )

        assertThat(result).isEqualTo(VerificationResult.RECOVERY_ACCEPTED)
    }

    @Test
    fun `missing maintenance credential is reported`() {
        val result = AccessibilityMaintenanceCredentialManager.classifyStoredCredential(
            credential = "anything",
            passwordVerifier = null,
            recoveryVerifier = null
        )

        assertThat(result).isEqualTo(VerificationResult.NOT_CONFIGURED)
    }

    @Test
    fun `invalid credential is rejected`() {
        val result = AccessibilityMaintenanceCredentialManager.classifyStoredCredential(
            credential = "wrong",
            passwordVerifier = AccessibilityMaintenanceCredentialManager.serializeSecret(
                secret = "maintenance-123",
                salt = "00112233445566778899aabbccddeeff"
            ),
            recoveryVerifier = AccessibilityMaintenanceCredentialManager.serializeSecret(
                secret = "ABCD2345EFGH6789",
                salt = "ffeeddccbbaa99887766554433221100"
            )
        )

        assertThat(result).isEqualTo(VerificationResult.REJECTED)
    }

    @Test
    fun `maintenance password requires eight characters`() {
        assertThat(
            AccessibilityMaintenanceCredentialManager.isPasswordValid("1234567")
        ).isFalse()
        assertThat(
            AccessibilityMaintenanceCredentialManager.isPasswordValid("12345678")
        ).isTrue()
    }
}
