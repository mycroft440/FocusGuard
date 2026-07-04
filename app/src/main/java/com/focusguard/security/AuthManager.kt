package com.focusguard.security

import android.content.Context
import com.focusguard.R
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
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Manager de autenticação: senhas, biometria, migrações.
 *
 * ANTES: era instanciado diretamente em 5+ lugares (`AuthManager(this)`),
 * cada instância disparava sua própria migrationJob no init — race condition.
 *
 * AGORA: o init é idempotente (migrationJob é companion-level singleton),
 * garantindo que a migração roda uma única vez mesmo se múltiplas instâncias
 * de AuthManager forem criadas. Idealmente, todos os callers devem usar Hilt
 * para obter a mesma instância singleton.
 */
class AuthManager(context: Context) {
    private val appContext = context.applicationContext

    private val prefs: SharedPreferences = appContext.getSharedPreferences("FocusGuardAuth", Context.MODE_PRIVATE)
    private val securePrefs = SecurePrefsManager(appContext)
    private val database = AppDatabase.getDatabase(appContext)
    private val passwordDao = database.appPasswordDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val migrationJob = scope.launch {
        try {
            // Sincroniza via companion-level flag — múltiplas instâncias de
            // AuthManager (legado) não disparam migração em paralelo.
            runMigrationOnce()
        } catch (e: Throwable) {
            com.focusguard.utils.FocusGuardLogger.logError("AuthManager", "Falha na migração inicial", e)
        }
    }

    private suspend fun ensureMigrationDone() {
        migrationJob.join()
    }

    private suspend fun runMigrationOnce() {
        // Companion-level synchronization — garante que apenas a primeira
        // instância de AuthManager dispare a migração Room/SecurePrefs.
        synchronized(migrationLock) {
            if (migrationCompleted) return
            migrateSharedPreferencesToRoom()
            migratePrefsToSecure()
            migrationCompleted = true
        }
    }

    private fun migratePrefsToSecure() {
        val keysToMigrate = listOf("max_password_attempts", "photo_capture_enabled", "safety_mode_enabled", "failed_password_attempts", "preferred_auth_type", "adult_filter_enabled")
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

    private suspend fun migrateSharedPreferencesToRoom() {
        val entries = prefs.getStringSet("app_password_entries", null)
        if (entries != null && entries.isNotEmpty()) {
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

    /**
     * App está bloqueado por senha (precisa de auth para entrar).
     * Implementação idêntica a [hasPasswordSet] — mantido como alias semântico
     * para callers que querem checar "está bloqueado?" (UX) em vez de
     * "tem senha?" (configuração).
     */
    suspend fun isAppLocked(): Boolean = hasPasswordSet()

    suspend fun hasPasswordSet(): Boolean {
        ensureMigrationDone()
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

    fun isAdultFilterEnabled(): Boolean {
        return securePrefs.getBoolean("adult_filter_enabled", false)
    }

    fun setAdultFilterEnabled(enabled: Boolean) {
        securePrefs.putBoolean("adult_filter_enabled", enabled)
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
        ensureMigrationDone()
        return passwordDao.getAllStatic().map { it.label }
    }

    fun addPasswordWithLabel(password: String, label: String) {
        scope.launch {
            addPasswordWithLabelSuspend(password, label)
        }
    }

    suspend fun addPasswordWithLabelSuspend(password: String, label: String) {
        ensureMigrationDone()
        insertPassword(password = password, label = label.ifBlank { "Senha" })
    }

    private suspend fun insertPassword(password: String, label: String) {
        val salt = generateSalt()
        val hash = hashPasswordWithSalt(password, salt)
        passwordDao.insert(AppPassword(label = label, passwordHash = hash, salt = salt))
    }

    fun removePasswordByIndex(index: Int) {
        scope.launch {
            removePasswordByIndexSuspend(index)
        }
    }

    suspend fun removePasswordByIndexSuspend(index: Int): Boolean {
        ensureMigrationDone()
        val entries = passwordDao.getAllStatic()
        return if (index in entries.indices) {
            passwordDao.delete(entries[index])
            true
        } else {
            false
        }
    }

    suspend fun verifyAndRemovePasswordByIndex(index: Int, passwordAttempt: String): Boolean {
        ensureMigrationDone()
        val entries = passwordDao.getAllStatic()
        if (index in entries.indices) {
            val entry = entries[index]
            if (verifyPasswordHash(passwordAttempt, entry.salt, entry.passwordHash)) {
                passwordDao.delete(entry)
                resetFailedAttempts()
                return true
            }
        }
        return false
    }

    suspend fun updatePasswordByIndex(index: Int, newPassword: String, label: String): Boolean {
        ensureMigrationDone()
        val entries = passwordDao.getAllStatic()
        if (index !in entries.indices) return false

        val entry = entries[index]
        val newSalt = generateSalt()
        val newHash = hashPasswordWithSalt(newPassword, newSalt)
        passwordDao.update(entry.copy(label = label.ifBlank { entry.label }, passwordHash = newHash, salt = newSalt))
        resetFailedAttempts()
        return true
    }

    suspend fun verifyAndUpdatePasswordByIndex(index: Int, passwordAttempt: String, newPassword: String, label: String): Boolean {
        ensureMigrationDone()
        val entries = passwordDao.getAllStatic()
        if (index in entries.indices) {
            val entry = entries[index]
            if (verifyPasswordHash(passwordAttempt, entry.salt, entry.passwordHash)) {
                val newSalt = generateSalt()
                val newHash = hashPasswordWithSalt(newPassword, newSalt)
                passwordDao.update(entry.copy(label = label.ifBlank { entry.label }, passwordHash = newHash, salt = newSalt))
                resetFailedAttempts()
                return true
            }
        }
        return false
    }

    fun addPassword(newPassword: String) {
        scope.launch {
            ensureMigrationDone()
            val count = passwordDao.getAllStatic().size
            insertPassword(password = newPassword, label = appContext.getString(R.string.senha_count_1))
        }
    }

    suspend fun verifyPassword(passwordAttempt: String): Boolean {
        ensureMigrationDone()
        val entries = passwordDao.getAllStatic()
        for (entry in entries) {
            if (verifyPasswordHash(passwordAttempt, entry.salt, entry.passwordHash)) {
                resetFailedAttempts()
                return true
            }
        }
        return false
    }

    fun removeAllPasswords() {
        scope.launch {
            ensureMigrationDone()
            passwordDao.deleteAll()
        }
    }

    companion object {
        // Guarda de migração companion-level — garante que múltiplas instâncias
        // de AuthManager (criadas por callers legados) não disparem migração
        // em paralelo. Quando todos os callers forem migrados para Hilt, isso
        // pode ser removido (Hilt garante singleton).
        private val migrationLock = Any()
        @Volatile
        private var migrationCompleted = false

        fun generateSalt(): String {
            val random = SecureRandom()
            val salt = ByteArray(16)
            random.nextBytes(salt)
            return salt.joinToString("") { "%02x".format(it) }
        }

        /**
         * Hash de senha usando PBKDF2-HMAC-SHA256 com 100k iterações.
         *
         * ANTIGO: scheme custom não-padronizado com 5000 iterações de
         * MessageDigest.update/digest. Não era PBKDF2 padrão e tinha análise
         * criptográfica limitada.
         *
         * NOVO: PBKDF2 é o padrão NIST para derivação de chaves baseada em
         * senha. 100k iterações é o recomendado pela OWASP para SHA-256 (2023).
         *
         * MANTIDO: o scheme legado é preservado como `hashPasswordLegacySalted`
         * para que senhas existentes continuem válidas. Novas senhas usam PBKDF2.
         */
        fun hashPasswordWithSalt(password: String, salt: String): String {
            return try {
                val keySpec = PBEKeySpec(password.toCharArray(), salt.hexToByteArray(), 100_000, 256)
                val keyFactory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                val hash = keyFactory.generateSecret(keySpec).encoded
                // Prefixo "v2:" marca senhas com novo scheme PBKDF2 para
                // distinguishability na verificação (se将来 quisermos migrar
                // senhas legadas ativamente).
                "v2:" + hash.joinToString("") { "%02x".format(it) }
            } catch (e: Throwable) {
                // Fallback para o scheme legado se PBKDF2 falhar (improvável,
                // mas evita lockout total)
                hashPasswordLegacySalted(password, salt)
            }
        }

        /**
         * Scheme legado (pré-PBKDF2) — preservado para verificação de senhas
         * existentes. NÃO usar para novas senhas.
         */
        private fun hashPasswordLegacySalted(password: String, salt: String): String {
            val iterations = 5000
            var hash = salt.toByteArray()
            val md = MessageDigest.getInstance("SHA-256")

            repeat(iterations) {
                md.update(hash)
                hash = md.digest(password.toByteArray())
            }

            return hash.joinToString("") { "%02x".format(it) }
        }

        fun hashPasswordLegacy(password: String): String {
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(password.toByteArray())
            return digest.joinToString("") { "%02x".format(it) }
        }

        /**
         * Comparação constante de tempo para evitar timing attacks.
         *
         * ANTIGO: `hash == entry.passwordHash` — comparação de String que
         * faz short-circuit no primeiro byte diferente, permitindo ataque
         * de timing para descobrir o hash byte a byte.
         *
         * NOVO: MessageDigest.isEqual faz comparação byte a byte em tempo
         * constante (mesmo tempo para igual e diferente).
         */
        private fun constantTimeEquals(a: String, b: String): Boolean {
            val aBytes = a.toByteArray()
            val bBytes = b.toByteArray()
            return MessageDigest.isEqual(aBytes, bBytes)
        }

        // Extensão privada para converter hex string em ByteArray
        private fun String.hexToByteArray(): ByteArray {
            return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }
    }

    private fun generateSalt(): String = AuthManager.generateSalt()
    private fun hashPasswordWithSalt(password: String, salt: String): String = AuthManager.hashPasswordWithSalt(password, salt)
    private fun hashPasswordLegacy(password: String): String = AuthManager.hashPasswordLegacy(password)

    /**
     * Verifica senha contra hash armazenado, suportando:
     *   - scheme v2 (PBKDF2, prefixado com "v2:")
     *   - scheme v1 (legacy com salt)
     *   - scheme v0 (legacy sem salt)
     *
     * Usa comparação constante de tempo (MessageDigest.isEqual) para evitar
     * timing attacks.
     */
    private fun verifyPasswordHash(passwordAttempt: String, salt: String?, storedHash: String): Boolean {
        return try {
            val computedHash = when {
                // v2: PBKDF2 (prefixo "v2:" marca o novo scheme)
                salt != null && storedHash.startsWith("v2:") ->
                    hashPasswordWithSalt(passwordAttempt, salt)
                // v1: legacy com salt (sem prefixo)
                salt != null -> AuthManager.run {
                    val iterations = 5000
                    var hash = salt.toByteArray()
                    val md = MessageDigest.getInstance("SHA-256")
                    repeat(iterations) {
                        md.update(hash)
                        hash = md.digest(passwordAttempt.toByteArray())
                    }
                    hash.joinToString("") { "%02x".format(it) }
                }
                // v0: legacy sem salt
                else -> hashPasswordLegacy(passwordAttempt)
            }
            constantTimeEquals(computedHash, storedHash)
        } catch (e: Throwable) {
            false
        }
    }

    fun getPreferredAuthType(): String {
        return securePrefs.getString("preferred_auth_type", "NUMERIC") ?: "NUMERIC"
    }

    fun setPreferredAuthType(type: String) {
        securePrefs.putString("preferred_auth_type", type)
    }

    fun isBiometricAvailable(): Boolean {
        val biometricManager = BiometricManager.from(appContext)
        return biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS
    }

    fun showBiometricPrompt(
        activity: FragmentActivity,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val biometricManager = BiometricManager.from(appContext)
        when (biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)) {
            BiometricManager.BIOMETRIC_SUCCESS -> {
                val executor = ContextCompat.getMainExecutor(appContext)
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