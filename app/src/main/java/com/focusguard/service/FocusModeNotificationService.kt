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
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Um único NotificationListenerService atende Modo Foco e Pomodoro.
 *
 * Notificações ocultadas pelo FocusGuard nunca são canceladas: elas são
 * temporariamente "snoozed" pelo Android até o fim da janela que pediu a
 * ocultação. Assim o conteúdo continua pertencendo ao app de origem e volta a
 * aparecer quando o período termina.
 */
@AndroidEntryPoint
class FocusModeNotificationService : NotificationListenerService() {
    @Inject lateinit var pomodoroPlanStore: PomodoroPlanStore
    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshNotificationPolicy()
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
        refreshNotificationPolicy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn ?: return
        updateListenerHints()
        snoozeIfNeeded(notification)
    }

    /**
     * Reaplica a política às notificações que já estavam visíveis quando uma
     * sessão começou. O serviço não apaga nenhuma delas: [snoozeNotification]
     * delega ao sistema a ocultação temporária e a republicação no vencimento.
     */
    private fun refreshNotificationPolicy() {
        updateListenerHints()
        runCatching {
            activeNotifications
                .orEmpty()
                .forEach(::snoozeIfNeeded)
        }.onFailure { error ->
            FocusGuardLogger.logError(
                "NotificationFilter",
                "Falha ao ocultar temporariamente notificações",
                error
            )
        }
    }

    private fun snoozeIfNeeded(notification: StatusBarNotification) {
        val durationMillis = suppressionDurationMillis(notification.packageName) ?: return
        runCatching {
            snoozeNotification(
                notification.key,
                durationMillis.coerceAtLeast(MIN_SNOOZE_DURATION_MILLIS)
            )
        }.onFailure { error ->
            FocusGuardLogger.logError(
                "NotificationFilter",
                "Falha ao adiar notificação durante o período de foco",
                error
            )
        }
    }

    /**
     * Retorna por quanto tempo a notificação deve ficar fora da central.
     *
     * Se Modo Foco e Pomodoro coincidirem, usa o término mais distante para não
     * fazer a notificação reaparecer enquanto uma das duas janelas ainda pede
     * ocultação.
     */
    private fun suppressionDurationMillis(
        packageName: String,
        nowMillis: Long = System.currentTimeMillis()
    ): Long? {
        if (isEssentialNotificationPackage(packageName)) return null

        var suppressUntilMillis = 0L

        val focusSession = FocusModeStore.readSession(applicationContext)
        if (
            focusSession?.isActive(nowMillis) == true &&
            FocusModePolicy.shouldSuppressNotification(
                focusModeActive = true,
                notificationPackage = packageName,
                focusGuardPackage = applicationContext.packageName,
                blockedPackages = focusSession.blockedPackages,
                // O fato de um pacote não poder ser suspenso pelo Device Owner
                // não torna sua notificação permitida. A ocultação é uma política
                // independente e precisa funcionar também nesses aparelhos/apps.
                exemptPackages = emptySet()
            )
        ) {
            suppressUntilMillis = maxOf(suppressUntilMillis, focusSession.endTimeMillis)
        }

        val pomodoroRuntime = pomodoroPlanStore.readRuntime()
        if (
            pomodoroRuntime?.active == true &&
            pomodoroRuntime.config.hideNotifications &&
            pomodoroRuntime.intervalEndTime > nowMillis
        ) {
            suppressUntilMillis = maxOf(
                suppressUntilMillis,
                pomodoroRuntime.intervalEndTime
            )
        }

        return (suppressUntilMillis - nowMillis).takeIf { it > 0L }
    }

    /**
     * O Modo Foco silencia os efeitos das notificações enquanto estiver ativo.
     * HINT_HOST_DISABLE_NOTIFICATION_EFFECTS não solicita o bloqueio dos efeitos
     * de chamadas; ele atua somente sobre efeitos de notificações do host.
     */
    private fun updateListenerHints() {
        val focusModeActive = FocusModeStore.readSession(applicationContext)?.isActive() == true
        val hints = if (focusModeActive) {
            HINT_HOST_DISABLE_NOTIFICATION_EFFECTS
        } else {
            0
        }
        runCatching { requestListenerHints(hints) }
            .onFailure { error ->
                FocusGuardLogger.logError(
                    "NotificationFilter",
                    "Falha ao atualizar silêncio temporário do Modo Foco",
                    error
                )
            }
    }

    private fun isEssentialNotificationPackage(packageName: String): Boolean {
        if (packageName == applicationContext.packageName) return true
        if (packageName == "android" || packageName == "com.android.systemui") return true

        val telecom = getSystemService(TelecomManager::class.java)
        return packageName == telecom?.defaultDialerPackage
    }

    override fun onDestroy() {
        // Libera explicitamente qualquer pedido de silêncio feito por este
        // listener. Se o Android nos reconectar durante uma sessão, onConnected
        // reaplica imediatamente a política correta.
        runCatching { requestListenerHints(0) }
        runCatching { unregisterReceiver(refreshReceiver) }
        super.onDestroy()
    }

    companion object {
        private const val ACTION_REFRESH = "com.focusguard.action.REFRESH_FOCUS_NOTIFICATIONS"
        private const val MIN_SNOOZE_DURATION_MILLIS = 1_000L

        fun requestRefresh(context: Context) {
            context.applicationContext.sendBroadcast(
                Intent(ACTION_REFRESH).setPackage(context.packageName)
            )
        }
    }
}
