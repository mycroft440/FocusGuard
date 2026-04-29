package com.focusguard.security

import android.content.Context
import android.content.SharedPreferences
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.security.MessageDigest
import java.security.SecureRandom

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

    fun isSafetyModeEnabled(): Boolean {
        return prefs.getBoolean("safety_mode_enabled", false)
    }

    fun setSafetyModeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("safety_mode_enabled", enabled).apply()
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

    // --- Labeled password storage (ordered list) ---
    // Format: "label|hash|salt" stored as ordered entries in a StringSet with index prefix
    // e.g. "0:Senha Principal|abc123...|xyz789...", "1:Senha Backup|def456...|uvw321..."
    
    private fun getPasswordEntries(): List<Triple<String, String, String?>> {
        val entries = prefs.getStringSet("app_password_entries", emptySet()) ?: emptySet()
        return entries.sortedBy { 
            val idx = it.substringBefore(":").toIntOrNull() ?: 0
            idx
        }.map { entry ->
            val withoutIndex = entry.substringAfter(":")
            val parts = withoutIndex.split("|")
            val label = parts.getOrNull(0) ?: "Senha"
            val hash = parts.getOrNull(1) ?: ""
            val salt = parts.getOrNull(2) // Pode ser null para hashes legados
            Triple(label, hash, salt)
        }
    }

    private fun savePasswordEntries(entries: List<Triple<String, String, String?>>) {
        val indexed = entries.mapIndexed { index, (label, hash, salt) -> 
            val suffix = if (salt != null) "|$hash|$salt" else "|$hash"
            "$index:$label$suffix"
        }.toSet()
        
        val hashes = entries.map { it.second }.toSet()
        
        prefs.edit()
            .putStringSet("app_password_entries", indexed)
            .putStringSet("app_password_hashes", hashes)
            .apply()
    }

    fun addPasswordWithLabel(password: String, label: String) {
        val salt = generateSalt()
        val hash = hashPasswordWithSalt(password, salt)
        val current = getPasswordEntries().toMutableList()
        current.add(Triple(label, hash, salt))
        savePasswordEntries(current)
    }

    fun getStoredPasswordLabels(): List<String> {
        return getPasswordEntries().map { it.first }
    }

    fun removePasswordByIndex(index: Int) {
        val current = getPasswordEntries().toMutableList()
        if (index in current.indices) {
            current.removeAt(index)
            savePasswordEntries(current)
        }
    }

    fun verifyAndRemovePasswordByIndex(index: Int, passwordAttempt: String): Boolean {
        if (verifyPassword(passwordAttempt)) {
            removePasswordByIndex(index)
            return true
        }
        return false
    }

    fun verifyAndUpdatePasswordByIndex(index: Int, passwordAttempt: String, newPassword: String, label: String): Boolean {
        if (verifyPassword(passwordAttempt)) {
            updatePasswordByIndex(index, newPassword, label)
            return true
        }
        return false
    }

    fun updatePasswordByIndex(index: Int, newPassword: String, label: String) {
        val current = getPasswordEntries().toMutableList()
        if (index in current.indices) {
            val salt = generateSalt()
            current[index] = Triple(label, hashPasswordWithSalt(newPassword, salt), salt)
            savePasswordEntries(current)
        }
    }

    // --- Authentication Type Management ---
    fun getPreferredAuthType(): String {
        return prefs.getString("preferred_auth_type", "NUMERIC") ?: "NUMERIC"
    }

    fun setPreferredAuthType(type: String) {
        prefs.edit().putString("preferred_auth_type", type).apply()
    }

    fun addPassword(newPassword: String) {
        val entries = getPasswordEntries().toMutableList()
        val label = "Senha ${entries.size + 1}"
        addPasswordWithLabel(newPassword, label)
    }

    fun verifyPassword(passwordAttempt: String): Boolean {
        val entries = getPasswordEntries()
        
        for (entry in entries) {
            val (_, storedHash, salt) = entry
            val hashToCompare = if (salt != null) {
                hashPasswordWithSalt(passwordAttempt, salt)
            } else {
                hashPasswordLegacy(passwordAttempt)
            }
            
            if (hashToCompare == storedHash) {
                resetFailedAttempts()
                return true
            }
        }
        return false
    }

    fun removePassword(passwordToRemove: String) {
        // Obsoleto: usar removePasswordByIndex para maior precisÃ£o
        val entries = getPasswordEntries().toMutableList()
        val toRemove = entries.find { (label, storedHash, salt) ->
            val hash = if (salt != null) hashPasswordWithSalt(passwordToRemove, salt) else hashPasswordLegacy(passwordToRemove)
            hash == storedHash
        }
        if (toRemove != null) {
            entries.remove(toRemove)
            savePasswordEntries(entries)
        }
    }

    fun removeAllPasswords() {
        prefs.edit().remove("app_password_hashes").remove("app_password_entries").apply()
    }

    private fun generateSalt(): String {
        val random = SecureRandom()
        val salt = ByteArray(16)
        random.nextBytes(salt)
        return salt.joinToString("") { "%02x".format(it) }
    }

    private fun hashPasswordWithSalt(password: String, salt: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(salt.toByteArray())
        val digest = md.digest(password.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun hashPasswordLegacy(password: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(password.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
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
                            onError("Falha na autenticaÃ§Ã£o biomÃ©trica.")
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
                onError("Biometria indisponÃ­vel. Use a senha.")
            }
        }
    }
}