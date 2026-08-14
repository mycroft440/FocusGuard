package com.focusguard.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat
import com.focusguard.focusmode.FocusModePolicy
import com.focusguard.focusmode.FocusModeStore
import com.focusguard.pomodoro.PomodoroPlanStore
import com.focusguard.utils.FocusGuardLogger

/**
 * Um único NotificationListenerService atende Modo Foco e Pomodoro. Assim o
 * usuário concede acesso às notificações uma vez só e o FocusGuard não cria
 * dois listeners concorrentes.
 */
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
                "NotificationFilter",
                "Falha ao limpar notificações bloqueadas",
                error
            )
        }
    }

    private fun shouldCancel(packageName: String): Boolean {
        if (shouldPomodoroHide(packageName)) return true

        val session = FocusModeStore.readSession(applicationContext)
        return FocusModePolicy.shouldSuppressNotification(
            focusModeActive = session?.isActive() == true,
            notificationPackage = packageName,
            focusGuardPackage = applicationContext.packageName,
            blockedPackages = session?.blockedPackages.orEmpty(),
            exemptPackages = session?.nonSuspendablePackages.orEmpty()
        )
    }

    private fun shouldPomodoroHide(packageName: String): Boolean {
        val runtime = PomodoroPlanStore(applicationContext).readRuntime()
        if (runtime?.active != true || !runtime.config.hideNotifications) return false
        if (packageName == applicationContext.packageName) return false

        // Não escondemos componentes essenciais do sistema nem o discador para
        // preservar chamadas durante um Pomodoro, inclusive no modo rigoroso.
        if (packageName == "android" || packageName == "com.android.systemui") return false
        val telecom = getSystemService(TelecomManager::class.java)
        if (packageName == telecom?.defaultDialerPackage) return false

        return true
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
