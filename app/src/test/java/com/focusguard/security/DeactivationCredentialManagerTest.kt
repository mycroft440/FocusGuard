package com.focusguard.security

import com.focusguard.security.DeactivationCredentialManager.VerificationResult
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DeactivationCredentialManagerTest {

    @Test
    fun `configured deactivation password is accepted`() {
        val passwordVerifier = DeactivationCredentialManager.serializeSecret(
            secret = "stop-blocking-123",
            salt = "00112233445566778899aabbccddeeff"
        )

        val result = DeactivationCredentialManager.classifyStoredCredential(
            credential = "stop-blocking-123",
            passwordVerifier = passwordVerifier,
            recoveryVerifier = null
        )

        assertThat(result).isEqualTo(VerificationResult.PASSWORD_ACCEPTED)
    }

    @Test
    fun `formatted recovery code is normalized and accepted`() {
        val recoveryVerifier = DeactivationCredentialManager.serializeSecret(
            secret = "ABCD2345EFGH6789",
            salt = "ffeeddccbbaa99887766554433221100"
        )

        val result = DeactivationCredentialManager.classifyStoredCredential(
            credential = "abcd-2345-efgh-6789",
            passwordVerifier = DeactivationCredentialManager.serializeSecret(
                secret = "different-password",
                salt = "00112233445566778899aabbccddeeff"
            ),
            recoveryVerifier = recoveryVerifier
        )

        assertThat(result).isEqualTo(VerificationResult.RECOVERY_ACCEPTED)
    }

    @Test
    fun `missing credential falls back to normal app authentication`() {
        val result = DeactivationCredentialManager.classifyStoredCredential(
            credential = "anything",
            passwordVerifier = null,
            recoveryVerifier = null
        )

        assertThat(result).isEqualTo(VerificationResult.NOT_CONFIGURED)
    }

    @Test
    fun `invalid deactivation credential is rejected`() {
        val result = DeactivationCredentialManager.classifyStoredCredential(
            credential = "wrong",
            passwordVerifier = DeactivationCredentialManager.serializeSecret(
                secret = "stop-blocking-123",
                salt = "00112233445566778899aabbccddeeff"
            ),
            recoveryVerifier = DeactivationCredentialManager.serializeSecret(
                secret = "ABCD2345EFGH6789",
                salt = "ffeeddccbbaa99887766554433221100"
            )
        )

        assertThat(result).isEqualTo(VerificationResult.REJECTED)
    }

    @Test
    fun `deactivation password requires eight characters`() {
        assertThat(DeactivationCredentialManager.isPasswordValid("1234567")).isFalse()
        assertThat(DeactivationCredentialManager.isPasswordValid("12345678")).isTrue()
    }
}
