package com.focusguard.security

import androidx.biometric.BiometricPrompt
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AppUnlockBiometricCancelRoutingTest {

    private fun callback(log: MutableList<String>) = AppUnlockBiometricCallback(
        failureThresholdBeforeFallback = Int.MAX_VALUE,
        cancelPrompt = { log += "cancelPrompt" },
        onSuccess = { log += "success" },
        onError = { log += "error" },
        onFallbackRequested = { log += "fallback" },
        onCancelled = { log += "userCancelled" },
        onFinished = { log += "finished" },
        onNegativeButton = { log += "negative" },
        onSystemCancelled = { log += "systemCancelled" }
    )

    @Test
    fun `negative button opens the typed credential route`() {
        val log = mutableListOf<String>()
        callback(log).onAuthenticationError(BiometricPrompt.ERROR_NEGATIVE_BUTTON, "")
        assertThat(log).containsExactly("finished", "negative").inOrder()
    }

    @Test
    fun `tapping outside only closes the prompt`() {
        val log = mutableListOf<String>()
        callback(log).onAuthenticationError(BiometricPrompt.ERROR_USER_CANCELED, "")
        assertThat(log).containsExactly("finished", "userCancelled").inOrder()
    }

    @Test
    fun `system cancellation is reported separately so the prompt can return`() {
        val log = mutableListOf<String>()
        callback(log).onAuthenticationError(BiometricPrompt.ERROR_CANCELED, "")
        assertThat(log).containsExactly("finished", "systemCancelled").inOrder()
    }
}
