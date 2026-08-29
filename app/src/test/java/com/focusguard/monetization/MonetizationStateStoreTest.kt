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

@RunWith(RobolectricTestRunner::class)
class MonetizationStateStoreTest {
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
    fun pomodoroCompletionQueueSurvivesReservationFailure() {
        assertFalse(MonetizationStateStore.hasPomodoroCompletionAdPending(context))

        MonetizationStateStore.markPomodoroCompletionAdPending(context)
        MonetizationStateStore.markPomodoroCompletionAdPending(context)
        assertEquals(2, MonetizationStateStore.pendingPomodoroCompletionAds(context))

        assertTrue(MonetizationStateStore.consumePomodoroCompletionAdPending(context))
        assertEquals(1, MonetizationStateStore.pendingPomodoroCompletionAds(context))

        MonetizationStateStore.restorePomodoroCompletionAdPending(context)
        assertEquals(2, MonetizationStateStore.pendingPomodoroCompletionAds(context))
    }

    @Test
    fun pomodoroQueueIsCappedToAvoidAdBacklog() {
        repeat(10) {
            MonetizationStateStore.markPomodoroCompletionAdPending(context)
        }
        assertEquals(5, MonetizationStateStore.pendingPomodoroCompletionAds(context))
    }

    private fun clearPrefs() {
        context.getSharedPreferences(
            "focusguard_monetization",
            Context.MODE_PRIVATE
        ).edit().clear().commit()
    }
}
