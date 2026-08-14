package com.focusguard.security

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Settings

/**
 * Short, one-purpose bridge for the system-owned Device Admin enrollment UI.
 *
 * Self-protection normally closes every FocusGuard administration surface while
 * a consented block is active. The permission wizard, however, must be able to
 * launch [android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN] without
 * being mistaken for a removal attempt. This window authorizes only that screen
 * and only while FocusGuard is not an active administrator yet.
 */
object DeviceAdminActivationWindow {
    private const val PREFERENCES_NAME = "device_admin_activation_window"
    private const val DEADLINE_KEY = "deadline_elapsed"
    private const val BOOT_COUNT_KEY = "boot_count"
    internal const val DURATION_MILLIS = 5 * 60_000L

    /** Uses commit so Accessibility can observe the authorization immediately. */
    fun open(context: Context): Boolean = preferences(context).edit()
        .putLong(DEADLINE_KEY, SystemClock.elapsedRealtime() + DURATION_MILLIS)
        .putInt(BOOT_COUNT_KEY, readBootCount(context))
        .commit()

    fun isAuthorized(context: Context, deviceAdminActive: Boolean): Boolean {
        val prefs = preferences(context)
        val authorized = evaluate(
            nowElapsedMillis = SystemClock.elapsedRealtime(),
            deadlineElapsedMillis = prefs.getLong(DEADLINE_KEY, 0L),
            storedBootCount = prefs.getInt(BOOT_COUNT_KEY, Int.MIN_VALUE),
            currentBootCount = readBootCount(context),
            deviceAdminActive = deviceAdminActive
        )
        if (!authorized && prefs.contains(DEADLINE_KEY)) {
            prefs.edit().clear().apply()
        }
        return authorized
    }

    fun close(context: Context) {
        preferences(context).edit().clear().apply()
    }

    internal fun evaluate(
        nowElapsedMillis: Long,
        deadlineElapsedMillis: Long,
        storedBootCount: Int,
        currentBootCount: Int,
        deviceAdminActive: Boolean
    ): Boolean = !deviceAdminActive &&
        storedBootCount == currentBootCount &&
        deadlineElapsedMillis > nowElapsedMillis

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
