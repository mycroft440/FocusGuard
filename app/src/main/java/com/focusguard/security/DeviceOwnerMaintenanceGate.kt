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

    fun isTemporarilyUnlocked(context: Context): Boolean = remainingMillis(context) > 0L

    /**
     * True when a maintenance window was persisted before the current Direct Boot pass.
     * A reboot always invalidates that window, but the native shield uses this signal to
     * fail closed before clearing the stale deadline.
     */
    internal fun hasPersistedWindow(context: Context): Boolean =
        preferences(context).contains(DEADLINE_ELAPSED_KEY)

    /** Preserves whether an interrupted maintenance window belonged to an active commitment. */
    internal fun wasProtectionArmedWhenOpened(context: Context): Boolean =
        preferences(context).getBoolean(PROTECTION_ARMED_WHEN_OPENED_KEY, false)

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
        val saved = preferences(context).edit()
            .putLong(DEADLINE_ELAPSED_KEY, deadline)
            .putInt(BOOT_COUNT_KEY, readBootCount(context))
            .putString(UNLOCK_SOURCE_KEY, source)
            .putBoolean(PROTECTION_ARMED_WHEN_OPENED_KEY, protectionArmed)
            .commit()
        check(saved) { "Não foi possível abrir a janela de manutenção" }
        scheduleExpiry(context, deadline)
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
