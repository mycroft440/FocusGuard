package com.focusguard.utils

import android.accessibilityservice.AccessibilityServiceInfo
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
import com.focusguard.admin.DeviceOwnerManager
import com.focusguard.manager.BlockingSessionManager
import com.focusguard.service.BlockingAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Observes Accessibility state while the FocusGuard process is alive.
 *
 * Disabling the service never opens a blocking activity or prevents normal use
 * of the device. The user receives a clear notification explaining that real-time
 * blocking is paused. On Device Owner installations, native policies are reconciled
 * immediately and the warning remains visible until Accessibility is restored.
 */
object AccessibilityStateMonitor {

    private const val TAG = "A11yStateMonitor"
    private const val POLL_INTERVAL_MS = 30_000L
    private const val ACTION_STATE_CHANGED =
        "android.accessibilityservice.ACCESSIBILITY_SERVICE_STATE_CHANGED"
    private const val CHANNEL_ID = "focusguard_permission_status"
    private const val NOTIFICATION_ID = 9001

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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
        if (enabled) {
            cancelPausedNotification(appContext)
        } else if (protectionShouldBeMonitored(appContext)) {
            handlePausedProtection(appContext)
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
        when {
            enabled && previous != true -> cancelPausedNotification(context)
            !enabled && previous != false && protectionShouldBeMonitored(context) -> {
                FocusGuardLogger.log(
                    TAG,
                    "Acessibilidade desativada; bloqueio em tempo real pausado"
                )
                handlePausedProtection(context)
            }
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

    private fun protectionShouldBeMonitored(context: Context): Boolean {
        val onboardingCompleted = context
            .getSharedPreferences("FocusGuardPrefs", Context.MODE_PRIVATE)
            .getBoolean("hasSeenOnboarding", false)
        return onboardingCompleted || DeviceOwnerManager.getInstance(context).isDeviceOwnerActive()
    }

    private fun handlePausedProtection(context: Context) {
        val deviceOwnerActive = DeviceOwnerManager.getInstance(context).isDeviceOwnerActive()
        sendPausedNotification(context, deviceOwnerActive)
        if (!deviceOwnerActive) return

        scope.launch {
            FocusGuardLogger.log(
                TAG,
                "Reconciliando políticas nativas após pausa da Acessibilidade"
            )
            BlockingSessionManager.getInstance(context).checkAndEnforce()
        }
    }

    private fun sendPausedNotification(context: Context, deviceOwnerActive: Boolean) {
        try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as? NotificationManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        context.getString(R.string.accessibility_paused_channel),
                        NotificationManager.IMPORTANCE_DEFAULT
                    ).apply {
                        description = context.getString(
                            R.string.accessibility_paused_channel_description
                        )
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
            val message = context.getString(
                if (deviceOwnerActive) {
                    R.string.accessibility_paused_device_owner_message
                } else {
                    R.string.accessibility_paused_message
                }
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_warning)
                .setContentTitle(context.getString(R.string.accessibility_paused_title))
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setPriority(
                    if (deviceOwnerActive) {
                        NotificationCompat.PRIORITY_HIGH
                    } else {
                        NotificationCompat.PRIORITY_DEFAULT
                    }
                )
                .setContentIntent(pendingIntent)
                .setAutoCancel(!deviceOwnerActive)
                .setOngoing(deviceOwnerActive)
                .build()
            manager.notify(NOTIFICATION_ID, notification)
        } catch (error: RuntimeException) {
            FocusGuardLogger.logError(TAG, "Falha ao enviar aviso de acessibilidade", error)
        }
    }

    private fun cancelPausedNotification(context: Context) {
        runCatching {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
                ?.cancel(NOTIFICATION_ID)
        }
    }
}
