package com.focusguard.security

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import com.focusguard.receiver.DeviceOwnerMaintenanceExpiryReceiver
import java.util.Calendar
import kotlin.math.max

/**
 * Controls a short maintenance window for Device Owner policies.
 *
 * Eligibility uses automatic network date/time. The ten-minute deadline uses
 * [SystemClock.elapsedRealtime], so wall-clock or timezone changes cannot extend it.
 * Reboot invalidates the window through [Settings.Global.BOOT_COUNT].
 *
 * The accessibility hot path must never re-read SharedPreferences or Settings.Global.
 * Those externally-backed values are loaded once and published through volatile fields.
 */
object DeviceOwnerMaintenanceGate {

    enum class UnlockResult {
        UNLOCKED,
        AUTOMATIC_DATE_TIME_REQUIRED,
        EXACT_EXPIRY_REQUIRED,
        ACTIVE_BLOCK_REQUIRES_MONTHLY_WINDOW,
        CREDENTIAL_NOT_CONFIGURED,
        INVALID_CREDENTIAL,
        OUTSIDE_MONTHLY_WINDOW
    }

    const val UNLOCK_DURATION_MILLIS: Long = 10 * 60 * 1_000L
    const val MONTHLY_MAINTENANCE_DAY: Int = 15
    const val MONTHLY_START_HOUR: Int = 2
    const val MONTHLY_START_MINUTE: Int = 50
    const val MONTHLY_END_HOUR: Int = 3
    const val MONTHLY_END_MINUTE: Int = 0

    private const val PREFERENCES_NAME = "device_owner_maintenance_gate"
    private const val DEADLINE_ELAPSED_KEY = "deadline_elapsed"
    private const val BOOT_COUNT_KEY = "boot_count"
    private const val UNLOCK_SOURCE_KEY = "unlock_source"
    private const val PROTECTION_ARMED_WHEN_OPENED_KEY = "protection_armed_when_opened"
    private const val EXPIRY_REQUEST_CODE = 7301
    private const val UNINITIALIZED_DEADLINE = Long.MIN_VALUE

    @Volatile private var cachedDeadlineElapsed = UNINITIALIZED_DEADLINE
    @Volatile private var cachedStoredBootCount = Int.MIN_VALUE
    @Volatile private var cachedCurrentBootCount = Int.MIN_VALUE
    @Volatile private var cachedAutomaticDateTimeEnabled = false
    @Volatile private var cachedMemoryOnlyWindow = false

    /** Loads all externally-backed state before Accessibility needs a decision. */
    fun preload(context: Context) {
        ensureCacheLoaded(context)
    }

    fun requestWithCredential(
        context: Context,
        credential: String,
        protectionArmed: Boolean
    ): UnlockResult {
        if (!isAutomaticDateAndTimeEnabled(context)) {
            revoke(context)
            return UnlockResult.AUTOMATIC_DATE_TIME_REQUIRED
        }
        if (protectionArmed) {
            return UnlockResult.ACTIVE_BLOCK_REQUIRES_MONTHLY_WINDOW
        }
        if (!canScheduleExactExpiry(context)) {
            return UnlockResult.EXACT_EXPIRY_REQUIRED
        }

        return when (DeactivationCredentialManager(context).verify(credential)) {
            DeactivationCredentialManager.VerificationResult.PASSWORD_ACCEPTED,
            DeactivationCredentialManager.VerificationResult.RECOVERY_ACCEPTED -> {
                if (openWindow(context, "credential", protectionArmed = false)) {
                    UnlockResult.UNLOCKED
                } else {
                    UnlockResult.EXACT_EXPIRY_REQUIRED
                }
            }
            DeactivationCredentialManager.VerificationResult.NOT_CONFIGURED ->
                UnlockResult.CREDENTIAL_NOT_CONFIGURED
            DeactivationCredentialManager.VerificationResult.REJECTED ->
                UnlockResult.INVALID_CREDENTIAL
        }
    }

    fun requestMonthlyWindow(
        context: Context,
        protectionArmed: Boolean,
        calendar: Calendar = Calendar.getInstance()
    ): UnlockResult {
        if (!isAutomaticDateAndTimeEnabled(context)) {
            revoke(context)
            return UnlockResult.AUTOMATIC_DATE_TIME_REQUIRED
        }
        if (!isWithinMonthlyWindow(
                dayOfMonth = calendar.get(Calendar.DAY_OF_MONTH),
                hourOfDay = calendar.get(Calendar.HOUR_OF_DAY),
                minute = calendar.get(Calendar.MINUTE)
            )
        ) {
            return UnlockResult.OUTSIDE_MONTHLY_WINDOW
        }
        if (!canScheduleExactExpiry(context)) {
            return UnlockResult.EXACT_EXPIRY_REQUIRED
        }

        return if (openWindow(context, "monthly_window", protectionArmed)) {
            UnlockResult.UNLOCKED
        } else {
            UnlockResult.EXACT_EXPIRY_REQUIRED
        }
    }

    /** Memory-only after [preload] or the first controlled maintenance operation. */
    fun isTemporarilyUnlocked(context: Context): Boolean {
        ensureCacheLoaded(context)
        return cachedRemainingMillis() > 0L
    }

    /**
     * True when a maintenance window or interruption marker was persisted before
     * the current Direct Boot pass. Authorization itself never survives a reboot;
     * this marker only lets the native shield fail closed after an interrupted window.
     */
    internal fun hasPersistedWindow(context: Context): Boolean =
        preferences(context).contains(DEADLINE_ELAPSED_KEY)

    /** Preserves whether an interrupted maintenance window belonged to an active commitment. */
    internal fun wasProtectionArmedWhenOpened(context: Context): Boolean =
        preferences(context).getBoolean(PROTECTION_ARMED_WHEN_OPENED_KEY, false)

    /** Memory-only after preload; suitable for latency-sensitive callers. */
    fun remainingMillis(context: Context): Long {
        ensureCacheLoaded(context)
        return cachedRemainingMillis()
    }

    fun revoke(context: Context) {
        publishInactiveCache()
        preferences(context).edit().clear().commit()
        cancelExpiry(context)
    }

    fun isAutomaticDateAndTimeEnabled(context: Context): Boolean {
        return readGlobalBoolean(context, Settings.Global.AUTO_TIME) &&
            readGlobalBoolean(context, Settings.Global.AUTO_TIME_ZONE)
    }

    internal fun isWithinMonthlyWindow(
        dayOfMonth: Int,
        hourOfDay: Int,
        minute: Int
    ): Boolean {
        if (dayOfMonth != MONTHLY_MAINTENANCE_DAY) return false
        val currentMinute = hourOfDay * 60 + minute
        val startMinute = MONTHLY_START_HOUR * 60 + MONTHLY_START_MINUTE
        val endMinute = MONTHLY_END_HOUR * 60 + MONTHLY_END_MINUTE
        return currentMinute in startMinute until endMinute
    }

    /**
     * Direct Boot must inspect the persisted interruption marker before preload
     * gets a chance to discard a stale/foreign-boot deadline.
     */
    internal fun shouldPreloadBeforeDirectBoot(userUnlocked: Boolean): Boolean = userUnlocked

    internal fun canPersistAcrossProcess(bootCount: Int): Boolean = bootCount >= 0

    internal fun persistedAuthorizationDeadline(
        bootCount: Int,
        deadlineElapsedMillis: Long
    ): Long = if (canPersistAcrossProcess(bootCount)) deadlineElapsedMillis else 0L

    /**
     * Before Android 12 exact alarms require no special access. Android 12+ must
     * explicitly report exact-alarm capability (permission or battery exemption).
     */
    internal fun canGuaranteeExactExpiry(
        sdkInt: Int,
        exactAlarmCapability: Boolean
    ): Boolean = sdkInt < Build.VERSION_CODES.S || exactAlarmCapability

    internal fun shouldRestorePersistedMaintenance(
        remainingMillis: Long,
        exactExpiryAvailable: Boolean
    ): Boolean = remainingMillis > 0L && exactExpiryAvailable

    internal fun evaluateRemainingMillis(
        automaticDateTimeEnabled: Boolean,
        nowElapsedMillis: Long,
        deadlineElapsedMillis: Long,
        storedBootCount: Int,
        currentBootCount: Int
    ): Long {
        if (!automaticDateTimeEnabled) return 0L
        if (!canPersistAcrossProcess(storedBootCount) ||
            !canPersistAcrossProcess(currentBootCount)
        ) return 0L
        if (storedBootCount != currentBootCount) return 0L
        return max(0L, deadlineElapsedMillis - nowElapsedMillis)
    }

    private fun openWindow(
        context: Context,
        source: String,
        protectionArmed: Boolean
    ): Boolean {
        val deadline = SystemClock.elapsedRealtime() + UNLOCK_DURATION_MILLIS
        if (!scheduleExpiry(context, deadline)) return false

        val bootCount = readBootCount(context)
        val persistedDeadline = persistedAuthorizationDeadline(bootCount, deadline)
        val saved = preferences(context).edit()
            // When BOOT_COUNT is unknown, persist only a non-authorizing marker.
            // Direct Boot can still see that maintenance was interrupted and
            // re-arm protection, while a restarted process sees no valid deadline.
            .putLong(DEADLINE_ELAPSED_KEY, persistedDeadline)
            .putInt(BOOT_COUNT_KEY, bootCount)
            .putString(UNLOCK_SOURCE_KEY, source)
            .putBoolean(PROTECTION_ARMED_WHEN_OPENED_KEY, protectionArmed)
            .commit()
        if (!saved) {
            cancelExpiry(context)
            return false
        }

        cachedDeadlineElapsed = deadline
        cachedStoredBootCount = bootCount
        cachedCurrentBootCount = bootCount
        cachedAutomaticDateTimeEnabled = true
        cachedMemoryOnlyWindow = persistedDeadline <= 0L
        return true
    }

    private fun cachedRemainingMillis(): Long {
        val now = SystemClock.elapsedRealtime()
        val remaining = when {
            !cachedAutomaticDateTimeEnabled -> 0L
            cachedMemoryOnlyWindow -> max(0L, cachedDeadlineElapsed.coerceAtLeast(0L) - now)
            else -> evaluateRemainingMillis(
                automaticDateTimeEnabled = true,
                nowElapsedMillis = now,
                deadlineElapsedMillis = cachedDeadlineElapsed.coerceAtLeast(0L),
                storedBootCount = cachedStoredBootCount,
                currentBootCount = cachedCurrentBootCount
            )
        }
        if (remaining == 0L && cachedDeadlineElapsed > 0L) {
            cachedDeadlineElapsed = 0L
            cachedMemoryOnlyWindow = false
        }
        return remaining
    }

    /**
     * Maintenance weakens native Device Owner policy, so an inexact alarm is not
     * an acceptable fallback: the Android contract permits it to be delivered late.
     */
    private fun scheduleExpiry(context: Context, deadlineElapsed: Long): Boolean {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val exactCapability = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { alarmManager.canScheduleExactAlarms() }.getOrDefault(false)
        } else {
            true
        }
        if (!canGuaranteeExactExpiry(Build.VERSION.SDK_INT, exactCapability)) return false

        return runCatching {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                deadlineElapsed,
                expiryPendingIntent(context)
            )
        }.isSuccess
    }

    private fun canScheduleExactExpiry(context: Context): Boolean {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val exactCapability = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { alarmManager.canScheduleExactAlarms() }.getOrDefault(false)
        } else {
            true
        }
        return canGuaranteeExactExpiry(Build.VERSION.SDK_INT, exactCapability)
    }

    private fun cancelExpiry(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(expiryPendingIntent(context))
    }

    private fun expiryPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, DeviceOwnerMaintenanceExpiryReceiver::class.java)
            .setAction(DeviceOwnerMaintenanceExpiryReceiver.ACTION_EXPIRE_MAINTENANCE)
        return PendingIntent.getBroadcast(
            context,
            EXPIRY_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun preferences(context: Context) =
        storageContext(context).getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private fun storageContext(context: Context): Context {
        val appContext = context.applicationContext
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching { appContext.createDeviceProtectedStorageContext() }
                .getOrDefault(appContext)
        } else {
            appContext
        }
    }

    private fun ensureCacheLoaded(context: Context) {
        if (cachedDeadlineElapsed != UNINITIALIZED_DEADLINE) return
        synchronized(this) {
            if (cachedDeadlineElapsed != UNINITIALIZED_DEADLINE) return

            val prefs = preferences(context)
            val hadPersistedMarker = prefs.contains(DEADLINE_ELAPSED_KEY)
            val deadline = prefs.getLong(DEADLINE_ELAPSED_KEY, 0L)
            val storedBootCount = prefs.getInt(BOOT_COUNT_KEY, Int.MIN_VALUE)
            val currentBootCount = readBootCount(context)
            val automaticDateTimeEnabled = isAutomaticDateAndTimeEnabled(context)
            val remaining = evaluateRemainingMillis(
                automaticDateTimeEnabled = automaticDateTimeEnabled,
                nowElapsedMillis = SystemClock.elapsedRealtime(),
                deadlineElapsedMillis = deadline,
                storedBootCount = storedBootCount,
                currentBootCount = currentBootCount
            )
            val exactExpiryAvailable = canScheduleExactExpiry(context)

            cachedAutomaticDateTimeEnabled = automaticDateTimeEnabled
            cachedCurrentBootCount = currentBootCount
            cachedMemoryOnlyWindow = false
            if (shouldRestorePersistedMaintenance(remaining, exactExpiryAvailable) &&
                scheduleExpiry(context, deadline)
            ) {
                // Re-arm the exact alarm on process/package restart. Scheduling the
                // same PendingIntent replaces an existing alarm without widening it.
                cachedDeadlineElapsed = deadline
                cachedStoredBootCount = storedBootCount
            } else {
                cachedDeadlineElapsed = 0L
                cachedStoredBootCount = Int.MIN_VALUE
                if (hadPersistedMarker) {
                    // Startup cleanup is intentionally outside the accessibility hot path.
                    // This also removes non-authorizing interruption markers used only
                    // by Direct Boot after an unknown BOOT_COUNT opening.
                    prefs.edit().clear().apply()
                }
            }
        }
    }

    private fun publishInactiveCache() {
        cachedDeadlineElapsed = 0L
        cachedStoredBootCount = Int.MIN_VALUE
        cachedCurrentBootCount = Int.MIN_VALUE
        cachedAutomaticDateTimeEnabled = false
        cachedMemoryOnlyWindow = false
    }

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
