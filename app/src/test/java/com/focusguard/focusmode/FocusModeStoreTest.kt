package com.focusguard.focusmode

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
class FocusModeStoreTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication().applicationContext
        FocusModeStore.clearSession(context)
        FocusModeStore.saveDraftPackages(context, emptySet())
    }

    @Test
    fun `active session survives a fresh read with every package set`() {
        val session = FocusModeSession(
            startedAtMillis = 10_000L,
            endTimeMillis = 70_000L,
            durationMillis = 60_000L,
            allowedPackages = setOf("com.focusguard.v2", "com.phone"),
            blockedPackages = setOf("com.social", "com.game"),
            nonSuspendablePackages = setOf("com.system.launcher"),
            grayscaleEnabled = true
        )

        assertThat(FocusModeStore.saveSession(context, session)).isTrue()
        assertThat(FocusModeStore.readSession(context)).isEqualTo(session)
    }

    @Test
    fun `clearing a session preserves the user app draft`() {
        val draft = setOf("com.whatsapp", "com.notes")
        FocusModeStore.saveDraftPackages(context, draft)
        FocusModeStore.saveSession(
            context,
            FocusModeSession(
                startedAtMillis = 10_000L,
                endTimeMillis = 70_000L,
                durationMillis = 60_000L,
                allowedPackages = draft,
                blockedPackages = setOf("com.social")
            )
        )

        assertThat(FocusModeStore.clearSession(context)).isTrue()

        assertThat(FocusModeStore.readSession(context)).isNull()
        assertThat(FocusModeStore.readDraftPackages(context)).containsExactlyElementsIn(draft)
    }
}
