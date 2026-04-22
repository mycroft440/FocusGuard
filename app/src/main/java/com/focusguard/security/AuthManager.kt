package com.focusguard.security

import android.content.Context
import android.content.SharedPreferences
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.security.MessageDigest

class AuthManager(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("FocusGuardAuth", Context.MODE_PRIVATE)

    fun isAppLocked(): Boolean {
        // App is locked if a password is set OR if biometrics are enabled as a requirement
        return hasPasswordSet()
    }

    fun hasPasswordSet(): Boolean {
        return prefs.getString("app_password_hash", null) != null
    }

    fun setPassword(newPassword: String) {
        val hash = hashPassword(newPassword)
        prefs.edit().putString("app_password_hash", hash).apply()
    }

    fun verifyPassword(password: String): Boolean {
        val hash = hashPassword(password)
        val storedHash = prefs.getString("app_password_hash", null)
        return storedHash == hash
    }
    
    fun removePassword() {
        prefs.edit().remove("app_password_hash").apply()
    }

    private fun hashPassword(password: String): String {
        val bytes = password.toByteArray()
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(bytes)
        return digest.fold("") { str, it -> str + "%02x".format(it) }
    }

    fun showBiometricPrompt(
        activity: FragmentActivity,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val biometricManager = BiometricManager.from(context)
        when (biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)) {
            BiometricManager.BIOMETRIC_SUCCESS -> {
                val executor = ContextCompat.getMainExecutor(context)
                val biometricPrompt = BiometricPrompt(activity, executor,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                            super.onAuthenticationError(errorCode, errString)
                            onError(errString.toString())
                        }

                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            super.onAuthenticationSucceeded(result)
                            onSuccess()
                        }

                        override fun onAuthenticationFailed() {
                            super.onAuthenticationFailed()
                            onError("Falha na autenticação biométrica.")
                        }
                    })

                val promptInfo = BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Desbloquear FocusGuard")
                    .setSubtitle("Confirme sua identidade para acessar o app")
                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                    .build()

                biometricPrompt.authenticate(promptInfo)
            }
            else -> {
                // Biometria não disponível no dispositivo, deve cair para verificação de Senha Padrão
                onError("Biometria indisponível. Use a senha.")
            }
        }
    }
}
