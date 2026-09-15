package com.focusguard.accessibility.website.identification

import android.view.accessibility.AccessibilityEvent

/**
 * Accessibility events are only invalidation/reinspection triggers. They never
 * prove the current URL by themselves.
 */
internal object WebsiteIdentificationEventPolicy {
    private val reinspectionEventTypes = setOf(
        AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
        AccessibilityEvent.TYPE_WINDOWS_CHANGED,
        AccessibilityEvent.TYPE_VIEW_FOCUSED,
        AccessibilityEvent.TYPE_VIEW_CLICKED
    )

    private val navigationEvidenceEventTypes = setOf(
        AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
        AccessibilityEvent.TYPE_WINDOWS_CHANGED
    )

    fun shouldReinspect(eventType: Int): Boolean = eventType in reinspectionEventTypes

    fun canConfirmNavigation(eventType: Int): Boolean = eventType in navigationEvidenceEventTypes

    fun reinspectionEventTypesForTest(): Set<Int> = reinspectionEventTypes
}
