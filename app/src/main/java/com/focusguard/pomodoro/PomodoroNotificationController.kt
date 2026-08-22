package com.focusguard.pomodoro

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.focusguard.utils.SecurePrefsManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Controla o modo Não Perturbe usado pelo Pomodoro.
 *
 * Usamos INTERRUPTION_FILTER_ALARMS: notificações ficam silenciosas, mas o
 * alarme do próprio Pomodoro continua audível no stream de alarme.
 */
@Singleton
class PomodoroNotificationController @Inject constructor(
    @ApplicationContext context: Context
) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(NotificationManager::class.java)
    private val prefs = SecurePrefsManager(appContext).prefs

    fun hasPolicyAccess(): Boolean = manager?.isNotificationPolicyAccessGranted == true

    fun policyAccessIntent(): Intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun notificationListenerIntent(): Intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun hasNotificationListenerAccess(listenerClassName: String): Boolean {
        val component = ComponentName(appContext.packageName, listenerClassName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            return manager?.isNotificationListenerAccessGranted(component) == true
        }

        // Android 8.0 não possui isNotificationListenerAccessGranted(). A lista
        // segura de componentes autorizados é a fonte usada pelo próprio painel
        // de configurações nessa versão.
        val enabled = Settings.Secure.getString(
            appContext.contentResolver,
            "enabled_notification_listeners"
        ).orEmpty()
        return enabled.split(':')
            .mapNotNull(ComponentName::unflattenFromString)
            .any { it == component }
    }

    /**
     * Guarda uma única vez o filtro que existia antes do plano. O snapshot é
     * usado também quando o modo rigoroso está ativo com o toggle "silenciar"
     * desligado, pois o bloqueador legado muda o DND para PRIORITY por conta
     * própria e precisamos desfazer essa mudança sem perder a escolha do usuário.
     */
    fun captureCurrentFilter(): Boolean {
        val notificationManager = manager ?: return false
        if (!notificationManager.isNotificationPolicyAccessGranted) return false
        if (prefs.contains(KEY_PREVIOUS_FILTER)) return true
        return prefs.edit()
            .putInt(KEY_PREVIOUS_FILTER, notificationManager.currentInterruptionFilter)
            .commit()
    }

    fun apply(config: PomodoroPlanConfig): Boolean {
        if (!config.silenceNotifications) return true
        val notificationManager = manager ?: return false
        if (!notificationManager.isNotificationPolicyAccessGranted) return false
        if (!captureCurrentFilter()) return false

        return runCatching {
            notificationManager.setInterruptionFilter(
                NotificationManager.INTERRUPTION_FILTER_ALARMS
            )
            true
        }.getOrDefault(false)
    }

    /**
     * Reaplica temporariamente o estado anterior sem apagar o snapshot. É usado
     * entre fases de um mesmo plano quando "Silenciar notificações" está
     * desligado, para neutralizar o DND automático do bloqueio rigoroso.
     */
    fun restoreForActivePlan() {
        restoreInternal(clearSnapshot = false)
    }

    /**
     * Restaura o estado anterior e encerra a posse do Pomodoro sobre o DND.
     *
     * Em Android 15+ para apps target 35+, setInterruptionFilter() controla uma
     * AutomaticZenRule pertencente ao próprio app. Nesse modelo, ALL desativa a
     * regra do FocusGuard e deixa as demais regras/Não Perturbe do usuário
     * continuarem sendo a fonte de verdade. Em versões anteriores restauramos o
     * filtro global que existia antes de o Pomodoro assumir o controle.
     */
    fun restore() {
        restoreInternal(clearSnapshot = true)
    }

    private fun restoreInternal(clearSnapshot: Boolean) {
        val hadPreviousFilter = prefs.contains(KEY_PREVIOUS_FILTER)
        val notificationManager = manager

        try {
            if (notificationManager?.isNotificationPolicyAccessGranted == true) {
                when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM -> {
                        // No target 35+, ALL desativa a regra automática do app;
                        // não substitui o DND global configurado pelo usuário.
                        runCatching {
                            notificationManager.setInterruptionFilter(
                                NotificationManager.INTERRUPTION_FILTER_ALL
                            )
                        }
                    }
                    hadPreviousFilter -> {
                        val previous = sanitizeFilter(
                            prefs.getInt(
                                KEY_PREVIOUS_FILTER,
                                NotificationManager.INTERRUPTION_FILTER_ALL
                            )
                        )
                        runCatching { notificationManager.setInterruptionFilter(previous) }
                    }
                }
            }
        } finally {
            if (clearSnapshot && hadPreviousFilter) {
                prefs.edit().remove(KEY_PREVIOUS_FILTER).commit()
            }
        }
    }

    private fun sanitizeFilter(filter: Int): Int = when (filter) {
        NotificationManager.INTERRUPTION_FILTER_ALL,
        NotificationManager.INTERRUPTION_FILTER_PRIORITY,
        NotificationManager.INTERRUPTION_FILTER_NONE,
        NotificationManager.INTERRUPTION_FILTER_ALARMS -> filter
        else -> NotificationManager.INTERRUPTION_FILTER_ALL
    }

    companion object {
        private const val KEY_PREVIOUS_FILTER = "pomodoro.previous_interruption_filter"
    }
}
