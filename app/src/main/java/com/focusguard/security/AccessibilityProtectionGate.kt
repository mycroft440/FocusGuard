package com.focusguard.security

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import kotlin.math.max

/**
 * Controls a short, explicit maintenance window for the Accessibility settings.
 *
 * The deadline uses [SystemClock.elapsedRealtime] so changing the wall clock cannot
 * extend the window. The boot counter invalidates any window after a device restart.
 */
object AccessibilityProtectionGate {

    enum class UnlockResult {
        UNLOCKED,
        AUTOMATIC_DATE_TIME_REQUIRED
    }

    const val UNLOCK_DURATION_MILLIS: Long = 10 * 60 * 1_000L

    private const val PREFERENCES_NAME = "accessibility_protection_gate"
    private const val DEADLINE_ELAPSED_KEY = "deadline_elapsed"
    private const val BOOT_COUNT_KEY = "boot_count"

    fun requestTemporaryUnlock(context: Context): UnlockResult {
        if (!isAutomaticDateAndTimeEnabled(context)) {
            revoke(context)
            return UnlockResult.AUTOMATIC_DATE_TIME_REQUIRED
        }

        val deadline = SystemClock.elapsedRealtime() + UNLOCK_DURATION_MILLIS
        preferences(context).edit()
            .putLong(DEADLINE_ELAPSED_KEY, deadline)
            .putInt(BOOT_COUNT_KEY, readBootCount(context))
            .apply()
        return UnlockResult.UNLOCKED
    }

    fun isTemporarilyUnlocked(context: Context): Boolean {
        return remainingMillis(context) > 0L
    }

    fun remainingMillis(context: Context): Long {
        val prefs = preferences(context)
        val remaining = evaluateRemainingMillis(
            automaticDateTimeEnabled = isAutomaticDateAndTimeEnabled(context),
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

    fun isAutomaticDateAndTimeEnabled(context: Context): Boolean {
        return readGlobalBoolean(context, Settings.Global.AUTO_TIME) &&
            readGlobalBoolean(context, Settings.Global.AUTO_TIME_ZONE)
    }

    internal fun evaluateRemainingMillis(
        automaticDateTimeEnabled: Boolean,
        nowElapsedMillis: Long,
        deadlineElapsedMillis: Long,
        storedBootCount: Int,
        currentBootCount: Int
    ): Long {
        if (!automaticDateTimeEnabled) return 0L
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

    private fun readGlobalBoolean(context: Context, key: String): Boolean {
        return runCatching {
            Settings.Global.getInt(context.contentResolver, key, 0) == 1
        }.getOrDefault(false)
    }
}
