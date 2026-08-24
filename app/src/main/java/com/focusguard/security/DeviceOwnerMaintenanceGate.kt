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
 * The persisted record is validated once and then published into volatile fields.
 * Accessibility hot paths only read elapsedRealtime + these cached values; they do
 * not synchronously touch SharedPreferences, Settings.Global or DevicePolicyManager.
 */
object DeviceOwnerMaintenanceGate {

    enum class UnlockResult {
        UNLOCKED,
        AUTOMATIC_DATE_TIME_REQUIRED,
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

    @Volatile private var cacheLoaded = false
    @Volatile private var cachedDeadlineElapsed = 0L
    @Volatile private var cachedStoredBootCount = Int.MIN_VALUE
    @Volatile private var cachedCurrentBootCount = Int.MIN_VALUE
    @Volatile private var cachedAutomaticDateTimeEnabled = false
    @Volatile private var cachedProtectionArmedWhenOpened = false

    fun preload(context: Context) {
        refreshCache(context)
    }

    /**
     * Slow validation entry point. Call from lifecycle/refresh work, not from the
     * Accessibility callback. It republishes a complete coherent-enough cache for
     * the lock-free hot read below.
     */
    fun refreshCache(context: Context) {
        val prefs = preferences(context)
        val deadline = prefs.getLong(DEADLINE_ELAPSED_KEY, 0L)
        val storedBootCount = prefs.getInt(BOOT_COUNT_KEY, Int.MIN_VALUE)
        val currentBootCount = readBootCount(context)
        val automaticDateTimeEnabled = isAutomaticDateAndTimeEnabled(context)
        val protectionArmed = prefs.getBoolean(PROTECTION_ARMED_WHEN_OPENED_KEY, false)

        cachedDeadlineElapsed = deadline
        cachedStoredBootCount = storedBootCount
        cachedCurrentBootCount = currentBootCount
        cachedAutomaticDateTimeEnabled = automaticDateTimeEnabled
        cachedProtectionArmedWhenOpened = protectionArmed
        cacheLoaded = true
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

        return when (DeactivationCredentialManager(context).verify(credential)) {
            DeactivationCredentialManager.VerificationResult.PASSWORD_ACCEPTED,
            DeactivationCredentialManager.VerificationResult.RECOVERY_ACCEPTED -> {
                openWindow(context, "credential", protectionArmed = false)
                UnlockResult.UNLOCKED
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

        openWindow(context, "monthly_window", protectionArmed)
        return UnlockResult.UNLOCKED
    }

    /** Hot-path compatible after preload. First-ever access still fails safe by loading once. */
    fun isTemporarilyUnlocked(context: Context): Boolean {
        ensureCacheLoaded(context)
        return isTemporarilyUnlockedCached()
    }

    /** No I/O, Settings or Binder. Intended for Accessibility event processing. */
    fun isTemporarilyUnlockedCached(nowElapsedMillis: Long = SystemClock.elapsedRealtime()): Boolean {
        if (!cacheLoaded) return false
        return evaluateRemainingMillis(
            automaticDateTimeEnabled = cachedAutomaticDateTimeEnabled,
            nowElapsedMillis = nowElapsedMillis,
            deadlineElapsedMillis = cachedDeadlineElapsed,
            storedBootCount = cachedStoredBootCount,
            currentBootCount = cachedCurrentBootCount
        ) > 0L
    }

    /**
     * True when a maintenance window was persisted before the current Direct Boot pass.
     * This intentionally remains a persisted read: Direct Boot policy setup is not an
     * Accessibility hot path.
     */
    internal fun hasPersistedWindow(context: Context): Boolean =
        preferences(context).contains(DEADLINE_ELAPSED_KEY)

    internal fun wasProtectionArmedWhenOpened(context: Context): Boolean =
        if (cacheLoaded) cachedProtectionArmedWhenOpened
        else preferences(context).getBoolean(PROTECTION_ARMED_WHEN_OPENED_KEY, false)

    fun remainingMillis(context: Context): Long {
        ensureCacheLoaded(context)
        return remainingMillisCached()
    }

    fun remainingMillisCached(nowElapsedMillis: Long = SystemClock.elapsedRealtime()): Long {
        if (!cacheLoaded) return 0L
        return evaluateRemainingMillis(
            automaticDateTimeEnabled = cachedAutomaticDateTimeEnabled,
            nowElapsedMillis = nowElapsedMillis,
            deadlineElapsedMillis = cachedDeadlineElapsed,
            storedBootCount = cachedStoredBootCount,
            currentBootCount = cachedCurrentBootCount
        )
    }

    fun revoke(context: Context) {
        cachedDeadlineElapsed = 0L
        cachedStoredBootCount = Int.MIN_VALUE
        cachedCurrentBootCount = Int.MIN_VALUE
        cachedAutomaticDateTimeEnabled = false
        cachedProtectionArmedWhenOpened = false
        cacheLoaded = true
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

    private fun openWindow(context: Context, source: String, protectionArmed: Boolean) {
        val deadline = SystemClock.elapsedRealtime() + UNLOCK_DURATION_MILLIS
        val bootCount = readBootCount(context)
        val saved = preferences(context).edit()
            .putLong(DEADLINE_ELAPSED_KEY, deadline)
            .putInt(BOOT_COUNT_KEY, bootCount)
            .putString(UNLOCK_SOURCE_KEY, source)
            .putBoolean(PROTECTION_ARMED_WHEN_OPENED_KEY, protectionArmed)
            .commit()
        check(saved) { "Não foi possível abrir a janela de manutenção" }

        cachedDeadlineElapsed = deadline
        cachedStoredBootCount = bootCount
        cachedCurrentBootCount = bootCount
        cachedAutomaticDateTimeEnabled = true
        cachedProtectionArmedWhenOpened = protectionArmed
        cacheLoaded = true
        scheduleExpiry(context, deadline)
    }

    private fun ensureCacheLoaded(context: Context) {
        if (!cacheLoaded) refreshCache(context)
    }

    private fun scheduleExpiry(context: Context, deadlineElapsed: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val operation = expiryPendingIntent(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarmManager.canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                deadlineElapsed,
                operation
            )
        } else {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                deadlineElapsed,
                operation
            )
        }
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
