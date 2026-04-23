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

    init {
        // Migration from single password to multiple passwords
        val oldPassword = prefs.getString("app_password_hash", null)
        if (oldPassword != null) {
            val hashes = getPasswordHashes().toMutableSet()
            hashes.add(oldPassword)
            prefs.edit().putStringSet("app_password_hashes", hashes).remove("app_password_hash").apply()
        }
    }

    fun isAppLocked(): Boolean {
        return hasPasswordSet()
    }

    fun hasPasswordSet(): Boolean {
        return getPasswordHashes().isNotEmpty()
    }

    fun getMaxPasswordAttempts(): Int {
        return prefs.getInt("max_password_attempts", 0) // 0 means no limit
    }

    fun setMaxPasswordAttempts(limit: Int) {
        prefs.edit().putInt("max_password_attempts", limit).apply()
    }

    fun isPhotoCaptureEnabled(): Boolean {
        return prefs.getBoolean("photo_capture_enabled", false)
    }

    fun setPhotoCaptureEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("photo_capture_enabled", enabled).apply()
    }

    fun getFailedAttempts(): Int {
        return prefs.getInt("failed_password_attempts", 0)
    }

    fun incrementFailedAttempts(): Int {
        val count = getFailedAttempts() + 1
        prefs.edit().putInt("failed_password_attempts", count).apply()
        return count
    }

    fun resetFailedAttempts() {
        prefs.edit().putInt("failed_password_attempts", 0).apply()
    }

    private fun getPasswordHashes(): Set<String> {
        return prefs.getStringSet("app_password_hashes", emptySet()) ?: emptySet()
    }

    fun addPassword(newPassword: String) {
        val hash = hashPassword(newPassword)
        val currentHashes = getPasswordHashes().toMutableSet()
        currentHashes.add(hash)
        prefs.edit().putStringSet("app_password_hashes", currentHashes).apply()
    }

    fun verifyPassword(password: String): Boolean {
        val hash = hashPassword(password)
        val isValid = getPasswordHashes().contains(hash)
        if (isValid) {
            resetFailedAttempts()
        }
        return isValid
    }

    fun removePassword(passwordToRemove: String) {
        val hash = hashPassword(passwordToRemove)
        val currentHashes = getPasswordHashes().toMutableSet()
        currentHashes.remove(hash)
        prefs.edit().putStringSet("app_password_hashes", currentHashes).apply()
    }

    fun removeAllPasswords() {
        prefs.edit().remove("app_password_hashes").apply()
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
                onError("Biometria indisponível. Use a senha.")
            }
        }
    }
}
