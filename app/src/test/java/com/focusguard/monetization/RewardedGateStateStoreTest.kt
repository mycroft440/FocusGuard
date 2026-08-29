package com.focusguard.monetization

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RewardedGateStateStoreTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        clearPrefs()
    }

    @After
    fun tearDown() {
        clearPrefs()
    }

    @Test
    fun threeRewardsPersistProgressThenCreateExactlyOneCredit() {
        val key = RewardedGateKeys.TIME_BLOCK_CREATION

        assertFalse(RewardedGateStateStore.recordReward(context, key, 3))
        assertEquals(1, RewardedGateStateStore.progress(context, key))
        assertFalse(RewardedGateStateStore.hasCredit(context, key))

        assertFalse(RewardedGateStateStore.recordReward(context, key, 3))
        assertEquals(2, RewardedGateStateStore.progress(context, key))

        assertTrue(RewardedGateStateStore.recordReward(context, key, 3))
        assertEquals(0, RewardedGateStateStore.progress(context, key))
        assertTrue(RewardedGateStateStore.hasCredit(context, key))

        assertTrue(RewardedGateStateStore.consumeCredit(context, key))
        assertFalse(RewardedGateStateStore.hasCredit(context, key))
        assertFalse(RewardedGateStateStore.consumeCredit(context, key))
    }

    @Test
    fun creditsAreIsolatedByFeature() {
        val appKey = RewardedGateKeys.EXTRA_APP_LIMIT_SLOT
        val siteKey = RewardedGateKeys.EXTRA_SITE_LIMIT_SLOT

        assertTrue(RewardedGateStateStore.recordReward(context, appKey, 1))
        assertTrue(RewardedGateStateStore.hasCredit(context, appKey))
        assertFalse(RewardedGateStateStore.hasCredit(context, siteKey))
    }

    private fun clearPrefs() {
        context.getSharedPreferences(
            "focusguard_rewarded_gate_state",
            Context.MODE_PRIVATE
        ).edit().clear().commit()
    }
}
