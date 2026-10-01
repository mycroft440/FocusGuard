package com.focusguard.service

import android.app.usage.UsageEvents
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Aviso de janela de um app bloqueado: só é entrada com o app de fato na frente. O
 * Android manda esses avisos mesmo de janelas fora da tela, depois que a pessoa saiu.
 */
class BlockedWindowEventPolicyTest {
    private val instagram = "com.instagram.android"
    private val launcher = "com.android.launcher3"

    @Test
    fun `a window in front is an entry without reading usage`() {
        var usageRead = false
        assertThat(
            BlockedWindowEventPolicy.isEntry(eventWindowInFront = true) {
                usageRead = true
                false
            }
        ).isTrue()
        assertThat(usageRead).isFalse()
    }

    @Test
    fun `an unreadable window list still blocks`() {
        assertThat(BlockedWindowEventPolicy.isEntry(eventWindowInFront = null) { false })
            .isTrue()
    }

    @Test
    fun `a window off screen after the exit is not an entry`() {
        assertThat(BlockedWindowEventPolicy.isEntry(eventWindowInFront = false) { false })
            .isFalse()
    }

    @Test
    fun `a window list lagging behind a real entry still blocks`() {
        assertThat(BlockedWindowEventPolicy.isEntry(eventWindowInFront = false) { true })
            .isTrue()
    }

    @Test
    fun `the app just opened is in front`() {
        assertThat(inFront(resumed(instagram, "Main"))).isTrue()
    }

    @Test
    fun `leaving to the home screen puts the app out of front`() {
        assertThat(
            inFront(
                resumed(instagram, "Main"),
                paused(instagram, "Main"),
                resumed(launcher, "Home"),
                stopped(instagram, "Main")
            )
        ).isFalse()
    }

    @Test
    fun `another app resumed later puts the app out of front`() {
        assertThat(inFront(resumed(instagram, "Main"), resumed(launcher, "Home"))).isFalse()
    }

    @Test
    fun `the screen turning off puts the app out of front`() {
        assertThat(
            inFront(resumed(instagram, "Main"), paused(instagram, "Main"), stopped(instagram, "Main"))
        ).isFalse()
    }

    @Test
    fun `moving between the app's own screens keeps it in front`() {
        assertThat(
            inFront(
                resumed(instagram, "Main"),
                paused(instagram, "Main"),
                resumed(instagram, "Story"),
                stopped(instagram, "Main")
            )
        ).isTrue()
    }

    @Test
    fun `a system screen on top does not count as another app`() {
        assertThat(
            inFront(resumed(instagram, "Main"), resumed("com.android.systemui", "Shade"))
        ).isTrue()
    }

    @Test
    fun `coming back to the app after leaving puts it in front again`() {
        assertThat(
            inFront(
                resumed(instagram, "Main"),
                resumed(launcher, "Home"),
                resumed(instagram, "Main")
            )
        ).isTrue()
    }

    @Test
    fun `no recent usage means the app is not in front`() {
        assertThat(inFront()).isFalse()
    }

    private data class Event(val packageName: String, val className: String, val type: Int)

    private fun resumed(packageName: String, className: String) =
        Event(packageName, className, UsageEvents.Event.ACTIVITY_RESUMED)

    private fun paused(packageName: String, className: String) =
        Event(packageName, className, UsageEvents.Event.ACTIVITY_PAUSED)

    private fun stopped(packageName: String, className: String) =
        Event(packageName, className, UsageEvents.Event.ACTIVITY_STOPPED)

    private fun inFront(vararg events: Event): Boolean {
        val tracker = BlockedWindowEventPolicy.ForegroundTracker(instagram)
        events.forEach { tracker.onEvent(it.packageName, it.className, it.type) }
        return tracker.targetInFront
    }
}
