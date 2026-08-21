package com.focusguard.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.focusguard.R
import com.focusguard.admin.DeviceOwnerManager
import com.focusguard.database.AppDatabase
import com.focusguard.security.UsageAccessPausePolicy
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Observes Usage Access while the FocusGuard process is alive.
 *
 * Counterpart to [AccessibilityStateMonitor], which existed for Accessibility but
 * had no equivalent here — so revoking Usage Access disabled every app usage limit
 * with no warning at all. See [UsageAccessPausePolicy] for why.
 *
 * Unlike Accessibility there is no system broadcast for this permission changing,
 * so polling is the only option. The check is a cheap AppOps lookup.
 */
@Singleton
class UsageAccessStateMonitor @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val database: AppDatabase,
    private val deviceOwnerManager: DeviceOwnerManager
) {
    private companion object {
        const val TAG = "UsageAccessMonitor"
        const val POLL_INTERVAL_MS = 30_000L

        /** Shared with [AccessibilityStateMonitor]: both report protection status. */
        const val CHANNEL_ID = "focusguard_permission_status"
        const val NOTIFICATION_ID = 9002
    }

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollingRunnable: Runnable? = null
    private var lastKnownState: UsageAccessPausePolicy.State? = null

    fun start() {
        checkAndHandle(appContext)
        if (pollingRunnable != null) return
        val runnable = object : Runnable {
            override fun run() {
                checkAndHandle(appContext)
                handler.postDelayed(this, POLL_INTERVAL_MS)
            }
        }
        pollingRunnable = runnable
        handler.postDelayed(runnable, POLL_INTERVAL_MS)
    }

    fun stop() {
        pollingRunnable?.let(handler::removeCallbacks)
        pollingRunnable = null
        lastKnownState = null
    }

    private fun checkAndHandle(context: Context) {
        scope.launch {
            val granted = PermissionUtils.isUsageAccessEnabled(context)
            // Counted in the database rather than trusted from memory: the limit
            // list changes from several screens and a stale count would either
            // warn about nothing or stay silent when it matters.
            val enabledAppLimits = runCatching {
                database.appUsageLimitDao().getAllActiveLimitsStatic().size
            }.getOrElse { error ->
                FocusGuardLogger.logError(TAG, "Falha ao contar limites ativos", error)
                return@launch
            }

            val state = UsageAccessPausePolicy.evaluate(
                usageAccessGranted = granted,
                enabledAppLimitCount = enabledAppLimits
            )
            if (state == lastKnownState) return@launch
            lastKnownState = state

            if (UsageAccessPausePolicy.shouldWarn(state)) {
                FocusGuardLogger.log(
                    TAG,
                    "Acesso de uso revogado com $enabledAppLimits limite(s) ativo(s); " +
                        "limites de app pararam de ser aplicados"
                )
                handler.post { sendPausedNotification(context) }
            } else {
                handler.post { cancelPausedNotification(context) }
            }
        }
    }

    private fun sendPausedNotification(context: Context) {
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

            val deviceOwnerActive = deviceOwnerManager.isDeviceOwnerActive()
            val settingsIntent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pendingIntent = PendingIntent.getActivity(
                context,
                1,
                settingsIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val message = context.getString(
                if (deviceOwnerActive) {
                    R.string.usage_access_paused_device_owner_message
                } else {
                    R.string.usage_access_paused_message
                }
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_warning)
                .setContentTitle(context.getString(R.string.usage_access_paused_title))
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
                // Dismissible on consumer installs, persistent under Device Owner —
                // same rule as the Accessibility warning.
                .setAutoCancel(!deviceOwnerActive)
                .setOngoing(deviceOwnerActive)
                .build()
            manager.notify(NOTIFICATION_ID, notification)
        } catch (error: RuntimeException) {
            FocusGuardLogger.logError(TAG, "Falha ao enviar aviso de acesso de uso", error)
        }
    }

    private fun cancelPausedNotification(context: Context) {
        runCatching {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
                ?.cancel(NOTIFICATION_ID)
        }
    }
}
