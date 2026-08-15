package com.focusguard.receiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.focusguard.focusmode.FocusModeKioskController
import com.focusguard.focusmode.FocusModeManager
import com.focusguard.utils.FocusGuardLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class FocusModeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val supported = intent.action == ACTION_EXPIRE ||
            intent.action == Intent.ACTION_TIME_CHANGED ||
            intent.action == Intent.ACTION_TIMEZONE_CHANGED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        if (!supported) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val manager = FocusModeManager.getInstance(context)
                val active = if (intent.action == ACTION_EXPIRE) {
                    manager.finishExpiredSession()
                    manager.isActive()
                } else {
                    manager.ensureEnforced()
                }
                FocusModeKioskController.reconcileSystemRestrictions(context)

                // Updating FocusGuard can recreate the app process and drop the
                // visible task even though Device Owner policies survive. Restore
                // the Focus Mode shell exactly like a reboot, but do not steal the
                // foreground on ordinary clock/timezone changes.
                if (active && intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
                    FocusModeKioskController.launchFocusGuardHome(context)
                }
            } catch (error: Exception) {
                FocusGuardLogger.logError(
                    "FocusMode",
                    "Falha ao reconciliar o temporizador",
                    error
                )
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val ACTION_EXPIRE = "com.focusguard.action.FOCUS_MODE_EXPIRE"
        private const val REQUEST_CODE = 5101

        fun scheduleExpiration(context: Context, endTimeMillis: Long) {
            if (endTimeMillis <= System.currentTimeMillis()) return
            val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
            val pendingIntent = pendingIntent(context)
            try {
                val canScheduleExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                    alarmManager.canScheduleExactAlarms()
                when {
                    canScheduleExact && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ->
                        alarmManager.setExactAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            endTimeMillis,
                            pendingIntent
                        )
                    canScheduleExact -> alarmManager.setExact(
                        AlarmManager.RTC_WAKEUP,
                        endTimeMillis,
                        pendingIntent
                    )
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ->
                        alarmManager.setAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            endTimeMillis,
                            pendingIntent
                        )
                    else -> alarmManager.set(
                        AlarmManager.RTC_WAKEUP,
                        endTimeMillis,
                        pendingIntent
                    )
                }
            } catch (error: SecurityException) {
                FocusGuardLogger.logError(
                    "FocusMode",
                    "Alarme exato indisponível; usando alarme comum",
                    error
                )
                runCatching {
                    alarmManager.set(AlarmManager.RTC_WAKEUP, endTimeMillis, pendingIntent)
                }
            }
        }

        fun cancelExpiration(context: Context) {
            val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
            runCatching { alarmManager.cancel(pendingIntent(context)) }
        }

        private fun pendingIntent(context: Context): PendingIntent {
            val intent = Intent(context.applicationContext, FocusModeReceiver::class.java)
                .setAction(ACTION_EXPIRE)
            return PendingIntent.getBroadcast(
                context.applicationContext,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}
