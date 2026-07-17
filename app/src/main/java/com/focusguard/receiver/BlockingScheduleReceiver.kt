package com.focusguard.receiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.focusguard.database.BlockSession
import com.focusguard.manager.BlockingSessionManager
import com.focusguard.utils.FocusGuardLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Reconcilia políticas nativas exatamente nas mudanças de janela de bloqueio. */
class BlockingScheduleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action !in SUPPORTED_ACTIONS) return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                BlockingSessionManager.getInstance(context).checkAndEnforce()
            } catch (error: Exception) {
                FocusGuardLogger.logError(
                    "BlockingSchedule",
                    "Falha ao reconciliar mudança de horário",
                    error
                )
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val ACTION_RECONCILE_BLOCKING =
            "com.focusguard.ACTION_RECONCILE_BLOCKING"
        private const val REQUEST_CODE = 4102
        private val SUPPORTED_ACTIONS = setOf(
            ACTION_RECONCILE_BLOCKING,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED
        )

        fun scheduleNext(
            context: Context,
            sessions: Collection<BlockSession>,
            additionalBoundaries: Collection<Long>,
            nowMillis: Long = System.currentTimeMillis()
        ) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                ?: return
            val operation = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                Intent(context, BlockingScheduleReceiver::class.java)
                    .setAction(ACTION_RECONCILE_BLOCKING),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val nextBoundary = BlockingScheduleCalculator.nextBoundary(
                sessions = sessions,
                additionalBoundaries = additionalBoundaries,
                nowMillis = nowMillis
            )
            if (nextBoundary == null) {
                alarmManager.cancel(operation)
                return
            }

            try {
                val exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                    alarmManager.canScheduleExactAlarms()
                if (exactAllowed) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        nextBoundary,
                        operation
                    )
                } else {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        nextBoundary,
                        operation
                    )
                }
            } catch (error: SecurityException) {
                FocusGuardLogger.logError(
                    "BlockingSchedule",
                    "Alarme exato indisponível; usando reconciliação inexata",
                    error
                )
                runCatching {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        nextBoundary,
                        operation
                    )
                }.onFailure {
                    FocusGuardLogger.logError(
                        "BlockingSchedule",
                        "Falha ao agendar reconciliação inexata",
                        it
                    )
                }
            }
        }
    }
}
