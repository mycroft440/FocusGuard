package com.focusguard.security

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import com.focusguard.admin.FocusGuardDeviceAdminReceiver

/**
 * Short, one-purpose bridge for the system-owned Device Admin enrollment UI.
 *
 * Self-protection normally closes every FocusGuard administration surface while
 * a consented block is active. The permission wizard, however, must be able to
 * launch [DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN] without being mistaken for
 * a removal attempt. This window authorizes only that screen and only when the
 * administrator was confirmed inactive before the system UI was launched.
 */
object DeviceAdminActivationWindow {
    private const val PREFERENCES_NAME = "device_admin_activation_window"
    private const val DEADLINE_KEY = "deadline_elapsed"
    private const val BOOT_COUNT_KEY = "boot_count"
    private const val ADMIN_INACTIVE_WHEN_OPENED_KEY = "admin_inactive_when_opened"
    internal const val DURATION_MILLIS = 5 * 60_000L
    private const val UNINITIALIZED_DEADLINE = Long.MIN_VALUE

    @Volatile private var cachedDeadlineElapsed = UNINITIALIZED_DEADLINE
    @Volatile private var cachedStoredBootCount = Int.MIN_VALUE
    @Volatile private var cachedCurrentBootCount = Int.MIN_VALUE
    @Volatile private var cachedAdminInactiveWhenOpened = false

    /** Loads the overwhelmingly common inactive state before an accessibility click. */
    fun preload(context: Context) {
        ensureCacheLoaded(context)
    }

    /**
     * Opens the authorization outside Accessibility's event callback.
     * DevicePolicyManager is queried here once; the later interception decision is memory-only.
     */
    fun open(context: Context): Boolean {
        val adminInactive = runCatching {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            dpm.isAdminActive(
                FocusGuardDeviceAdminReceiver.getComponentName(context)
            ).not()
        }.getOrDefault(false)
        if (!adminInactive) {
            invalidateCachedState()
            clearPersistedState(context)
            return false
        }

        val deadline = SystemClock.elapsedRealtime() + DURATION_MILLIS
        val bootCount = readBootCount(context)
        val persisted = preferences(context).edit()
            .putLong(DEADLINE_KEY, deadline)
            .putInt(BOOT_COUNT_KEY, bootCount)
            .putBoolean(ADMIN_INACTIVE_WHEN_OPENED_KEY, true)
            .commit()
        if (persisted) {
            cachedStoredBootCount = bootCount
            cachedCurrentBootCount = bootCount
            cachedAdminInactiveWhenOpened = true
            cachedDeadlineElapsed = deadline
        }
        return persisted
    }

    /** Cheap pre-check with no DPM/Settings/Preferences read after preload/open. */
    fun isPotentiallyAuthorized(context: Context): Boolean {
        ensureCacheLoaded(context)
        val deadline = cachedDeadlineElapsed
        if (deadline <= 0L) return false
        val active = cachedAdminInactiveWhenOpened &&
            cachedStoredBootCount == cachedCurrentBootCount &&
            deadline > SystemClock.elapsedRealtime()
        if (!active) invalidateCachedState()
        return active
    }

    /** Memory-only after [preload] or [open]. */
    fun isAuthorized(context: Context): Boolean {
        if (!isPotentiallyAuthorized(context)) return false
        val authorized = evaluate(
            nowElapsedMillis = SystemClock.elapsedRealtime(),
            deadlineElapsedMillis = cachedDeadlineElapsed,
            storedBootCount = cachedStoredBootCount,
            currentBootCount = cachedCurrentBootCount,
            deviceAdminActive = cachedAdminInactiveWhenOpened.not()
        )
        if (!authorized) invalidateCachedState()
        return authorized
    }

    /** Controlled lifecycle close may clean persisted state because it is not a hot-path read. */
    fun close(context: Context) {
        invalidateCachedState()
        clearPersistedState(context)
    }

    internal fun evaluate(
        nowElapsedMillis: Long,
        deadlineElapsedMillis: Long,
        storedBootCount: Int,
        currentBootCount: Int,
        deviceAdminActive: Boolean
    ): Boolean = deviceAdminActive.not() &&
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
            cachedAdminInactiveWhenOpened =
                prefs.getBoolean(ADMIN_INACTIVE_WHEN_OPENED_KEY, false)
            cachedDeadlineElapsed = deadline

            // Old/stale windows fail closed. No synchronous disk write is performed
            // from an accessibility event merely because a cached deadline expired.
            if (cachedAdminInactiveWhenOpened.not() ||
                cachedStoredBootCount != cachedCurrentBootCount ||
                cachedDeadlineElapsed <= SystemClock.elapsedRealtime()
            ) {
                invalidateCachedState()
            }
        }
    }

    private fun invalidateCachedState() {
        cachedStoredBootCount = Int.MIN_VALUE
        cachedCurrentBootCount = Int.MIN_VALUE
        cachedAdminInactiveWhenOpened = false
        cachedDeadlineElapsed = 0L
    }

    private fun clearPersistedState(context: Context) {
        val prefs = preferences(context)
        if (prefs.contains(DEADLINE_KEY) ||
            prefs.contains(BOOT_COUNT_KEY) ||
            prefs.contains(ADMIN_INACTIVE_WHEN_OPENED_KEY)
        ) {
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
