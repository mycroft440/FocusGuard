package com.focusguard.focusmode

/**
 * Navigation guard used while Focus Mode is active.
 *
 * FocusGuard may temporarily expose its own Protection, Pomodoro and settings screens
 * during a Focus Mode session. Those screens are useful, but they must not become an indefinite
 * escape from the Focus Mode home. After 30 seconds without user interaction we return there.
 */
object FocusModeIdleReturnPolicy {
    const val IDLE_TIMEOUT_MILLIS = 30_000L
    const val FOCUS_MODE_TAB = 4

    fun shouldArm(
        focusModeActive: Boolean,
        onFocusModeHome: Boolean
    ): Boolean = focusModeActive && !onFocusModeHome
}
