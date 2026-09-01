package com.focusguard.monetization

import android.content.Context

object RewardedGateKeys {
    const val EXTRA_APP_LIMIT_SLOT = "extra_app_limit_slot"
    const val EXTRA_SITE_LIMIT_SLOT = "extra_site_limit_slot"
    const val TIME_BLOCK_CREATION = "time_block_creation"
    const val BIOMETRIC_APP_UNLOCK = "biometric_app_unlock"
    const val INTRUDER_SELFIE = "intruder_selfie"

    fun forRequest(title: String, requiredAds: Int): String {
        val normalized = title.lowercase()
        return when {
            "desbloquear apps com digital" in normalized ||
                "unlock apps with biometric" in normalized -> BIOMETRIC_APP_UNLOCK

            "selfie" in normalized || "intruder" in normalized -> INTRUDER_SELFIE
            "aplicativo" in normalized -> EXTRA_APP_LIMIT_SLOT
            "site" in normalized -> EXTRA_SITE_LIMIT_SLOT
            "bloqueio sem senha" in normalized -> TIME_BLOCK_CREATION
            else -> "generic_${requiredAds}_${title.hashCode()}"
        }
    }
}

/**
 * Persiste progresso e créditos de rewarded ads.
 *
 * O crédito é separado da ação em memória: se o processo morrer depois de o
 * usuário conquistar a recompensa, a próxima tentativa consome o crédito sem
 * exigir que ele assista aos anúncios novamente.
 */
object RewardedGateStateStore {
    private const val PREFS = "focusguard_rewarded_gate_state"
    private const val PROGRESS_PREFIX = "progress_"
    private const val CREDIT_PREFIX = "credit_"

    @Synchronized
    fun progress(context: Context, gateKey: String): Int =
        prefs(context).getInt(PROGRESS_PREFIX + gateKey, 0).coerceAtLeast(0)

    /**
     * Registra um rewarded concluído. Retorna true quando o conjunto exigido foi
     * completado e um crédito persistente foi criado.
     */
    @Synchronized
    fun recordReward(context: Context, gateKey: String, requiredAds: Int): Boolean {
        val target = requiredAds.coerceAtLeast(1)
        val preferences = prefs(context)
        val current = preferences.getInt(PROGRESS_PREFIX + gateKey, 0).coerceAtLeast(0)
        val next = current + 1
        return if (next >= target) {
            val credits = preferences.getInt(CREDIT_PREFIX + gateKey, 0).coerceAtLeast(0)
            preferences.edit()
                .putInt(PROGRESS_PREFIX + gateKey, 0)
                .putInt(CREDIT_PREFIX + gateKey, credits + 1)
                .commit()
            true
        } else {
            preferences.edit()
                .putInt(PROGRESS_PREFIX + gateKey, next)
                .commit()
            false
        }
    }

    @Synchronized
    fun hasCredit(context: Context, gateKey: String): Boolean =
        prefs(context).getInt(CREDIT_PREFIX + gateKey, 0) > 0

    @Synchronized
    fun consumeCredit(context: Context, gateKey: String): Boolean {
        val preferences = prefs(context)
        val credits = preferences.getInt(CREDIT_PREFIX + gateKey, 0)
        if (credits <= 0) return false
        preferences.edit()
            .putInt(CREDIT_PREFIX + gateKey, credits - 1)
            .commit()
        return true
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
