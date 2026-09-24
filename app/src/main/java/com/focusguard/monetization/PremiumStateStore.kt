package com.focusguard.monetization

import android.content.Context
import android.content.SharedPreferences

/** Entitlement local, sem expiração, independente dos créditos de anúncios. */
object PremiumStateStore {
    private const val PREFS = "focusguard_premium"
    private const val KEY_ACTIVE = "premium_active"
    private const val PROMOTIONAL_CODE = "josegustavo34"

    enum class RedemptionResult { ACTIVATED, INVALID_CODE, SAVE_FAILED }

    fun isPremium(context: Context): Boolean = preferences(context).getBoolean(KEY_ACTIVE, false)

    fun redeem(context: Context, code: String): RedemptionResult {
        if (code.trim() != PROMOTIONAL_CODE) return RedemptionResult.INVALID_CODE
        if (!preferences(context).edit().putBoolean(KEY_ACTIVE, true).commit()) {
            return RedemptionResult.SAVE_FAILED
        }
        MonetizationStateStore.clearPomodoroCompletionAds(context)
        return RedemptionResult.ACTIVATED
    }

    fun preferences(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
