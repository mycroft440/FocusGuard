package com.focusguard.monetization

import android.content.Context
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PremiumStateStoreTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        clearPreferences()
    }

    @After
    fun tearDown() = clearPreferences()

    private fun clearPreferences() {
        listOf("focusguard_premium", "focusguard_monetization", "focusguard_rewarded_gate_state")
            .forEach { context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
    }

    @Test
    fun invalidCodesDoNotActivatePremium() {
        listOf("", "wrong", "josegustavo", "josegustavo340").forEach {
            assertEquals(PremiumStateStore.RedemptionResult.INVALID_CODE, PremiumStateStore.redeem(context, it))
            assertFalse(PremiumStateStore.isPremium(context))
        }
    }

    @Test
    fun validCodePersistsAndRepeatedRedemptionKeepsPremium() {
        assertEquals(PremiumStateStore.RedemptionResult.ACTIVATED, PremiumStateStore.redeem(context, " josegustavo34 "))
        val recreatedContext = context.createConfigurationContext(context.resources.configuration)
        assertTrue(PremiumStateStore.isPremium(recreatedContext))
        assertEquals(PremiumStateStore.RedemptionResult.ACTIVATED, PremiumStateStore.redeem(recreatedContext, "josegustavo34"))
        PremiumStateStore.redeem(context, "invalid")
        assertTrue(PremiumStateStore.isPremium(context))
    }

    @Test
    fun premiumClearsExistingPomodoroAdsAndCannotQueueOrRestoreMore() {
        MonetizationStateStore.markPomodoroCompletionAdPending(context)
        assertTrue(MonetizationStateStore.hasPomodoroCompletionAdPending(context))
        PremiumStateStore.redeem(context, "josegustavo34")
        MonetizationStateStore.markPomodoroCompletionAdPending(context)
        MonetizationStateStore.restorePomodoroCompletionAdPending(context)
        assertEquals(0, MonetizationStateStore.pendingPomodoroCompletionAds(context))
        assertFalse(MonetizationStateStore.consumePomodoroCompletionAdPending(context))
        assertFalse(context.getSharedPreferences("focusguard_monetization", Context.MODE_PRIVATE)
            .contains("pomodoro_completion_ad_pending_count"))
    }

    @Test
    fun premiumBypassesEveryRewardedFeatureWithoutConsumingCredits() {
        PremiumStateStore.redeem(context, "josegustavo34")
        listOf(
            "Limite de aplicativo" to 1,
            "Limite de site" to 1,
            "Bloqueio sem senha" to MonetizationPolicy.TIME_BLOCK_REWARDED_ADS,
            "Desbloquear apps com digital" to MonetizationPolicy.BIOMETRIC_UNLOCK_REWARDED_ADS,
            "Selfie" to MonetizationPolicy.INTRUDER_SELFIE_REWARDED_ADS
        ).forEach { (title, count) ->
            val key = RewardedGateKeys.forRequest(title, count)
            repeat(count) { RewardedGateStateStore.recordReward(context, key, count) }
            var executions = 0
            RewardedGateCoordinator.launch(context, count, title, "benefit") { executions++ }
            assertEquals(1, executions)
            assertTrue(RewardedGateStateStore.hasCredit(context, key))
            assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
        }
    }

    @Test
    fun freeUserStillNeedsTheRewardedGate() {
        var executed = false
        RewardedGateCoordinator.launch(context, 3, "Bloqueio sem senha", "benefit") { executed = true }
        assertFalse(executed)
        val intent = shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
        assertNotNull(intent)
        intent.getStringExtra("rewarded_gate_token")?.let(RewardedGateCoordinator::cancel)
    }
}
