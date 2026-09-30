package com.focusguard.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Preferências sensíveis do FocusGuard.
 *
 * O cofre principal continua usando o alias padrão histórico do Jetpack
 * Security, garantindo compatibilidade com instalações existentes. Se essa
 * chave ficar inválida, o arquivo original não é apagado e nenhum dado passa a
 * ser salvo em texto puro: um segundo cofre criptografado é usado para
 * recuperação.
 */
class SecurePrefsManager(context: Context) {

    private val appContext = context.applicationContext
    private val primaryPrefsName = "focusguard_secure_prefs"
    private val recoveryPrefsName = "focusguard_secure_prefs_recovery"
    private val recoveryAlias = "focusguard_recovery_master_key"

    private val result = cachedFor(appContext) ?: synchronized(lock) {
        cachedFor(appContext) ?: createPrefs().also {
            cachedResult = it
            cachedContext = appContext
            // O cofre de recuperação fica guardado só por um instante: numa falha
            // passageira do Keystore, a instância seguinte volta a tentar o principal,
            // como antes, mas sem repetir a tentativa (Keystore + Tink + log) a cada
            // tela aberta no mesmo segundo.
            cachedRecoveryUntilElapsed = if (it.usingRecovery) {
                android.os.SystemClock.elapsedRealtime() + RECOVERY_RETRY_INTERVAL_MILLIS
            } else {
                0L
            }
        }
    }
    val prefs: SharedPreferences = result.prefs
    val isUsingRecovery: Boolean = result.usingRecovery

    /** Mantido para compatibilidade com telas antigas. Nunca significa texto puro. */
    val isUsingFallback: Boolean
        get() = isUsingRecovery

    private data class PrefsResult(
        val prefs: SharedPreferences,
        val usingRecovery: Boolean
    )

    companion object {
        // Abrir o cofre custa uma chamada ao Keystore (MasterKey) e a decifragem dos
        // conjuntos de chaves do Tink: 10–45 ms por instância. A tela de senha criava
        // duas na primeira composição. O cofre aberto é reaproveitado no processo; as
        // leituras e gravações continuam indo ao mesmo arquivo, então nada fica velho.
        private val lock = Any()
        @Volatile private var cachedResult: PrefsResult? = null
        @Volatile private var cachedContext: Context? = null
        @Volatile private var cachedRecoveryUntilElapsed = 0L
        private const val RECOVERY_RETRY_INTERVAL_MILLIS = 30_000L

        private fun cachedFor(appContext: Context): PrefsResult? {
            val cached = cachedResult?.takeIf { cachedContext === appContext } ?: return null
            if (cached.usingRecovery &&
                android.os.SystemClock.elapsedRealtime() >= cachedRecoveryUntilElapsed
            ) {
                return null
            }
            return cached
        }

        /** Abre o cofre fora da thread principal antes de ele ser necessário. */
        fun prewarm(context: Context) {
            runCatching { SecurePrefsManager(context) }
        }
    }

    private fun createPrefs(): PrefsResult {
        return try {
            PrefsResult(
                prefs = createPrimaryEncryptedPrefs(),
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
                    prefs = createRecoveryEncryptedPrefs(),
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

    /** Usa exatamente o mesmo alias padrão que as versões anteriores. */
    private fun createPrimaryEncryptedPrefs(): SharedPreferences {
        val key = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return createEncryptedPrefs(primaryPrefsName, key)
    }

    private fun createRecoveryEncryptedPrefs(): SharedPreferences {
        val key = MasterKey.Builder(appContext, recoveryAlias)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return createEncryptedPrefs(recoveryPrefsName, key)
    }

    private fun createEncryptedPrefs(name: String, key: MasterKey): SharedPreferences {
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
