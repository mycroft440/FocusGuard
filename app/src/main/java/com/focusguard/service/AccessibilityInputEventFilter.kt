package com.focusguard.service

import android.view.accessibility.AccessibilityWindowInfo

/**
 * Classifies FocusGuard UI, accessibility-overlay and IME events without requesting
 * any AccessibilityNodeInfo. Window metadata comes from Android's window manager;
 * reading a window root or an event source would synchronously ask the app/keyboard
 * to build its accessibility tree.
 */
internal class AccessibilityInputEventFilter {
    /** INPUT_METHOD also represents non-application windows that must be consumed. */
    enum class Decision { OWN_UI, INPUT_METHOD, INSPECT }

    data class Window(val id: Int, val type: Int)

    private val ownWindowIds = linkedSetOf<Int>()
    private val windowTypes = mutableMapOf<Int, Int>()

    fun classify(
        ownPackageName: String,
        eventPackageName: String,
        windowId: Int,
        windowsChanged: Boolean,
        allowWindowLookup: Boolean,
        readWindows: () -> List<Window>
    ): Decision {
        // A WINDOWS_CHANGED package can describe a different window. Learn owner
        // IDs only from ordinary events whose package actually names their source.
        if (!windowsChanged && eventPackageName.isNotBlank() && windowId >= 0) {
            if (eventPackageName == ownPackageName) {
                ownWindowIds.add(windowId)
                if (ownWindowIds.size > MAX_OWN_WINDOWS) {
                    ownWindowIds.remove(ownWindowIds.first())
                }
            } else {
                ownWindowIds.remove(windowId)
            }
        }

        val ownEvent = !windowsChanged && (
            eventPackageName == ownPackageName ||
                (eventPackageName.isBlank() && windowId in ownWindowIds)
            )

        if (!windowsChanged) {
            val cachedType = windowTypes[windowId]
            if (isConsumedNonApplicationWindow(cachedType)) {
                return Decision.INPUT_METHOD
            }
            if (ownEvent &&
                (cachedType == AccessibilityWindowInfo.TYPE_APPLICATION || !allowWindowLookup)
            ) {
                // Ordinary FocusGuard focus/text events stay on the zero-lookup fast
                // path. Window-transition events arrive with lookup enabled, so a
                // newly-created accessibility curtain is still classified from real
                // window metadata before it can be promoted to OWN_UI.
                return Decision.OWN_UI
            }
        }

        if (!allowWindowLookup || windowId < 0) return Decision.INSPECT

        val wasConsumedNonApplication = isConsumedNonApplicationWindow(windowTypes[windowId])
        if (windowsChanged || windowId !in windowTypes) {
            val snapshot = readWindows()
            windowTypes.clear()
            snapshot.forEach { windowTypes[it.id] = it.type }
            ownWindowIds.retainAll(windowTypes.keys)
        }

        val type = windowTypes[windowId]
        if (isConsumedNonApplicationWindow(type) ||
            (windowsChanged && wasConsumedNonApplication && type == null)
        ) {
            // Keyboard and FocusGuard accessibility-overlay events are consumed but
            // never promoted to foreground application context. Their removal event
            // is consumed as well, preventing the curtain from retiring its own
            // website transition.
            return Decision.INPUT_METHOD
        }

        if (type == AccessibilityWindowInfo.TYPE_APPLICATION && windowId in ownWindowIds) {
            return Decision.OWN_UI
        }
        if (ownEvent && type == null) {
            // Metadata can briefly lag a newly-created Activity. At this point a
            // lookup was attempted and no overlay/IME evidence exists, so preserve
            // the previous own-Activity behavior rather than leaking the event.
            return Decision.OWN_UI
        }

        // Unknown, removed app, Settings, browser and System UI windows retain
        // normal inspection. Merely having an IME visible never exempts an app.
        return Decision.INSPECT
    }

    private fun isConsumedNonApplicationWindow(type: Int?): Boolean =
        type == AccessibilityWindowInfo.TYPE_INPUT_METHOD ||
            type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY

    private companion object {
        const val MAX_OWN_WINDOWS = 32
    }
}
