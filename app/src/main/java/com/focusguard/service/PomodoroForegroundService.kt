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
import com.focusguard.R
import com.focusguard.manager.StrictPomodoroLock
import com.focusguard.ui.PomodoroLockActivity
import com.focusguard.utils.FocusGuardLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Foreground Service that acts as an unkillable watchdog for the Pomodoro strict lock.
 * Ensures the lock screen stays active even if the app process is killed.
 * Uses START_STICKY + AlarmManager failsafe for maximum persistence.
 */
class PomodoroForegroundService : Service() {

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
            } catch (e: Exception) {
                FocusGuardLogger.logError("PomodoroFGService", "Falha ao iniciar serviço", e)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, PomodoroForegroundService::class.java)
            try {
                context.stopService(intent)
            } catch (e: Exception) {
                FocusGuardLogger.logError("PomodoroFGService", "Falha ao parar serviço", e)
            }
            cancelWatchdogAlarm(context)
        }

        fun scheduleWatchdogAlarm(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(context, com.focusguard.receiver.PomodoroWatchdogReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                WATCHDOG_ALARM_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val triggerAt = System.currentTimeMillis() + WATCHDOG_INTERVAL_MS
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
                } else {
                    alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
                }
            } catch (e: Exception) {
                FocusGuardLogger.logError("PomodoroFGService", "Falha ao agendar alarme watchdog", e)
            }
        }

        fun cancelWatchdogAlarm(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(context, com.focusguard.receiver.PomodoroWatchdogReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                WATCHDOG_ALARM_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(pendingIntent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
        FocusGuardLogger.log("PomodoroFGService", "onCreate: Watchdog criado")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!StrictPomodoroLock.isActive(applicationContext)) {
            FocusGuardLogger.log("PomodoroFGService", "Nenhum Pomodoro ativo. Encerrando serviço.")
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
        } catch (e: Exception) {
            FocusGuardLogger.logError("PomodoroFGService", "Falha no startForeground", e)
        }

        startWatchdogLoop()
        scheduleWatchdogAlarm(applicationContext)

        FocusGuardLogger.log("PomodoroFGService", "onStartCommand: Watchdog ativo e vigilante")
        return START_STICKY
    }

    private fun startWatchdogLoop() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (true) {
                try {
                    if (!StrictPomodoroLock.isActive(applicationContext)) {
                        FocusGuardLogger.log("PomodoroFGService", "Pomodoro expirou. Encerrando watchdog.")
                        cancelWatchdogAlarm(applicationContext)
                        stopSelf()
                        break
                    }

                    // Atualizar notificação com tempo restante
                    updateNotification()

                    // Garantir que a PomodoroLockActivity esteja no topo
                    ensureLockActivityOnTop()

                                } catch (e: Exception) {
                    FocusGuardLogger.logError("PomodoroFGService", "Erro no loop watchdog", e)
                }
                delay(300)
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
            val pendingIntent = PendingIntent.getActivity(
                applicationContext,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            pendingIntent.send()
        } catch (e: Exception) {
            FocusGuardLogger.logError("PomodoroFGService", "Falha ao garantir LockActivity no topo via PendingIntent", e)
            try {
                val fbIntent = Intent(applicationContext, PomodoroLockActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                applicationContext.startActivity(fbIntent)
            } catch (_: Exception) {}
        }
    }

    private fun buildNotification(): Notification {
        val remaining = StrictPomodoroLock.remainingMillis(applicationContext)
        val minutes = remaining / 60000
        val seconds = (remaining % 60000) / 1000
        val timeText = String.format("%02d:%02d", minutes, seconds)

        val lockIntent = Intent(applicationContext, PomodoroLockActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext, 0, lockIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("🔒 Pomodoro Rigoroso Ativo")
            .setContentText("Tempo restante: $timeText")
            .setSmallIcon(R.drawable.ic_shield)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification() {
        try {
            val notification = buildNotification()
            val manager = getSystemService(NotificationManager::class.java)
            manager?.notify(NOTIFICATION_ID, notification)
        } catch (_: Exception) {}
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Pomodoro Watchdog",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Garante que o bloqueio rigoroso do Pomodoro permaneça ativo"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = powerManager?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "focusguard:pomodoro_watchdog"
            )?.apply {
                acquire(4 * 60 * 60 * 1000L) // Máximo 4h
            }
        } catch (e: Exception) {
            FocusGuardLogger.logError("PomodoroFGService", "Falha ao adquirir WakeLock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {}
        wakeLock = null
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        FocusGuardLogger.log("PomodoroFGService", "onTaskRemoved: Reagendando watchdog")
        if (StrictPomodoroLock.isActive(applicationContext)) {
            scheduleWatchdogAlarm(applicationContext)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        watchdogJob?.cancel()
        releaseWakeLock()
        if (StrictPomodoroLock.isActive(applicationContext)) {
            FocusGuardLogger.log("PomodoroFGService", "onDestroy durante Pomodoro ativo! Reagendando...")
            scheduleWatchdogAlarm(applicationContext)
        }
        scope.cancel()
        FocusGuardLogger.log("PomodoroFGService", "onDestroy: Watchdog destruído")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
