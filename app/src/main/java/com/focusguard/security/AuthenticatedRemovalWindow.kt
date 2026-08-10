package com.focusguard.security

import android.content.Context
import android.os.SystemClock
import android.provider.Settings

/**
 * Very short bridge between the authenticated in-app uninstall action and the
 * Android-owned uninstall UI. Without it, Accessibility would correctly see an
 * active password block but would also close the system screen that this app
 * had just authorized.
 */
object AuthenticatedRemovalWindow {
    private const val PREFERENCES_NAME = "authenticated_removal_window"
    private const val DEADLINE_KEY = "deadline_elapsed"
    private const val BOOT_COUNT_KEY = "boot_count"
    internal const val DURATION_MILLIS = 60_000L

    fun open(context: Context) {
        preferences(context).edit()
            .putLong(DEADLINE_KEY, SystemClock.elapsedRealtime() + DURATION_MILLIS)
            .putInt(BOOT_COUNT_KEY, readBootCount(context))
            .commit()
    }

    fun isActive(context: Context): Boolean {
        val prefs = preferences(context)
        val active = evaluate(
            nowElapsedMillis = SystemClock.elapsedRealtime(),
            deadlineElapsedMillis = prefs.getLong(DEADLINE_KEY, 0L),
            storedBootCount = prefs.getInt(BOOT_COUNT_KEY, Int.MIN_VALUE),
            currentBootCount = readBootCount(context)
        )
        if (!active && prefs.contains(DEADLINE_KEY)) prefs.edit().clear().apply()
        return active
    }

    fun close(context: Context) {
        preferences(context).edit().clear().apply()
    }

    internal fun evaluate(
        nowElapsedMillis: Long,
        deadlineElapsedMillis: Long,
        storedBootCount: Int,
        currentBootCount: Int
    ): Boolean = storedBootCount == currentBootCount &&
        deadlineElapsedMillis > nowElapsedMillis

    private fun preferences(context: Context) = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private fun readBootCount(context: Context): Int = runCatching {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    }.getOrDefault(-1)
}
