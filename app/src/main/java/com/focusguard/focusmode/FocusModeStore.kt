package com.focusguard.focusmode

import android.content.Context
import android.os.Build

/** Device-protected persistence so Focus Mode survives process death and reboot. */
object FocusModeStore {
    private const val PREFERENCES_NAME = "focusguard_focus_mode"
    private const val KEY_ACTIVE = "active"
    private const val KEY_STARTED_AT = "started_at"
    private const val KEY_END_TIME = "end_time"
    private const val KEY_DURATION = "duration"
    private const val KEY_ALLOWED_PACKAGES = "allowed_packages"
    private const val KEY_BLOCKED_PACKAGES = "blocked_packages"
    private const val KEY_NON_SUSPENDABLE_PACKAGES = "non_suspendable_packages"
    private const val KEY_DRAFT_PACKAGES = "draft_packages"
    private const val KEY_DRAFT_INITIALIZED = "draft_initialized"

    fun saveSession(context: Context, session: FocusModeSession): Boolean =
        preferences(context).edit()
            .putBoolean(KEY_ACTIVE, true)
            .putLong(KEY_STARTED_AT, session.startedAtMillis)
            .putLong(KEY_END_TIME, session.endTimeMillis)
            .putLong(KEY_DURATION, session.durationMillis)
            .putStringSet(KEY_ALLOWED_PACKAGES, session.allowedPackages)
            .putStringSet(KEY_BLOCKED_PACKAGES, session.blockedPackages)
            .putStringSet(KEY_NON_SUSPENDABLE_PACKAGES, session.nonSuspendablePackages)
            .commit()

    fun readSession(context: Context): FocusModeSession? {
        val prefs = preferences(context)
        if (!prefs.getBoolean(KEY_ACTIVE, false)) return null

        val endTime = prefs.getLong(KEY_END_TIME, 0L)
        val duration = prefs.getLong(KEY_DURATION, 0L)
        if (endTime <= 0L || duration <= 0L) return null

        return FocusModeSession(
            startedAtMillis = prefs.getLong(KEY_STARTED_AT, endTime - duration),
            endTimeMillis = endTime,
            durationMillis = duration,
            allowedPackages = prefs.getStringSet(KEY_ALLOWED_PACKAGES, emptySet())
                .orEmpty()
                .toSet(),
            blockedPackages = prefs.getStringSet(KEY_BLOCKED_PACKAGES, emptySet())
                .orEmpty()
                .toSet(),
            nonSuspendablePackages = prefs.getStringSet(
                KEY_NON_SUSPENDABLE_PACKAGES,
                emptySet()
            ).orEmpty().toSet()
        )
    }

    fun updateNonSuspendablePackages(
        context: Context,
        packageNames: Set<String>
    ): FocusModeSession? {
        val current = readSession(context) ?: return null
        val updated = current.copy(nonSuspendablePackages = packageNames)
        return if (saveSession(context, updated)) updated else current
    }

    fun clearSession(context: Context): Boolean = preferences(context).edit()
        .remove(KEY_ACTIVE)
        .remove(KEY_STARTED_AT)
        .remove(KEY_END_TIME)
        .remove(KEY_DURATION)
        .remove(KEY_ALLOWED_PACKAGES)
        .remove(KEY_BLOCKED_PACKAGES)
        .remove(KEY_NON_SUSPENDABLE_PACKAGES)
        .commit()

    fun saveDraftPackages(context: Context, packageNames: Set<String>): Boolean =
        preferences(context).edit()
            .putBoolean(KEY_DRAFT_INITIALIZED, true)
            .putStringSet(KEY_DRAFT_PACKAGES, packageNames)
            .commit()

    fun readDraftPackages(context: Context): Set<String>? {
        val prefs = preferences(context)
        if (!prefs.getBoolean(KEY_DRAFT_INITIALIZED, false)) return null
        return prefs.getStringSet(KEY_DRAFT_PACKAGES, emptySet()).orEmpty().toSet()
    }

    fun isActive(context: Context, nowMillis: Long = System.currentTimeMillis()): Boolean =
        readSession(context)?.isActive(nowMillis) == true

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
