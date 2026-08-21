package com.focusguard.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.focusguard.MainActivity
import com.focusguard.R
import com.focusguard.manager.PomodoroManager
import com.focusguard.manager.StrictPomodoroLock
import com.focusguard.pomodoro.PomodoroPhase
import com.focusguard.pomodoro.PomodoroPlanStore
import com.focusguard.ui.PomodoroLockActivity
import com.focusguard.utils.FocusGuardLogger
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Serviço foreground do Pomodoro.
 *
 * Antes ele existia apenas enquanto o bloqueio rigoroso estava ativo. Agora
 * mantém também os ciclos normais (foco/pausa/foco) vivos em segundo plano. O
 * watchdog agressivo e a LockActivity continuam exclusivos do modo rigoroso.
 */
@AndroidEntryPoint
class PomodoroForegroundService : Service() {
    @Inject lateinit var pomodoroManager: PomodoroManager
    @Inject lateinit var pomodoroPlanStore: PomodoroPlanStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watchdogJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        private const val CHANNEL_ID = "focusguard_pomodoro_watchdog"
        private const val NOTIFICATION_ID = 201
        private const val WATCHDOG_ALARM_REQUEST_CODE = 3001
        private const val WATCHDOG_INTERVAL_MS = 30_000L

        fun start(context: Context) {
            val intent = Intent(context, PomodoroForegroundService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (error: Exception) {
                FocusGuardLogger.logError("PomodoroFGService", "Falha ao iniciar serviço", error)
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, PomodoroForegroundService::class.java))
            }
            cancelWatchdogAlarm(context)
        }

        fun scheduleWatchdogAlarm(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val canScheduleExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                alarmManager.canScheduleExactAlarms()
            } else {
                true
            }
            val pendingIntent = watchdogPendingIntent(context)
            val triggerAt = System.currentTimeMillis() + WATCHDOG_INTERVAL_MS
            try {
                if (canScheduleExact) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerAt,
                        pendingIntent
                    )
                } else {
                    alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
                }
            } catch (error: SecurityException) {
                FocusGuardLogger.logError(
                    "PomodoroFGService",
                    "Sem permissão de alarme exato; usando fallback",
                    error
                )
                runCatching {
                    alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
                }
            } catch (error: Throwable) {
                FocusGuardLogger.logError(
                    "PomodoroFGService",
                    "Falha ao agendar watchdog",
                    error
                )
            }
        }

        fun cancelWatchdogAlarm(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            alarmManager.cancel(watchdogPendingIntent(context))
        }

        private fun watchdogPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, com.focusguard.receiver.PomodoroWatchdogReceiver::class.java)
            return PendingIntent.getBroadcast(
                context,
                WATCHDOG_ALARM_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Força a restauração do gerente quando o Android recria somente o
        // serviço após matar o processo.
        pomodoroManager.currentSession.value

        if (!hasActivePlan()) {
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (error: Exception) {
            FocusGuardLogger.logError("PomodoroFGService", "Falha no startForeground", error)
        }

        startWatchdogLoop()
        if (StrictPomodoroLock.isActive(applicationContext)) {
            scheduleWatchdogAlarm(applicationContext)
        } else {
            cancelWatchdogAlarm(applicationContext)
        }
        return START_STICKY
    }

    private fun hasActivePlan(): Boolean {
        val runtime = pomodoroPlanStore.readRuntime()
        return runtime?.active == true || StrictPomodoroLock.isActive(applicationContext)
    }

    private fun startWatchdogLoop() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (true) {
                try {
                    if (!hasActivePlan()) {
                        cancelWatchdogAlarm(applicationContext)
                        stopSelf()
                        break
                    }

                    updateNotification()
                    if (StrictPomodoroLock.isActive(applicationContext)) {
                        ensureLockActivityOnTop()
                        scheduleWatchdogAlarm(applicationContext)
                    } else {
                        cancelWatchdogAlarm(applicationContext)
                    }
                } catch (error: Throwable) {
                    FocusGuardLogger.logError("PomodoroFGService", "Erro no loop", error)
                }
                delay(2_000L)
            }
        }
    }

    private fun ensureLockActivityOnTop() {
        try {
            val intent = Intent(applicationContext, PomodoroLockActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                )
            }
            PendingIntent.getActivity(
                applicationContext,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ).send()
        } catch (error: Exception) {
            FocusGuardLogger.logError(
                "PomodoroFGService",
                "Falha ao manter LockActivity no topo",
                error
            )
        }
    }

    private fun buildNotification(): Notification {
        val runtime = pomodoroPlanStore.readRuntime()
        val remaining = runtime?.intervalEndTime
            ?.minus(System.currentTimeMillis())
            ?.coerceAtLeast(0L)
            ?: StrictPomodoroLock.remainingMillis(applicationContext)
        val minutes = remaining / 60_000L
        val seconds = (remaining % 60_000L) / 1_000L
        val timeText = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
        val strict = StrictPomodoroLock.isActive(applicationContext)
        val phase = runtime?.phase ?: PomodoroPhase.FOCUS
        val phaseText = getString(
            when (phase) {
                PomodoroPhase.FOCUS -> R.string.fg_pomodoro_phase_focus
                PomodoroPhase.SHORT_BREAK -> R.string.fg_pomodoro_phase_short_break
                PomodoroPhase.LONG_BREAK -> R.string.fg_pomodoro_phase_long_break
            }
        )

        val targetActivity = if (strict) PomodoroLockActivity::class.java else MainActivity::class.java
        val contentIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            Intent(applicationContext, targetActivity).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (strict) {
            getString(R.string.fg_pomodoro_strict_title)
        } else {
            getString(R.string.fg_pomodoro_notification_title_phase, phaseText)
        }

        return NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(getString(R.string.fg_pomodoro_time_remaining, timeText))
            .setSmallIcon(R.drawable.ic_shield)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .build()
    }

    private fun updateNotification() {
        runCatching {
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.fg_pomodoro_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.fg_pomodoro_channel_desc)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun acquireWakeLock() {
        runCatching {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = powerManager?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "focusguard:pomodoro_cycle"
            )?.apply {
                acquire(12 * 60 * 60 * 1_000L)
            }
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        wakeLock = null
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (StrictPomodoroLock.isActive(applicationContext)) {
            scheduleWatchdogAlarm(applicationContext)
        }
    }

    override fun onDestroy() {
        watchdogJob?.cancel()
        releaseWakeLock()
        if (StrictPomodoroLock.isActive(applicationContext)) {
            scheduleWatchdogAlarm(applicationContext)
        }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
