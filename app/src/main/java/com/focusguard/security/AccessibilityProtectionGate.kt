package com.focusguard.security

import android.content.Context

/**
 * Compatibility gate retained for callers created by older FocusGuard versions.
 *
 * Consumer installations must never use an AccessibilityService to prevent the
 * user from opening Android Accessibility settings, disabling the service or
 * removing the app. Strong anti-removal protection belongs exclusively to the
 * official Device Owner flow.
 */
object AccessibilityProtectionGate {

    const val UNLOCK_DURATION_MILLIS: Long = 0L

    @Suppress("UNUSED_PARAMETER")
    fun requestTemporaryUnlock(context: Context) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun isTemporarilyUnlocked(context: Context): Boolean =
        shouldAllowSystemAccessibilitySettings()

    @Suppress("UNUSED_PARAMETER")
    fun remainingMillis(context: Context): Long = 0L

    @Suppress("UNUSED_PARAMETER")
    fun revoke(context: Context) = Unit

    internal fun shouldAllowSystemAccessibilitySettings(): Boolean = true
}
