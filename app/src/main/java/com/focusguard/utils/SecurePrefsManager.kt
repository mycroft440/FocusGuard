package com.focusguard.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import android.util.Log

/**
 * Manager para SharedPreferences criptografadas via Jetpack Security (Tink).
 *
 * P3-4: Adicionado fallback defensivo se EncryptedSharedPreferences falhar.
 *
 * Cenários de falha conhecidos:
 *  1. Keystore corrompido (após restore de backup, factory reset parcial,
 *     mudança de PIN/dispositivo em alguns ROMs OEM)
 *  2. Ataque de downgrade (Tink incompatível com versão antiga)
 *  3. Falta de suporte a StrongBox em alguns emuladores
 *
 * Comportamento anterior: crashava o app na primeira init, impossibilitando
 * uso (AuthManager não conseguia verificar senhas, app inutilizável).
 *
 * Comportamento novo: cai para SharedPreferences COMUM (sem criptografia)
 * + loga warning. Senhas continuam hasheadas (PBKDF2), então o dano é
 * limitado — apenas metadados (config flags) ficam sem criptografia extra.
 * Usuário pode reativar criptografia resetando senhas após fixar keystore.
 */
class SecurePrefsManager(context: Context) {

    private val TAG = "SecurePrefsManager"
    private val PREFS_NAME = "focusguard_secure_prefs"

    val prefs: SharedPreferences = createPrefs(context)

    private fun createPrefs(context: Context): SharedPreferences {
        // Tentativa 1: EncryptedSharedPreferences (Tink + AES256-GCM)
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Throwable) {
            // Tentativa 2: Fallback para SharedPreferences comum (sem crypto).
            // Isso só deve acontecer em cenários de keystore corrompido —
            // logamos aviso prominente para o usuário saber que precisa
            // redefinir senhas.
            Log.e(TAG, "FALHA ao criar EncryptedSharedPreferences — usando fallback SEM criptografia. " +
                    "Senhas continuam hasheadas (PBKDF2) mas metadados não estão criptografados. " +
                    "Causa: ${e.javaClass.simpleName}: ${e.message}", e)
            FocusGuardLogger.logError(TAG,
                "Keystore corrompido ou EncryptedSharedPreferences indisponível. " +
                "Usando fallback sem criptografia. Recomendado: redefinir senhas.", e)

            // Deleta o arquivo de prefs corrompido antes de recriar (alguns
            // casos de keystore inválido deixam o arquivo inacessível).
            try {
                context.deleteSharedPreferences(PREFS_NAME)
            } catch (_: Throwable) {
                // ignore — se nem deletar der, segue com o fallback
            }

            context.getSharedPreferences("${PREFS_NAME}_fallback", Context.MODE_PRIVATE)
        }
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

    /**
     * Indica se as prefs estão sendo criptografadas ou em modo fallback.
     * Pode ser usado pela UI para avisar o usuário que a keystore está
     * comprometida.
     */
    val isUsingFallback: Boolean
        get() = prefs.javaClass.simpleName != "EncryptedSharedPreferences"
}

