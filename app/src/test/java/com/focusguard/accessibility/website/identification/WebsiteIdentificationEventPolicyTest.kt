package com.focusguard.accessibility.website.identification

import android.view.accessibility.AccessibilityEvent
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WebsiteIdentificationEventPolicyTest {
    @Test
    fun `website reinspection listens to every configured browser mutation signal`() {
        assertThat(WebsiteIdentificationEventPolicy.reinspectionEventTypesForTest()).containsAtLeast(
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_CLICKED
        )
    }

    @Test
    fun `focus and click trigger reinspection but do not certify navigation`() {
        assertThat(
            WebsiteIdentificationEventPolicy.shouldReinspect(
                AccessibilityEvent.TYPE_VIEW_FOCUSED
            )
        ).isTrue()
        assertThat(
            WebsiteIdentificationEventPolicy.shouldReinspect(
                AccessibilityEvent.TYPE_VIEW_CLICKED
            )
        ).isTrue()
        assertThat(
            WebsiteIdentificationEventPolicy.canConfirmNavigation(
                AccessibilityEvent.TYPE_VIEW_FOCUSED
            )
        ).isFalse()
        assertThat(
            WebsiteIdentificationEventPolicy.canConfirmNavigation(
                AccessibilityEvent.TYPE_VIEW_CLICKED
            )
        ).isFalse()
    }

    @Test
    fun `surface mutation can be navigation evidence`() {
        assertThat(
            WebsiteIdentificationEventPolicy.canConfirmNavigation(
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            )
        ).isTrue()
        assertThat(
            WebsiteIdentificationEventPolicy.canConfirmNavigation(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            )
        ).isTrue()
    }
}
