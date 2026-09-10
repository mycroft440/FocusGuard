package com.focusguard.security

import androidx.biometric.BiometricPrompt
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import org.junit.Test

class AppUnlockBiometricCallbackTest {
    private val events = mutableListOf<String>()

    private fun callback(failureThreshold: Int = 2) = AppUnlockBiometricCallback(
        failureThresholdBeforeFallback = failureThreshold,
        cancelPrompt = { events += "cancelPrompt" },
        onSuccess = { events += "success" },
        onError = { events += "error" },
        onFallbackRequested = { events += "fallback" },
        onCancelled = { events += "cancelled" },
        onFinished = { events += "finished" }
    )

    @Test
    fun `rejected scan keeps the current prompt active`() {
        val callback = callback()
        callback.onAuthenticationFailed()
        assertThat(events).containsExactly("error")

        callback.onAuthenticationSucceeded(mockk())
        assertThat(events).containsExactly("error", "finished", "success").inOrder()
    }

    @Test
    fun `fallback cancels once and ignores delayed cancellation and success`() {
        val callback = callback()
        callback.onAuthenticationFailed()
        callback.onAuthenticationFailed()
        callback.onAuthenticationError(BiometricPrompt.ERROR_CANCELED, "Cancelled")
        callback.onAuthenticationSucceeded(mockk())
        callback.onAuthenticationFailed()

        assertThat(events)
            .containsExactly("error", "finished", "cancelPrompt", "fallback").inOrder()
    }

    @Test
    fun `discarding the surface cancels without navigating or accepting late success`() {
        val callback = callback()
        callback.cancel()
        callback.cancel()
        callback.onAuthenticationError(BiometricPrompt.ERROR_CANCELED, "Cancelled")
        callback.onAuthenticationSucceeded(mockk())

        assertThat(events).containsExactly("finished", "cancelPrompt").inOrder()
    }

    @Test
    fun `terminal hardware error releases the prompt and offers credential fallback`() {
        val callback = callback()
        callback.onAuthenticationError(BiometricPrompt.ERROR_LOCKOUT, "Locked out")
        callback.onAuthenticationError(BiometricPrompt.ERROR_CANCELED, "Cancelled")
        assertThat(events).containsExactly("finished", "error", "fallback").inOrder()
    }

    @Test
    fun `biometric only keeps rejected scans active and never invents a fallback`() {
        val callback = callback(failureThreshold = 0)
        repeat(3) { callback.onAuthenticationFailed() }
        assertThat(events).containsExactly("error", "error", "error")
        callback.onAuthenticationError(BiometricPrompt.ERROR_LOCKOUT, "Locked out")
        assertThat(events)
            .containsExactly("error", "error", "error", "finished", "error").inOrder()
    }

    @Test
    fun `negative button delivers only one cancellation`() {
        val callback = callback()
        callback.onAuthenticationError(BiometricPrompt.ERROR_NEGATIVE_BUTTON, "Use password")
        callback.onAuthenticationError(BiometricPrompt.ERROR_CANCELED, "Cancelled")
        assertThat(events).containsExactly("finished", "cancelled").inOrder()
    }

    @Test
    fun `success releases the prompt once and ignores later terminal events`() {
        val callback = callback()
        callback.onAuthenticationSucceeded(mockk())
        callback.onAuthenticationSucceeded(mockk())
        callback.cancel()
        callback.onAuthenticationError(BiometricPrompt.ERROR_CANCELED, "Cancelled")
        assertThat(events).containsExactly("finished", "success").inOrder()
    }
}
