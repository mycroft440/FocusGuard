package com.focusguard.monetization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonetizationPolicyTest {
    @Test
    fun firstAppOrSiteLimitIsFreeAndNextOneRequiresRewarded() {
        assertFalse(
            MonetizationPolicy.requiresExtraUsageLimitAd(
                existingConfiguredCount = 0,
                targetAlreadyConfigured = false
            )
        )
        assertTrue(
            MonetizationPolicy.requiresExtraUsageLimitAd(
                existingConfiguredCount = 1,
                targetAlreadyConfigured = false
            )
        )
        assertFalse(
            MonetizationPolicy.requiresExtraUsageLimitAd(
                existingConfiguredCount = 5,
                targetAlreadyConfigured = true
            )
        )
    }

    @Test
    fun timeBlockRequiresThreeRewardedAds() {
        assertEquals(1, MonetizationPolicy.FREE_APP_LIMITS)
        assertEquals(1, MonetizationPolicy.FREE_SITE_LIMITS)
        assertEquals(3, MonetizationPolicy.TIME_BLOCK_REWARDED_ADS)
    }

    @Test
    fun rewardedGateKeysDoNotCollideForCurrentFlows() {
        val app = RewardedGateKeys.forRequest("Adicionar mais um aplicativo", 1)
        val site = RewardedGateKeys.forRequest("Adicionar mais um site", 1)
        val time = RewardedGateKeys.forRequest("Ativar bloqueio sem senha", 3)

        assertEquals(RewardedGateKeys.EXTRA_APP_LIMIT_SLOT, app)
        assertEquals(RewardedGateKeys.EXTRA_SITE_LIMIT_SLOT, site)
        assertEquals(RewardedGateKeys.TIME_BLOCK_CREATION, time)
        assertTrue(setOf(app, site, time).size == 3)
    }
}
