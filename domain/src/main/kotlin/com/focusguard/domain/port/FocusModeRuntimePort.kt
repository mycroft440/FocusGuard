package com.focusguard.domain.port

/** Permission and process-lifecycle effects required by Focus Mode. */
interface FocusModeRuntimePort {
    fun isAccessibilityEnabled(): Boolean
    fun isNotificationAccessEnabled(): Boolean
    fun activate(endTimeMillis: Long)
    fun deactivate()
}
