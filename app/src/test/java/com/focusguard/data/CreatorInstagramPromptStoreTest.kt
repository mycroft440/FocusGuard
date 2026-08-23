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
    fun `presentation eligibility is persisted after first hour`() {
        val store = CreatorInstagramPromptStore(context)

        assertThat(store.wasHomeCardPresented()).isFalse()

        store.markHomeCardPresented()

        assertThat(CreatorInstagramPromptStore(context).wasHomeCardPresented()).isTrue()
    }

    @Test
    fun `attention animation uses gradual fade and long visible interval`() {
        assertThat(CreatorInstagramPromptPolicy.ATTENTION_VISIBLE_MILLIS)
            .isEqualTo(12_000L)
        assertThat(CreatorInstagramPromptPolicy.ATTENTION_FADE_OUT_MILLIS)
            .isEqualTo(900L)
    }

    @Test
    fun `feedback button remains available with persistent Instagram card`() {
        assertThat(
            CreatorInstagramPromptPolicy.shouldShowFeedbackButton(
                homeCardPresented = false,
                homeCardVisible = false
            )
        ).isFalse()

        assertThat(
            CreatorInstagramPromptPolicy.shouldShowFeedbackButton(
                homeCardPresented = true,
                homeCardVisible = true
            )
        ).isTrue()

        assertThat(
            CreatorInstagramPromptPolicy.shouldShowFeedbackButton(
                homeCardPresented = true,
                homeCardVisible = false
            )
        ).isTrue()
    }
}
