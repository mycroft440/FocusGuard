package com.focusguard.service

import android.view.accessibility.AccessibilityWindowInfo

/**
 * Classifies UI/IME events without requesting any AccessibilityNodeInfo. Window
 * metadata comes from Android's window manager; reading a window root or an event
 * source would synchronously ask the app/keyboard to build its accessibility tree.
 */
internal class AccessibilityInputEventFilter {
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

        if (!windowsChanged) {
            if (eventPackageName == ownPackageName ||
                (eventPackageName.isBlank() && windowId in ownWindowIds)
            ) return Decision.OWN_UI
            if (windowTypes[windowId] == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                return Decision.INPUT_METHOD
            }
        }

        // Named Settings/launcher/browser events keep their existing immediate
        // protection path before even a window-metadata lookup is attempted.
        if (!allowWindowLookup || windowId < 0) return Decision.INSPECT

        val wasInputMethod =
            windowTypes[windowId] == AccessibilityWindowInfo.TYPE_INPUT_METHOD
        if (windowsChanged || windowId !in windowTypes) {
            val snapshot = readWindows()
            windowTypes.clear()
            snapshot.forEach { windowTypes[it.id] = it.type }
            ownWindowIds.retainAll(windowTypes.keys)
        }

        val type = windowTypes[windowId]
        if (type == AccessibilityWindowInfo.TYPE_INPUT_METHOD ||
            (windowsChanged && wasInputMethod && type == null)
        ) {
            // The removal event belongs to the keyboard too. It must not replace
            // the foreground app with the IME or trigger a fallback root read.
            return Decision.INPUT_METHOD
        }
        if (type == AccessibilityWindowInfo.TYPE_APPLICATION && windowId in ownWindowIds) {
            return Decision.OWN_UI
        }
        // Unknown, removed app, Settings, browser and System UI windows retain
        // normal inspection. Merely having an IME visible never exempts an app.
        return Decision.INSPECT
    }

    private companion object {
        const val MAX_OWN_WINDOWS = 32
    }
}
