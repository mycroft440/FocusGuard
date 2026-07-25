package com.focusguard.security

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import kotlin.math.max

/**
 * Controls the short window in which Android Accessibility settings may be opened.
 *
 * Authentication is performed before this gate is opened. The deadline uses
 * [SystemClock.elapsedRealtime], so changing the wall clock cannot extend it.
 * The boot counter invalidates any window after a device restart.
 */
object AccessibilityProtectionGate {

    const val UNLOCK_DURATION_MILLIS: Long = 10 * 60 * 1_000L

    private const val PREFERENCES_NAME = "accessibility_protection_gate"
    private const val DEADLINE_ELAPSED_KEY = "deadline_elapsed"
    private const val BOOT_COUNT_KEY = "boot_count"

    fun requestTemporaryUnlock(context: Context) {
        val deadline = SystemClock.elapsedRealtime() + UNLOCK_DURATION_MILLIS
        preferences(context).edit()
            .putLong(DEADLINE_ELAPSED_KEY, deadline)
            .putInt(BOOT_COUNT_KEY, readBootCount(context))
            .apply()
    }

    fun isTemporarilyUnlocked(context: Context): Boolean {
        return remainingMillis(context) > 0L
    }

    fun remainingMillis(context: Context): Long {
        val prefs = preferences(context)
        val remaining = evaluateRemainingMillis(
            nowElapsedMillis = SystemClock.elapsedRealtime(),
            deadlineElapsedMillis = prefs.getLong(DEADLINE_ELAPSED_KEY, 0L),
            storedBootCount = prefs.getInt(BOOT_COUNT_KEY, Int.MIN_VALUE),
            currentBootCount = readBootCount(context)
        )
        if (remaining == 0L && prefs.contains(DEADLINE_ELAPSED_KEY)) {
            revoke(context)
        }
        return remaining
    }

    fun revoke(context: Context) {
        preferences(context).edit().clear().apply()
    }

    internal fun evaluateRemainingMillis(
        nowElapsedMillis: Long,
        deadlineElapsedMillis: Long,
        storedBootCount: Int,
        currentBootCount: Int
    ): Long {
        if (storedBootCount != currentBootCount) return 0L
        return max(0L, deadlineElapsedMillis - nowElapsedMillis)
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private fun readBootCount(context: Context): Int {
        return runCatching {
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        }.getOrDefault(-1)
    }
}
