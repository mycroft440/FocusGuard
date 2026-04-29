package com.focusguard.security

import android.content.Context
import android.content.SharedPreferences
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.focusguard.database.AppDatabase
import com.focusguard.database.AppPassword
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.security.MessageDigest
import java.security.SecureRandom

class AuthManager(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("FocusGuardAuth", Context.MODE_PRIVATE)
    private val database = AppDatabase.getDatabase(context)
    private val passwordDao = database.appPasswordDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        migrateSharedPreferencesToRoom()
    }

    private fun migrateSharedPreferencesToRoom() {
        // Verifica se hÃ¡ algo no formato antigo "app_password_entries"
        val entries = prefs.getStringSet("app_password_entries", null)
        if (entries != null && entries.isNotEmpty()) {
            runBlocking(Dispatchers.IO) {
                val dbEntries = passwordDao.getAllStatic()
                if (dbEntries.isEmpty()) {
                    // Migra apenas se o banco estiver vazio para evitar duplicatas
                    entries.forEach { entry ->
                        val withoutIndex = entry.substringAfter(":")
                        val parts = withoutIndex.split("|")
                        val label = parts.getOrNull(0) ?: "Senha"
                        val hash = parts.getOrNull(1) ?: ""
                        val salt = parts.getOrNull(2)
                        passwordDao.insert(AppPassword(label = label, passwordHash = hash, salt = salt))
                    }
                }
                // Limpa o SharedPreferences apÃ³s migraÃ§Ã£o bem sucedida
                prefs.edit().remove("app_password_entries").remove("app_password_hashes").apply()
            }
        }
    }

    fun isAppLocked(): Boolean {
        return hasPasswordSet()
    }

    fun hasPasswordSet(): Boolean {
        return runBlocking(Dispatchers.IO) {
            passwordDao.getAllStatic().isNotEmpty()
        }
    }

    fun getMaxPasswordAttempts(): Int {
        return prefs.getInt("max_password_attempts", 0)
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

    fun getStoredPasswordLabels(): List<String> {
        return runBlocking(Dispatchers.IO) {
            passwordDao.getAllStatic().map { it.label }
        }
    }

    fun addPasswordWithLabel(password: String, label: String) {
        val salt = generateSalt()
        val hash = hashPasswordWithSalt(password, salt)
        scope.launch {
            passwordDao.insert(AppPassword(label = label, passwordHash = hash, salt = salt))
        }
    }

    fun removePasswordByIndex(index: Int) {
        scope.launch {
            val entries = passwordDao.getAllStatic()
            if (index in entries.indices) {
                passwordDao.delete(entries[index])
            }
        }
    }

    fun verifyAndRemovePasswordByIndex(index: Int, passwordAttempt: String): Boolean {
        return runBlocking(Dispatchers.IO) {
            val entries = passwordDao.getAllStatic()
            if (index in entries.indices) {
                val entry = entries[index]
                val hash = if (entry.salt != null) hashPasswordWithSalt(passwordAttempt, entry.salt) else hashPasswordLegacy(passwordAttempt)
                if (hash == entry.passwordHash) {
                    passwordDao.delete(entry)
                    resetFailedAttempts()
                    return@runBlocking true
                }
            }
            false
        }
    }

    fun verifyAndUpdatePasswordByIndex(index: Int, passwordAttempt: String, newPassword: String, label: String): Boolean {
        return runBlocking(Dispatchers.IO) {
            val entries = passwordDao.getAllStatic()
            if (index in entries.indices) {
                val entry = entries[index]
                val hash = if (entry.salt != null) hashPasswordWithSalt(passwordAttempt, entry.salt) else hashPasswordLegacy(passwordAttempt)
                if (hash == entry.passwordHash) {
                    val newSalt = generateSalt()
                    val newHash = hashPasswordWithSalt(newPassword, newSalt)
                    passwordDao.update(entry.copy(label = label, passwordHash = newHash, salt = newSalt))
                    resetFailedAttempts()
                    return@runBlocking true
                }
            }
            false
        }
    }

    fun addPassword(newPassword: String) {
        scope.launch {
            val count = passwordDao.getAllStatic().size
            val label = "Senha ${count + 1}"
            addPasswordWithLabel(newPassword, label)
        }
    }

    fun verifyPassword(passwordAttempt: String): Boolean {
        return runBlocking(Dispatchers.IO) {
            val entries = passwordDao.getAllStatic()
            for (entry in entries) {
                val hash = if (entry.salt != null) hashPasswordWithSalt(passwordAttempt, entry.salt) else hashPasswordLegacy(passwordAttempt)
                if (hash == entry.passwordHash) {
                    resetFailedAttempts()
                    return@runBlocking true
                }
            }
            false
        }
    }

    fun removeAllPasswords() {
        scope.launch {
            passwordDao.deleteAll()
        }
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