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
    private const val UNINITIALIZED_DEADLINE = Long.MIN_VALUE

    @Volatile private var cachedDeadlineElapsed = UNINITIALIZED_DEADLINE
    @Volatile private var cachedStoredBootCount = Int.MIN_VALUE
    @Volatile private var cachedCurrentBootCount = Int.MIN_VALUE

    /** Loads the common inactive state before the first accessibility event. */
    fun preload(context: Context) {
        ensureCacheLoaded(context)
    }

    fun open(context: Context) {
        val deadline = SystemClock.elapsedRealtime() + DURATION_MILLIS
        val bootCount = readBootCount(context)
        val persisted = preferences(context).edit()
            .putLong(DEADLINE_KEY, deadline)
            .putInt(BOOT_COUNT_KEY, bootCount)
            .commit()
        if (persisted) {
            cachedStoredBootCount = bootCount
            cachedCurrentBootCount = bootCount
            cachedDeadlineElapsed = deadline
        }
    }

    fun isActive(context: Context): Boolean {
        ensureCacheLoaded(context)
        val deadline = cachedDeadlineElapsed
        if (deadline <= 0L) return false
        val active = evaluate(
            nowElapsedMillis = SystemClock.elapsedRealtime(),
            deadlineElapsedMillis = deadline,
            storedBootCount = cachedStoredBootCount,
            currentBootCount = cachedCurrentBootCount
        )
        if (!active) clearCachedAndPersistedState(context)
        return active
    }

    fun close(context: Context) {
        clearCachedAndPersistedState(context)
    }

    private fun ensureCacheLoaded(context: Context) {
        if (cachedDeadlineElapsed != UNINITIALIZED_DEADLINE) return
        synchronized(this) {
            if (cachedDeadlineElapsed != UNINITIALIZED_DEADLINE) return
            val prefs = preferences(context)
            val deadline = prefs.getLong(DEADLINE_KEY, 0L)
            if (deadline <= 0L) {
                cachedDeadlineElapsed = 0L
                return
            }
            cachedStoredBootCount = prefs.getInt(BOOT_COUNT_KEY, Int.MIN_VALUE)
            cachedCurrentBootCount = readBootCount(context)
            cachedDeadlineElapsed = deadline
        }
    }

    private fun clearCachedAndPersistedState(context: Context) {
        cachedStoredBootCount = Int.MIN_VALUE
        cachedCurrentBootCount = Int.MIN_VALUE
        cachedDeadlineElapsed = 0L
        val prefs = preferences(context)
        if (prefs.contains(DEADLINE_KEY) || prefs.contains(BOOT_COUNT_KEY)) {
            prefs.edit().clear().apply()
        }
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
