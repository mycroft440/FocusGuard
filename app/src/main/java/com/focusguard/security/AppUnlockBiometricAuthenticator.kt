package com.focusguard.security

import android.content.Context
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

    fun isAvailable(context: Context): Boolean {
        return BiometricManager.from(context.applicationContext)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }

    fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        cancelLabel: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (!isAvailable(activity)) {
            onError("Biometria forte indisponível neste aparelho")
            return
        }

        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(
                    result: BiometricPrompt.AuthenticationResult
                ) {
                    super.onAuthenticationSucceeded(result)
                    onSuccess()
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    onError("Biometria não reconhecida")
                }

                override fun onAuthenticationError(
                    errorCode: Int,
                    errString: CharSequence
                ) {
                    super.onAuthenticationError(errorCode, errString)
                    if (
                        errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON &&
                        errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                        errorCode != BiometricPrompt.ERROR_CANCELED
                    ) {
                        onError(errString.toString())
                    }
                }
            }
        )

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText(cancelLabel)
            .setConfirmationRequired(false)
            .build()

        prompt.authenticate(promptInfo)
    }
}
