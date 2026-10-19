package com.focusguard.sitesblocker

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
class LegacySitesListTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    @Test
    fun `list and adult option of the old screen are deleted`() {
        // Gravado como a antiga tela Bloquear sites (BlockedSitesStore) gravava.
        context.getSharedPreferences(LegacySitesList.PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet("domains", setOf("instagram.com", "youtube.com"))
            .putBoolean("block_adult_content", true)
            .commit()

        assertThat(LegacySitesList.delete(context)).isTrue()

        val leftover = context.getSharedPreferences(LegacySitesList.PREFS, Context.MODE_PRIVATE)
        assertThat(leftover.all).isEmpty()
    }

    @Test
    fun `browser identification is kept`() {
        val identified = context.getSharedPreferences("identified_browsers", Context.MODE_PRIVATE)
        identified.edit().putString("com.example.browser", "CHROMIUM").commit()

        LegacySitesList.delete(context)

        assertThat(identified.getString("com.example.browser", null)).isEqualTo("CHROMIUM")
    }

    @Test
    fun `nothing to delete is not an error`() {
        assertThat(LegacySitesList.delete(context)).isTrue()
        assertThat(LegacySitesList.delete(context)).isTrue()
    }
}
