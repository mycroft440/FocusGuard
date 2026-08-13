package com.focusguard.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.content.ContextCompat
import com.focusguard.focusmode.FocusModePolicy
import com.focusguard.focusmode.FocusModeStore
import com.focusguard.utils.FocusGuardLogger

class FocusModeNotificationService : NotificationListenerService() {
    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            clearBlockedNotifications()
        }
    }

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter(ACTION_REFRESH)
        ContextCompat.registerReceiver(
            this,
            refreshReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        clearBlockedNotifications()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn ?: return
        if (shouldCancel(notification.packageName)) {
            runCatching { cancelNotification(notification.key) }
        }
    }

    private fun clearBlockedNotifications() {
        runCatching {
            activeNotifications
                .filter { shouldCancel(it.packageName) }
                .map { it.key }
                .takeIf { it.isNotEmpty() }
                ?.toTypedArray()
                ?.let(::cancelNotifications)
        }.onFailure { error ->
            FocusGuardLogger.logError(
                "FocusMode",
                "Falha ao limpar notificações bloqueadas",
                error
            )
        }
    }

    private fun shouldCancel(packageName: String): Boolean {
        val session = FocusModeStore.readSession(applicationContext)
        return FocusModePolicy.shouldSuppressNotification(
            focusModeActive = session?.isActive() == true,
            notificationPackage = packageName,
            focusGuardPackage = applicationContext.packageName,
            blockedPackages = session?.blockedPackages.orEmpty(),
            exemptPackages = session?.nonSuspendablePackages.orEmpty()
        )
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(refreshReceiver) }
        super.onDestroy()
    }

    companion object {
        private const val ACTION_REFRESH = "com.focusguard.action.REFRESH_FOCUS_NOTIFICATIONS"

        fun requestRefresh(context: Context) {
            context.applicationContext.sendBroadcast(
                Intent(ACTION_REFRESH).setPackage(context.packageName)
            )
        }
    }
}
