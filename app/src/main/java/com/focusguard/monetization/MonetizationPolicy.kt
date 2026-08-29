package com.focusguard.monetization

object MonetizationPolicy {
    const val FREE_APP_LIMITS = 1
    const val FREE_SITE_LIMITS = 1
    const val TIME_BLOCK_REWARDED_ADS = 3

    fun requiresExtraUsageLimitAd(
        existingConfiguredCount: Int,
        targetAlreadyConfigured: Boolean
    ): Boolean = !targetAlreadyConfigured && existingConfiguredCount >= 1
}
