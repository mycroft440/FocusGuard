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

    // --- Labeled password storage (ordered list) ---
    // Format: "label|hash" stored as ordered entries in a StringSet with index prefix
    // e.g. "0:Senha Principal|abc123...", "1:Senha Backup|def456..."
    
    private fun getPasswordEntries(): List<Pair<String, String>> {
        val entries = prefs.getStringSet("app_password_entries", emptySet()) ?: emptySet()
        return entries.sortedBy { 
            val idx = it.substringBefore(":").toIntOrNull() ?: 0
            idx
        }.map { entry ->
            val withoutIndex = entry.substringAfter(":")
            val label = withoutIndex.substringBefore("|")
            val hash = withoutIndex.substringAfter("|")
            label to hash
        }
    }

    private fun savePasswordEntries(entries: List<Pair<String, String>>) {
        val indexed = entries.mapIndexed { index, (label, hash) -> "$index:$label|$hash" }.toSet()
        val hashes = entries.map { it.second }.toSet()
        // Single atomic transaction to keep both stores in sync
        prefs.edit()
            .putStringSet("app_password_entries", indexed)
            .putStringSet("app_password_hashes", hashes)
            .apply()
    }

    fun addPasswordWithLabel(password: String, label: String) {
        val hash = hashPassword(password)
        val current = getPasswordEntries().toMutableList()
        current.add(label to hash)
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

    fun addPassword(newPassword: String) {
        val hash = hashPassword(newPassword)
        val entries = getPasswordEntries().toMutableList()
        val label = "Senha ${entries.size + 1}"
        entries.add(label to hash)
        savePasswordEntries(entries)
    }

    fun verifyPassword(password: String): Boolean {
        val limit = getMaxPasswordAttempts()
        val currentFailed = getFailedAttempts()
        
        // Regra do Usuário: Imprimir dados das primeiras 5 tentativas
        if (currentFailed < 5) {
            android.util.Log.d("NuclearOption", "Tentativa de Senha #${currentFailed + 1}: Limite=$limit, Atual=$currentFailed")
        }

        if (limit > 0 && currentFailed >= limit) {
            android.util.Log.w("NuclearOption", "Limite de tentativas atingido ($limit). Acesso negado.")
            return false
        }
        
        val hash = hashPassword(password)
        val isValid = getPasswordHashes().contains(hash)
        
        if (isValid) {
            resetFailedAttempts()
            android.util.Log.d("NuclearOption", "Senha correta. Contador resetado.")
        } else {
            val newCount = incrementFailedAttempts()
            android.util.Log.w("NuclearOption", "Senha incorreta. Novo contador: $newCount")
        }
        
        return isValid
    }

    fun removePassword(passwordToRemove: String) {
        val hash = hashPassword(passwordToRemove)
        val current = getPasswordEntries().toMutableList()
        val updated = current.filter { it.second != hash }
        savePasswordEntries(updated)
    }

    fun removeAllPasswords() {
        prefs.edit().remove("app_password_hashes").remove("app_password_entries").apply()
    }

    private fun hashPassword(password: String): String {
        // Simple static salt for basic protection against rainbow tables
        // In a full implementation, a per-user random salt would be better
        val salt = "FocusGuard_Static_Salt_2024"
        val bytes = (password + salt).toByteArray()
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(bytes)
        return digest.fold("") { str, it -> str + "%02x".format(it) }
    }

    fun showBiometricPrompt(
        activity: FragmentActivity,
        title: String = "Desbloquear FocusGuard",
        subtitle: String = "Confirme sua identidade para acessar o app",
        onSuccess: () -> Unit,
        onError: (Int, String) -> Unit
    ) {
        val biometricManager = BiometricManager.from(context)
        when (biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)) {
            BiometricManager.BIOMETRIC_SUCCESS -> {
                val executor = ContextCompat.getMainExecutor(context)
                val biometricPrompt = BiometricPrompt(activity, executor,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                            super.onAuthenticationError(errorCode, errString)
                            onError(errorCode, errString.toString())
                        }

                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            super.onAuthenticationSucceeded(result)
                            onSuccess()
                        }

                        override fun onAuthenticationFailed() {
                            super.onAuthenticationFailed()
                            onError(-1, "Falha na autenticação biométrica.")
                        }
                    })

                val promptInfo = BiometricPrompt.PromptInfo.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                    .build()

                biometricPrompt.authenticate(promptInfo)
            }
            else -> {
                onError(-2, "Biometria indisponível. Use a senha.")
            }
        }
    }
}
