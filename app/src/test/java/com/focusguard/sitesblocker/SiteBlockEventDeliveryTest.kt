package com.focusguard.sitesblocker

import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import com.google.common.truth.Truth.assertThat
import java.time.Duration
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SiteBlockEventDeliveryTest {

    private val delivered = mutableListOf<AccessibilityEvent>()
    private val delivery = SiteBlockEventDelivery { delivered += it }

    private fun event(type: Int, packageName: String = "com.android.chrome") =
        AccessibilityEvent(type).apply { this.packageName = packageName }

    private fun advance(millis: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))
    }

    @Test
    fun `events reach the engine 40 ms later, like notificationTimeout 40`() {
        delivery.post(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED))

        advance(39)
        assertThat(delivered).isEmpty()

        advance(1)
        assertThat(delivered.map { it.eventType })
            .containsExactly(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    }

    @Test
    fun `events of the same type inside the timeout arrive as the last one`() {
        delivery.post(event(AccessibilityEvent.TYPE_VIEW_SCROLLED, "first"))
        advance(30)
        delivery.post(event(AccessibilityEvent.TYPE_VIEW_SCROLLED, "second"))

        // O prazo recomeça a cada evento do mesmo tipo.
        advance(30)
        assertThat(delivered).isEmpty()

        advance(10)
        assertThat(delivered.map { it.packageName.toString() }).containsExactly("second")
    }

    @Test
    fun `content changes are delayed but never merged`() {
        delivery.post(event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, "a"))
        delivery.post(event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, "b"))

        advance(40)
        assertThat(delivered.map { it.packageName.toString() }).containsExactly("a", "b").inOrder()
    }

    @Test
    fun `different types are merged independently and keep their order`() {
        delivery.post(event(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED))
        delivery.post(event(AccessibilityEvent.TYPE_VIEW_FOCUSED))

        advance(40)
        assertThat(delivered.map { it.eventType }).containsExactly(
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED
        ).inOrder()
    }

    @Test
    fun `the engine receives a copy that survives the recycled original`() {
        val original = event(AccessibilityEvent.TYPE_VIEW_CLICKED, "com.brave.browser")
        delivery.post(original)
        original.packageName = "changed"

        advance(40)
        assertThat(delivered.single()).isNotSameInstanceAs(original)
        assertThat(delivered.single().packageName.toString()).isEqualTo("com.brave.browser")
    }

    @Test
    fun `clear drops events not yet delivered`() {
        delivery.post(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED))
        delivery.post(event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED))

        delivery.clear()
        advance(100)
        assertThat(delivered).isEmpty()
    }
}
