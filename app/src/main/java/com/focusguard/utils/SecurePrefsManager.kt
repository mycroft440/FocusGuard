package com.focusguard.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Preferências sensíveis do FocusGuard.
 *
 * Se a chave principal do Android Keystore ficar inválida, o arquivo original
 * não é apagado e nenhum dado passa a ser salvo em texto puro. Em vez disso, o
 * app cria um segundo cofre criptografado para recuperação. O usuário poderá
 * reconfigurar as credenciais, enquanto o arquivo anterior permanece intacto
 * para diagnóstico ou futura recuperação.
 */
class SecurePrefsManager(context: Context) {

    private val appContext = context.applicationContext
    private val primaryPrefsName = "focusguard_secure_prefs"
    private val recoveryPrefsName = "focusguard_secure_prefs_recovery"
    private val primaryAlias = "focusguard_primary_master_key"
    private val recoveryAlias = "focusguard_recovery_master_key"

    private val result = createPrefs()
    val prefs: SharedPreferences = result.prefs
    val isUsingRecovery: Boolean = result.usingRecovery

    /** Mantido para compatibilidade com telas antigas. Nunca significa texto puro. */
    val isUsingFallback: Boolean
        get() = isUsingRecovery

    private data class PrefsResult(
        val prefs: SharedPreferences,
        val usingRecovery: Boolean
    )

    private fun createPrefs(): PrefsResult {
        return try {
            PrefsResult(
                prefs = createEncryptedPrefs(primaryPrefsName, primaryAlias),
                usingRecovery = false
            )
        } catch (primaryError: Exception) {
            FocusGuardLogger.logError(
                "SecurePrefsManager",
                "Cofre principal indisponível; iniciando cofre criptografado de recuperação",
                primaryError
            )
            try {
                PrefsResult(
                    prefs = createEncryptedPrefs(recoveryPrefsName, recoveryAlias),
                    usingRecovery = true
                )
            } catch (recoveryError: Exception) {
                FocusGuardLogger.logError(
                    "SecurePrefsManager",
                    "Nenhum cofre criptografado pôde ser inicializado",
                    recoveryError
                )
                throw IllegalStateException(
                    "Não foi possível inicializar o armazenamento seguro do FocusGuard",
                    recoveryError
                )
            }
        }
    }

    private fun createEncryptedPrefs(name: String, alias: String): SharedPreferences {
        val key = MasterKey.Builder(appContext, alias)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            appContext,
            name,
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun putString(key: String, value: String?) {
        prefs.edit().putString(key, value).apply()
    }

    fun getString(key: String, defaultValue: String? = null): String? {
        return prefs.getString(key, defaultValue)
    }

    fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    fun getBoolean(key: String, defaultValue: Boolean = false): Boolean {
        return prefs.getBoolean(key, defaultValue)
    }

    fun putInt(key: String, value: Int) {
        prefs.edit().putInt(key, value).apply()
    }

    fun getInt(key: String, defaultValue: Int = 0): Int {
        return prefs.getInt(key, defaultValue)
    }
}
