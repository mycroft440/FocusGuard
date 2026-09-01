package com.focusguard.monetization

object MonetizationPolicy {
    const val FREE_APP_LIMITS = 1
    const val FREE_SITE_LIMITS = 1
    const val TIME_BLOCK_REWARDED_ADS = 3
    const val BIOMETRIC_UNLOCK_REWARDED_ADS = 1
    const val INTRUDER_SELFIE_REWARDED_ADS = 1

    fun requiresExtraUsageLimitAd(
        existingConfiguredCount: Int,
        targetAlreadyConfigured: Boolean
    ): Boolean = !targetAlreadyConfigured && existingConfiguredCount >= 1
}
