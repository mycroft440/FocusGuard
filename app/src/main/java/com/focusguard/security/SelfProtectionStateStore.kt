package com.focusguard.security

import android.content.Context
import android.os.Build

/**
 * Synchronous, device-protected snapshot used by the accessibility hot path.
 *
 * Room remains the source of truth for configured blocks. This snapshot is the
 * fail-closed bridge across process death and AccessibilityService startup: the
 * first event must not wait for an asynchronous Room query before knowing both
 * that protection is active and which targets must be rejected immediately.
 */
object SelfProtectionStateStore {
    private const val PREFERENCES_NAME = "focusguard_self_protection_state"
    private const val KEY_ARMED = "armed"
    private const val KEY_BLOCKED_APPS = "blocked_apps"
    private const val KEY_BLOCKED_SITES = "blocked_sites"
    private const val KEY_STRICT_POMODORO = "strict_pomodoro"

    data class Snapshot(
        val armed: Boolean,
        val blockedApps: Set<String>,
        val blockedSites: Set<String>,
        val strictPomodoro: Boolean
    )

    fun isArmed(context: Context): Boolean =
        preferences(context).getBoolean(KEY_ARMED, false)

    fun read(context: Context): Snapshot {
        val preferences = preferences(context)
        val armed = preferences.getBoolean(KEY_ARMED, false)
        if (!armed) {
            return Snapshot(
                armed = false,
                blockedApps = emptySet(),
                blockedSites = emptySet(),
                strictPomodoro = false
            )
        }
        return Snapshot(
            armed = true,
            blockedApps = preferences.getStringSet(KEY_BLOCKED_APPS, emptySet())
                .orEmpty()
                .filter(String::isNotBlank)
                .toSet(),
            blockedSites = preferences.getStringSet(KEY_BLOCKED_SITES, emptySet())
                .orEmpty()
                .filter(String::isNotBlank)
                .toSet(),
            strictPomodoro = preferences.getBoolean(KEY_STRICT_POMODORO, false)
        )
    }

    /** Uses commit deliberately: callers must know the snapshot is durable now. */
    fun setSnapshot(
        context: Context,
        armed: Boolean,
        blockedApps: Collection<String>,
        blockedSites: Collection<String>,
        strictPomodoro: Boolean
    ): Boolean {
        val effectiveApps = if (armed) {
            blockedApps.filter(String::isNotBlank).toSet()
        } else {
            emptySet()
        }
        val effectiveSites = if (armed) {
            blockedSites.filter(String::isNotBlank).toSet()
        } else {
            emptySet()
        }
        return preferences(context).edit()
            .putBoolean(KEY_ARMED, armed)
            .putStringSet(KEY_BLOCKED_APPS, effectiveApps)
            .putStringSet(KEY_BLOCKED_SITES, effectiveSites)
            .putBoolean(KEY_STRICT_POMODORO, armed && strictPomodoro)
            .commit()
    }

    /**
     * Compatibility helper for callers that only change the armed state.
     * Disarming also clears target data so a stale package can never be blocked
     * after the source-of-truth session has ended.
     */
    fun setArmed(context: Context, armed: Boolean): Boolean {
        val editor = preferences(context).edit().putBoolean(KEY_ARMED, armed)
        if (!armed) {
            editor.remove(KEY_BLOCKED_APPS)
            editor.remove(KEY_BLOCKED_SITES)
            editor.remove(KEY_STRICT_POMODORO)
        }
        return editor.commit()
    }

    internal fun usesDeviceProtectedStorage(context: Context): Boolean =
        storageContext(context).isDeviceProtectedStorage

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
}
