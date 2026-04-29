package com.focusguard.security

import android.content.Context
import android.content.SharedPreferences
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.focusguard.database.AppDatabase
import com.focusguard.database.AppPassword
import com.focusguard.utils.SecurePrefsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.security.MessageDigest
import java.security.SecureRandom

class AuthManager(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("FocusGuardAuth", Context.MODE_PRIVATE)
    private val securePrefs = SecurePrefsManager(context)
    private val database = AppDatabase.getDatabase(context)
    private val passwordDao = database.appPasswordDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        migrateSharedPreferencesToRoom()
        migratePrefsToSecure()
    }

    private fun migratePrefsToSecure() {
        val keysToMigrate = listOf("max_password_attempts", "photo_capture_enabled", "safety_mode_enabled", "failed_password_attempts", "preferred_auth_type")
        keysToMigrate.forEach { key ->
            if (prefs.contains(key) && !securePrefs.prefs.contains(key)) {
                when (val value = prefs.all[key]) {
                    is Int -> securePrefs.putInt(key, value)
                    is Boolean -> securePrefs.putBoolean(key, value)
                    is String -> securePrefs.putString(key, value)
                }
            }
        }
    }

    private fun migrateSharedPreferencesToRoom() {
        val entries = prefs.getStringSet("app_password_entries", null)
        if (entries != null && entries.isNotEmpty()) {
            runBlocking(Dispatchers.IO) {
                val dbEntries = passwordDao.getAllStatic()
                if (dbEntries.isEmpty()) {
                    entries.forEach { entry ->
                        val withoutIndex = entry.substringAfter(":")
                        val parts = withoutIndex.split("|")
                        val label = parts.getOrNull(0) ?: "Senha"
                        val hash = parts.getOrNull(1) ?: ""
                        val salt = parts.getOrNull(2)
                        passwordDao.insert(AppPassword(label = label, passwordHash = hash, salt = salt))
                    }
                }
                prefs.edit().remove("app_password_entries").remove("app_password_hashes").apply()
            }
        }
    }

    suspend fun isAppLocked(): Boolean {
        return hasPasswordSet()
    }

    suspend fun hasPasswordSet(): Boolean {
        return passwordDao.getAllStatic().isNotEmpty()
    }

    fun getMaxPasswordAttempts(): Int {
        return securePrefs.getInt("max_password_attempts", 0)
    }

    fun setMaxPasswordAttempts(limit: Int) {
        securePrefs.putInt("max_password_attempts", limit)
    }

    fun isPhotoCaptureEnabled(): Boolean {
        return securePrefs.getBoolean("photo_capture_enabled", false)
    }

    fun setPhotoCaptureEnabled(enabled: Boolean) {
        securePrefs.putBoolean("photo_capture_enabled", enabled)
    }

    fun isSafetyModeEnabled(): Boolean {
        return securePrefs.getBoolean("safety_mode_enabled", false)
    }

    fun setSafetyModeEnabled(enabled: Boolean) {
        securePrefs.putBoolean("safety_mode_enabled", enabled)
    }

    fun getFailedAttempts(): Int {
        return securePrefs.getInt("failed_password_attempts", 0)
    }

    fun incrementFailedAttempts(): Int {
        val count = getFailedAttempts() + 1
        securePrefs.putInt("failed_password_attempts", count)
        return count
    }

    fun resetFailedAttempts() {
        securePrefs.putInt("failed_password_attempts", 0)
    }

    suspend fun getStoredPasswordLabels(): List<String> {
        return passwordDao.getAllStatic().map { it.label }
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

    suspend fun verifyAndRemovePasswordByIndex(index: Int, passwordAttempt: String): Boolean {
        val entries = passwordDao.getAllStatic()
        if (index in entries.indices) {
            val entry = entries[index]
            val hash = if (entry.salt != null) hashPasswordWithSalt(passwordAttempt, entry.salt) else hashPasswordLegacy(passwordAttempt)
            if (hash == entry.passwordHash) {
                passwordDao.delete(entry)
                resetFailedAttempts()
                return true
            }
        }
        return false
    }

    suspend fun verifyAndUpdatePasswordByIndex(index: Int, passwordAttempt: String, newPassword: String, label: String): Boolean {
        val entries = passwordDao.getAllStatic()
        if (index in entries.indices) {
            val entry = entries[index]
            val hash = if (entry.salt != null) hashPasswordWithSalt(passwordAttempt, entry.salt) else hashPasswordLegacy(passwordAttempt)
            if (hash == entry.passwordHash) {
                val newSalt = generateSalt()
                val newHash = hashPasswordWithSalt(newPassword, newSalt)
                passwordDao.update(entry.copy(label = label, passwordHash = newHash, salt = newSalt))
                resetFailedAttempts()
                return true
            }
        }
        return false
    }

    fun addPassword(newPassword: String) {
        scope.launch {
            val count = passwordDao.getAllStatic().size
            val label = "Senha ${count + 1}"
            addPasswordWithLabel(newPassword, label)
        }
    }

    suspend fun verifyPassword(passwordAttempt: String): Boolean {
        val entries = passwordDao.getAllStatic()
        for (entry in entries) {
            val hash = if (entry.salt != null) hashPasswordWithSalt(passwordAttempt, entry.salt) else hashPasswordLegacy(passwordAttempt)
            if (hash == entry.passwordHash) {
                resetFailedAttempts()
                return true
            }
        }
        return false
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

    fun getPreferredAuthType(): String {
        return securePrefs.getString("preferred_auth_type", "NUMERIC") ?: "NUMERIC"
    }

    fun setPreferredAuthType(type: String) {
        securePrefs.putString("preferred_auth_type", type)
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