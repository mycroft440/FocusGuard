package com.focusguard.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.focusguard.MainActivity
import com.focusguard.R
import com.focusguard.focusmode.FocusModeManager
import com.focusguard.focusmode.FocusModeStore
import com.focusguard.receiver.FocusModeReceiver
import com.focusguard.utils.FocusGuardLogger
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class FocusModeForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var timerJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val session = FocusModeStore.readSession(applicationContext)
        if (session?.isActive() != true) {
            scope.launch { FocusModeManager.getInstance(applicationContext).finishExpiredSession() }
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildNotification(session.remainingMillis())
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
            FocusGuardLogger.logError("FocusMode", "Falha ao iniciar serviço foreground", error)
        }

        FocusModeReceiver.scheduleExpiration(applicationContext, session.endTimeMillis)
        startTimerLoop()
        return START_STICKY
    }

    private fun startTimerLoop() {
        timerJob?.cancel()
        timerJob = scope.launch {
            while (true) {
                val session = FocusModeStore.readSession(applicationContext)
                val remaining = session?.remainingMillis() ?: 0L
                if (remaining <= 0L) {
                    FocusModeManager.getInstance(applicationContext).finishExpiredSession()
                    stopSelf()
                    break
                }
                getSystemService(NotificationManager::class.java)?.notify(
                    NOTIFICATION_ID,
                    buildNotification(remaining)
                )
                delay(minOf(UPDATE_INTERVAL_MILLIS, remaining))
            }
        }
    }

    private fun buildNotification(remainingMillis: Long): Notification {
        val totalMinutes = (remainingMillis.coerceAtLeast(0L) + 59_999L) / 60_000L
        val hours = totalMinutes / 60L
        val minutes = totalMinutes % 60L
        val timeText = String.format(Locale.getDefault(), "%02d:%02d", hours, minutes)
        val openIntent = Intent(applicationContext, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(getString(R.string.focus_mode_notification_title))
            .setContentText(getString(R.string.focus_mode_notification_time, timeText))
            .setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.focus_mode_notification_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.focus_mode_notification_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        FocusModeStore.readSession(applicationContext)?.takeIf { it.isActive() }?.let {
            FocusModeReceiver.scheduleExpiration(applicationContext, it.endTimeMillis)
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        timerJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "focusguard_focus_mode"
        private const val NOTIFICATION_ID = 511
        private const val UPDATE_INTERVAL_MILLIS = 30_000L

        fun start(context: Context) {
            val intent = Intent(context.applicationContext, FocusModeForegroundService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.applicationContext.startForegroundService(intent)
                } else {
                    context.applicationContext.startService(intent)
                }
            }.onFailure { error ->
                FocusGuardLogger.logError("FocusMode", "Falha ao iniciar temporizador", error)
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.applicationContext.stopService(
                    Intent(context.applicationContext, FocusModeForegroundService::class.java)
                )
            }
        }
    }
}
