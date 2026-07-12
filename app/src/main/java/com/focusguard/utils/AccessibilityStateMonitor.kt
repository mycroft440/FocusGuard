package com.focusguard.utils

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationCompat
import com.focusguard.R
import com.focusguard.service.BlockingAccessibilityService
import com.focusguard.ui.AccessibilityDisabledActivity

/**
 * Detecta alterações no serviço de acessibilidade enquanto o processo do app
 * está vivo. Não tenta prometer detecção após Force Stop, pois nesse cenário o
 * Android encerra o próprio processo responsável pelo monitor.
 */
object AccessibilityStateMonitor {

    private const val TAG = "A11yStateMonitor"
    private const val POLL_INTERVAL_MS = 30_000L
    private const val ACTION_STATE_CHANGED =
        "android.accessibilityservice.ACCESSIBILITY_SERVICE_STATE_CHANGED"

    private val handler = Handler(Looper.getMainLooper())
    private var pollingRunnable: Runnable? = null
    private var receiverRegistered = false
    private var lastKnownEnabled: Boolean? = null

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (context != null && intent?.action == ACTION_STATE_CHANGED) {
                checkAndHandle(context.applicationContext)
            }
        }
    }

    fun start(context: Context) {
        val appContext = context.applicationContext
        if (!receiverRegistered) {
            try {
                val filter = IntentFilter(ACTION_STATE_CHANGED)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    appContext.registerReceiver(
                        stateReceiver,
                        filter,
                        Context.RECEIVER_NOT_EXPORTED
                    )
                } else {
                    @Suppress("DEPRECATION")
                    appContext.registerReceiver(stateReceiver, filter)
                }
                receiverRegistered = true
            } catch (error: RuntimeException) {
                FocusGuardLogger.logError(TAG, "Falha ao registrar monitor", error)
            }
        }

        val enabled = isAccessibilityServiceEnabled(appContext)
        lastKnownEnabled = enabled
        if (!enabled && protectionWasConfigured(appContext)) {
            onAccessibilityDisabled(appContext)
        }
        startPolling(appContext)
    }

    fun stop(context: Context) {
        if (receiverRegistered) {
            runCatching { context.applicationContext.unregisterReceiver(stateReceiver) }
            receiverRegistered = false
        }
        pollingRunnable?.let(handler::removeCallbacks)
        pollingRunnable = null
        lastKnownEnabled = null
    }

    private fun startPolling(context: Context) {
        if (pollingRunnable != null) return
        val runnable = object : Runnable {
            override fun run() {
                checkAndHandle(context)
                handler.postDelayed(this, POLL_INTERVAL_MS)
            }
        }
        pollingRunnable = runnable
        handler.postDelayed(runnable, POLL_INTERVAL_MS)
    }

    private fun checkAndHandle(context: Context) {
        val enabled = isAccessibilityServiceEnabled(context)
        val previous = lastKnownEnabled
        if (!enabled && protectionWasConfigured(context) && previous != false) {
            FocusGuardLogger.logError(
                TAG,
                "BlockingAccessibilityService foi desativado enquanto o processo estava ativo",
                null
            )
            onAccessibilityDisabled(context)
        }
        lastKnownEnabled = enabled
    }

    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        return try {
            val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE)
                as? AccessibilityManager ?: return false
            val expected = ComponentName(context, BlockingAccessibilityService::class.java)
            manager.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK
            ).any { info ->
                val serviceInfo = info.resolveInfo?.serviceInfo ?: return@any false
                ComponentName(serviceInfo.packageName, serviceInfo.name) == expected
            }
        } catch (error: RuntimeException) {
            FocusGuardLogger.logError(TAG, "Falha ao verificar serviço", error)
            false
        }
    }

    private fun protectionWasConfigured(context: Context): Boolean {
        return context.getSharedPreferences("FocusGuardPrefs", Context.MODE_PRIVATE)
            .getBoolean("hasSeenOnboarding", false)
    }

    private fun onAccessibilityDisabled(context: Context) {
        try {
            context.startActivity(
                Intent(context, AccessibilityDisabledActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                    )
                }
            )
        } catch (error: RuntimeException) {
            FocusGuardLogger.logError(TAG, "Falha ao abrir alerta de acessibilidade", error)
            sendCriticalNotification(context)
        }
    }

    private fun sendCriticalNotification(context: Context) {
        try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as? NotificationManager ?: return
            val channelId = "focusguard_critical"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        channelId,
                        "Alertas críticos",
                        NotificationManager.IMPORTANCE_HIGH
                    ).apply {
                        description = "Avisos sobre a proteção do FocusGuard"
                        enableVibration(true)
                    }
                )
            }

            val settingsIntent = Intent(
                android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                settingsIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_warning)
                .setContentTitle("Proteção desativada")
                .setContentText("Toque para reativar o FocusGuard")
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(Notification.CATEGORY_ERROR)
                .setContentIntent(pendingIntent)
                .setAutoCancel(false)
                .setOngoing(true)
                .build()
            manager.notify(9001, notification)
        } catch (error: RuntimeException) {
            FocusGuardLogger.logError(TAG, "Falha ao enviar notificação crítica", error)
        }
    }
}
