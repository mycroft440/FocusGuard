package com.focusguard.security

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Biometria usada exclusivamente para abrir apps protegidos.
 *
 * Usa BIOMETRIC_STRONG sem DEVICE_CREDENTIAL. Assim, escolher "somente digital"
 * não permite que o PIN/padrão/senha do próprio aparelho substitua a biometria.
 */
object AppUnlockBiometricAuthenticator {

    fun interface AuthenticationHandle {
        /** Cancels a discarded surface without invoking its navigation callbacks. */
        fun cancel()
    }

    enum class Availability {
        AVAILABLE,
        ENROLLMENT_REQUIRED,
        UNAVAILABLE
    }

    fun availability(context: Context): Availability {
        val result = BiometricManager.from(context.applicationContext)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        return availabilityFromCanAuthenticateResult(result)
    }

    fun isAvailable(context: Context): Boolean = availability(context) == Availability.AVAILABLE

    internal fun availabilityFromCanAuthenticateResult(result: Int): Availability = when (result) {
        BiometricManager.BIOMETRIC_SUCCESS -> Availability.AVAILABLE
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> Availability.ENROLLMENT_REQUIRED
        else -> Availability.UNAVAILABLE
    }

    /**
     * Returns the narrowest Android enrollment surface available on this device.
     * Android 11+ receives the strong-biometric requirement explicitly. Older
     * versions fall back to the fingerprint enrollment action and, on OEMs that
     * do not expose it, to the general security settings screen.
     */
    fun createEnrollmentIntent(context: Context): Intent {
        val candidates = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                add(
                    Intent(Settings.ACTION_BIOMETRIC_ENROLL).putExtra(
                        Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED,
                        BiometricManager.Authenticators.BIOMETRIC_STRONG
                    )
                )
            }
            add(Intent("android.settings.FINGERPRINT_ENROLL"))
            add(Intent(Settings.ACTION_SECURITY_SETTINGS))
        }

        return candidates.firstOrNull { intent ->
            runCatching { intent.resolveActivity(context.packageManager) != null }
                .getOrDefault(false)
        } ?: Intent(Settings.ACTION_SECURITY_SETTINGS)
    }

    /**
     * Opens the strong-biometric prompt.
     *
     * [failureThresholdBeforeFallback] is optional so legacy callers keep the
     * previous behaviour. Password/pattern protected targets pass a positive
     * threshold: after that many consecutive rejected scans, or after a terminal
     * biometric error such as lockout, the caller can present its typed/drawn
     * fallback immediately.
     */
    fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        cancelLabel: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
        failureThresholdBeforeFallback: Int = 0,
        onFallbackRequested: () -> Unit = {},
        onCancelled: () -> Unit = {},
        onFinished: () -> Unit = {}
    ): AuthenticationHandle {
        if (!isAvailable(activity)) {
            onFinished()
            onError("Biometria forte indisponível neste aparelho")
            if (failureThresholdBeforeFallback > 0) onFallbackRequested()
            return AuthenticationHandle {}
        }

        val executor = ContextCompat.getMainExecutor(activity)
        lateinit var prompt: BiometricPrompt
        val callback = AppUnlockBiometricCallback(
            failureThresholdBeforeFallback = failureThresholdBeforeFallback,
            cancelPrompt = { prompt.cancelAuthentication() },
            onSuccess = onSuccess,
            onError = onError,
            onFallbackRequested = onFallbackRequested,
            onCancelled = onCancelled,
            onFinished = onFinished
        )

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText(cancelLabel)
            .setConfirmationRequired(false)
            .build()

        try {
            prompt = BiometricPrompt(activity, executor, callback)
            prompt.authenticate(promptInfo)
        } catch (error: RuntimeException) {
            callback.onAuthenticationError(
                BiometricPrompt.ERROR_HW_UNAVAILABLE,
                error.message ?: "Biometria forte indisponível neste aparelho"
            )
        }
        return AuthenticationHandle(callback::cancel)
    }
}

/** A rejected scan is non-terminal; every terminal result is delivered once. */
internal class AppUnlockBiometricCallback(
    private val failureThresholdBeforeFallback: Int,
    private val cancelPrompt: () -> Unit,
    private val onSuccess: () -> Unit,
    private val onError: (String) -> Unit,
    private val onFallbackRequested: () -> Unit,
    private val onCancelled: () -> Unit,
    private val onFinished: () -> Unit
) : BiometricPrompt.AuthenticationCallback() {
    private var consecutiveFailures = 0
    private var finished = false

    private fun finish(deliverResult: () -> Unit) {
        if (finished) return
        finished = true
        onFinished()
        deliverResult()
    }

    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
        finish(onSuccess)
    }

    override fun onAuthenticationFailed() {
        if (finished) return
        consecutiveFailures++
        if (
            failureThresholdBeforeFallback > 0 &&
            consecutiveFailures >= failureThresholdBeforeFallback
        ) {
            finish {
                // Ignore the later ERROR_CANCELED from this cancellation so it
                // cannot reopen an old password dialog over a subsequent prompt.
                cancelPrompt()
                onFallbackRequested()
            }
        } else {
            onError("Biometria não reconhecida")
        }
    }

    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
        finish {
            val cancelled = errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                errorCode == BiometricPrompt.ERROR_CANCELED
            if (cancelled) {
                onCancelled()
            } else {
                onError(errString.toString())
                if (failureThresholdBeforeFallback > 0) onFallbackRequested()
            }
        }
    }

    fun cancel() = finish(cancelPrompt)
}
