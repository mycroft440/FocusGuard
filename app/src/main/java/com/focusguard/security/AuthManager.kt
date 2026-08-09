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
import kotlinx.coroutines.sync.withLock
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
        // Companion-level synchronization via Mutex — garante que apenas a
        // primeira instância de AuthManager dispare a migração Room/SecurePrefs.
        // Usamos Mutex (não synchronized) porque migrateSharedPreferencesToRoom
        // é suspend — Kotlin proíbe suspensão dentro de synchronized{}.
        //
        // Não podemos usar `return` dentro de withLock (não-locally), então
        // capturamos o resultado do bloco.
        val alreadyDone = migrationMutex.withLock {
            if (migrationCompleted) {
                true
            } else {
                migrateSharedPreferencesToRoom()
                migratePrefsToSecure()
                migrationCompleted = true
                false
            }
        }
        if (alreadyDone) return
    }

    private fun migratePrefsToSecure() {
        val keysToMigrate = listOf(
            "max_password_attempts",
            "photo_capture_enabled",
            "safety_mode_enabled",
            "failed_password_attempts",
            "preferred_auth_type",
            ADULT_FILTER_ENABLED_KEY
        )
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
     * A entrada no FocusGuard exige senha somente durante um bloqueio ativo
     * do tipo PASSWORD. Ter uma credencial armazenada, por si só, não deixa o
     * aplicativo permanentemente trancado depois que o bloqueio termina.
     */
    suspend fun isAppLocked(): Boolean {
        ensureMigrationDone()
        val hasStoredPassword = passwordDao.getAllStatic().isNotEmpty()
        val activeSessionTypes = database.blockSessionDao()
            .getAllActiveSessionsStatic()
            .map { it.sessionType }

        return AppEntryLockPolicy.requiresPassword(
            hasStoredPassword = hasStoredPassword,
            activeSessionTypes = activeSessionTypes
        )
    }

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

    /**
     * Opt-in: allow a fingerprint to stand in for the password when opening a
     * blocked app. Off by default — enabling it must be a deliberate choice,
     * since it lowers the friction the user asked for.
     *
     * Scope is enforced by [BiometricAppUnlockPolicy], not here: this flag only
     * expresses intent. It can never open a Dopamine Fast or a time-hardened
     * limit, no matter its value.
     */
    fun isBiometricAppUnlockEnabled(): Boolean {
        return securePrefs.getBoolean(BIOMETRIC_APP_UNLOCK_KEY, false)
    }

    fun setBiometricAppUnlockEnabled(enabled: Boolean) {
        securePrefs.putBoolean(BIOMETRIC_APP_UNLOCK_KEY, enabled)
    }

    /**
     * O modo global sem senha foi substituído pelos modos de cada limite.
     * Limpa a preferência legada para não manter uma proteção invisível ativa.
     */
    fun isSafetyModeEnabled(): Boolean {
        if (securePrefs.getBoolean("safety_mode_enabled", false)) {
            securePrefs.putBoolean("safety_mode_enabled", false)
        }
        return false
    }

    @Suppress("UNUSED_PARAMETER")
    fun setSafetyModeEnabled(enabled: Boolean) {
        securePrefs.putBoolean("safety_mode_enabled", false)
    }

    fun isAdultFilterEnabled(): Boolean {
        return if (securePrefs.prefs.contains(ADULT_FILTER_ENABLED_KEY)) {
            securePrefs.getBoolean(ADULT_FILTER_ENABLED_KEY, false)
        } else {
            prefs.getBoolean(ADULT_FILTER_ENABLED_KEY, false)
        }
    }

    fun setAdultFilterEnabled(enabled: Boolean) {
        securePrefs.putBoolean(ADULT_FILTER_ENABLED_KEY, enabled)
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
            addPasswordSuspend(newPassword)
        }
    }

    suspend fun addPasswordSuspend(newPassword: String) {
        ensureMigrationDone()
        val nextNumber = passwordDao.getAllStatic().size + 1
        insertPassword(
            password = newPassword,
            label = appContext.getString(R.string.senha_count_1, nextNumber)
        )
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
        private const val AUTH_PREFERENCES = "FocusGuardAuth"
        private const val ADULT_FILTER_ENABLED_KEY = "adult_filter_enabled"
        private const val BIOMETRIC_APP_UNLOCK_KEY = "biometric_app_unlock_enabled"

        // Guarda de migração companion-level — garante que múltiplas instâncias
        // de AuthManager (criadas por callers legados) não disparem migração
        // em paralelo. Quando todos os callers forem migrados para Hilt, isso
        // pode ser removido (Hilt garante singleton).
        //
        // Mutex (não synchronized/Object) porque migrateSharedPreferencesToRoom
        // é suspend — Kotlin proíbe suspensão dentro de synchronized{}.
        private val migrationMutex = kotlinx.coroutines.sync.Mutex()
        @Volatile
        private var migrationCompleted = false

        /**
         * Reads the adult-filter choice without constructing the Room-backed manager.
         *
         * Device Owner policy reconciliation runs very early during process startup,
         * before the asynchronous legacy-preference migration is guaranteed to finish.
         * Falling back to the legacy store keeps the policy enabled across upgrades.
         */
        internal fun isAdultFilterConfigured(context: Context): Boolean {
            val appContext = context.applicationContext
            val legacyValue = appContext
                .getSharedPreferences(AUTH_PREFERENCES, Context.MODE_PRIVATE)
                .getBoolean(ADULT_FILTER_ENABLED_KEY, false)

            return runCatching {
                val encryptedPreferences = SecurePrefsManager(appContext)
                if (encryptedPreferences.prefs.contains(ADULT_FILTER_ENABLED_KEY)) {
                    encryptedPreferences.getBoolean(ADULT_FILTER_ENABLED_KEY, false)
                } else {
                    legacyValue
                }
            }.getOrElse { error ->
                com.focusguard.utils.FocusGuardLogger.logError(
                    "AuthManager",
                    "Falha ao ler estado do filtro adulto; usando preferência legada",
                    error
                )
                legacyValue
            }
        }

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

        /** Verifica o formato `salt:hash` usado pelas senhas de limites. */
        fun verifySerializedPassword(password: String, stored: String): Boolean {
            if (stored.isBlank()) return false
            return try {
                val computed = if (stored.contains(':')) {
                    val salt = stored.substringBefore(':')
                    val expectedHash = stored.substringAfter(':')
                    val hash = if (expectedHash.startsWith("v2:")) {
                        hashPasswordWithSalt(password, salt)
                    } else {
                        hashPasswordLegacySalted(password, salt)
                    }
                    hash to expectedHash
                } else {
                    hashPasswordLegacy(password) to stored
                }
                constantTimeEquals(computed.first, computed.second)
            } catch (_: Throwable) {
                false
            }
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

    /**
     * @param title/[subtitle] override the prompt copy. Default to the app-unlock
     *   wording; the blocked-app flow passes its own so the user knows which
     *   decision the fingerprint is confirming.
     */
    fun showBiometricPrompt(
        activity: FragmentActivity,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
        title: String = appContext.getString(R.string.biometric_prompt_default_title),
        subtitle: String = appContext.getString(R.string.biometric_prompt_default_subtitle)
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
                            onError(appContext.getString(R.string.biometric_auth_failed))
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
                onError(appContext.getString(R.string.biometric_unavailable_use_password))
            }
        }
    }
}
