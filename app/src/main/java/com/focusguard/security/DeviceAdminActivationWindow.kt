package com.focusguard.security

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Settings

/**
 * Short, one-purpose bridge for the system-owned Device Admin enrollment UI.
 *
 * The persisted record is preloaded before Accessibility callbacks. The hot-path
 * methods below use only volatile cache + elapsedRealtime; no Settings.Global,
 * SharedPreferences or DevicePolicyManager access is required for a decision.
 */
object DeviceAdminActivationWindow {
    private const val PREFERENCES_NAME = "device_admin_activation_window"
    private const val DEADLINE_KEY = "deadline_elapsed"
    private const val BOOT_COUNT_KEY = "boot_count"
    internal const val DURATION_MILLIS = 5 * 60_000L
    private const val UNINITIALIZED_DEADLINE = Long.MIN_VALUE

    @Volatile private var cachedDeadlineElapsed = UNINITIALIZED_DEADLINE
    @Volatile private var cachedStoredBootCount = Int.MIN_VALUE
    @Volatile private var cachedCurrentBootCount = Int.MIN_VALUE

    fun preload(context: Context) {
        ensureCacheLoaded(context)
    }

    /** Uses commit so Accessibility can observe the authorization immediately. */
    fun open(context: Context): Boolean {
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
        return persisted
    }

    /** Slow-compatible wrapper for callers outside the Accessibility fast path. */
    fun isPotentiallyAuthorized(context: Context): Boolean {
        ensureCacheLoaded(context)
        return isPotentiallyAuthorizedCached()
    }

    /** No I/O and no Binder. Expired cache simply fails closed. */
    fun isPotentiallyAuthorizedCached(
        nowElapsedMillis: Long = SystemClock.elapsedRealtime()
    ): Boolean {
        val deadline = cachedDeadlineElapsed
        return deadline > 0L &&
            cachedStoredBootCount == cachedCurrentBootCount &&
            deadline > nowElapsedMillis
    }

    fun isAuthorized(context: Context, deviceAdminActive: Boolean): Boolean {
        ensureCacheLoaded(context)
        val authorized = isAuthorizedCached(deviceAdminActive)
        if (!authorized && cachedDeadlineElapsed > 0L) clearCachedAndPersistedState(context)
        return authorized
    }

    /** No I/O/Binder. Used by the Settings L0 classifier. */
    fun isAuthorizedCached(
        deviceAdminActive: Boolean,
        nowElapsedMillis: Long = SystemClock.elapsedRealtime()
    ): Boolean = evaluate(
        nowElapsedMillis = nowElapsedMillis,
        deadlineElapsedMillis = cachedDeadlineElapsed,
        storedBootCount = cachedStoredBootCount,
        currentBootCount = cachedCurrentBootCount,
        deviceAdminActive = deviceAdminActive
    )

    fun close(context: Context) {
        clearCachedAndPersistedState(context)
    }

    internal fun evaluate(
        nowElapsedMillis: Long,
        deadlineElapsedMillis: Long,
        storedBootCount: Int,
        currentBootCount: Int,
        deviceAdminActive: Boolean
    ): Boolean = !deviceAdminActive &&
        deadlineElapsedMillis > 0L &&
        storedBootCount == currentBootCount &&
        deadlineElapsedMillis > nowElapsedMillis

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

    private fun preferences(context: Context) = storageContext(context)
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private fun storageContext(context: Context): Context {
        val appContext = context.applicationContext
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching { appContext.createDeviceProtectedStorageContext() }
                .getOrDefault(appContext)
        } else {
            appContext
        }
    }

    private fun readBootCount(context: Context): Int = runCatching {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    }.getOrDefault(-1)
}
