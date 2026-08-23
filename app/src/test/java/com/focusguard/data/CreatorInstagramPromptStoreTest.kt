package com.focusguard.data

import android.content.Context
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CreatorInstagramPromptStoreTest {
    private val context: Context = RuntimeEnvironment.getApplication().applicationContext

    @Before
    fun clearPresentationState() {
        context.getSharedPreferences(
            CreatorInstagramPromptStore.PREFERENCES_NAME,
            Context.MODE_PRIVATE
        ).edit().clear().commit()
    }

    @Test
    fun `card remains unavailable until one hour after installation`() {
        val installedAt = 1_000_000L

        assertThat(
            CreatorInstagramPromptPolicy.remainingDelayMillis(
                firstInstallTimeMillis = installedAt,
                nowMillis = installedAt + 59L * 60L * 1_000L
            )
        ).isEqualTo(60_000L)

        assertThat(
            CreatorInstagramPromptPolicy.remainingDelayMillis(
                firstInstallTimeMillis = installedAt,
                nowMillis = installedAt + CreatorInstagramPromptPolicy.MIN_INSTALL_AGE_MILLIS
            )
        ).isEqualTo(0L)
    }

    @Test
    fun `presentation is persisted and cannot be repeated`() {
        val store = CreatorInstagramPromptStore(context)

        assertThat(store.wasHomeCardPresented()).isFalse()

        store.markHomeCardPresented()

        assertThat(CreatorInstagramPromptStore(context).wasHomeCardPresented()).isTrue()
    }

    @Test
    fun `one-time card stays visible long enough to be read`() {
        assertThat(CreatorInstagramPromptPolicy.HOME_CARD_VISIBLE_MILLIS)
            .isEqualTo(15_000L)
    }

    @Test
    fun `feedback button is permanently available on protection home`() {
        listOf(
            false to false,
            false to true,
            true to false,
            true to true
        ).forEach { (homeCardPresented, homeCardVisible) ->
            assertThat(
                CreatorInstagramPromptPolicy.shouldShowFeedbackButton(
                    homeCardPresented = homeCardPresented,
                    homeCardVisible = homeCardVisible
                )
            ).isTrue()
        }
    }
}
