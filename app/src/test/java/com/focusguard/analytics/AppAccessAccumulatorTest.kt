package com.focusguard.analytics

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AppAccessAccumulatorTest {

    @Test
    fun `resumed then paused counts one completed access`() {
        val counter = counter()

        counter.onActivityResumed(APP_A, "MainActivity")
        counter.onActivityPaused(APP_A, "MainActivity")

        assertThat(counter.finish()).containsExactly(APP_A, 1)
    }

    @Test
    fun `app still in foreground is not counted yet`() {
        val counter = counter()

        counter.onActivityResumed(APP_A, "MainActivity")

        assertThat(counter.finish()).isEmpty()
    }

    @Test
    fun `switching activities inside same app does not duplicate access`() {
        val counter = counter()

        counter.onActivityResumed(APP_A, "MainActivity")
        counter.onActivityPaused(APP_A, "MainActivity")
        counter.onActivityResumed(APP_A, "DetailsActivity")
        counter.onActivityPaused(APP_A, "DetailsActivity")

        assertThat(counter.finish()).containsExactly(APP_A, 1)
    }

    @Test
    fun `late pause from previous activity does not close current activity`() {
        val counter = counter()

        counter.onActivityResumed(APP_A, "MainActivity")
        counter.onActivityResumed(APP_A, "DetailsActivity")
        counter.onActivityPaused(APP_A, "MainActivity")

        assertThat(counter.finish()).isEmpty()

        counter.onActivityPaused(APP_A, "DetailsActivity")
        assertThat(counter.finish()).containsExactly(APP_A, 1)
    }

    @Test
    fun `switching apps completes only the app that was left`() {
        val counter = counter()

        counter.onActivityResumed(APP_A, "MainActivity")
        counter.onActivityResumed(APP_B, "MainActivity")

        assertThat(counter.finish()).containsExactly(APP_A, 1)
    }

    @Test
    fun `background event without an opening is ignored`() {
        val counter = counter()

        counter.onActivityPaused(APP_A, "MainActivity")

        assertThat(counter.finish()).isEmpty()
    }

    @Test
    fun `screen off completes current access and next use starts another`() {
        val counter = counter()

        counter.onActivityResumed(APP_A, "MainActivity")
        counter.onDeviceBecameInactive()
        counter.onActivityResumed(APP_A, "MainActivity")
        counter.onActivityPaused(APP_A, "MainActivity")

        assertThat(counter.finish()).containsExactly(APP_A, 2)
    }

    @Test
    fun `ineligible packages close an app but never enter the ranking`() {
        val counter = AppAccessAccumulator { it != SYSTEM_UI }

        counter.onActivityResumed(APP_A, "MainActivity")
        counter.onActivityResumed(SYSTEM_UI, "SystemUiActivity")
        counter.onActivityPaused(SYSTEM_UI, "SystemUiActivity")

        assertThat(counter.finish()).containsExactly(APP_A, 1)
    }

    private fun counter() = AppAccessAccumulator { true }

    private companion object {
        const val APP_A = "com.example.alpha"
        const val APP_B = "com.example.beta"
        const val SYSTEM_UI = "com.android.systemui"
    }
}
