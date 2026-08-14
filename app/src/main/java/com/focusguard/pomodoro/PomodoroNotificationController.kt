package com.focusguard.pomodoro

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.focusguard.utils.SecurePrefsManager

/**
 * Controla o modo Não Perturbe usado pelo Pomodoro.
 *
 * Usamos INTERRUPTION_FILTER_ALARMS: notificações ficam silenciosas, mas o
 * alarme do próprio Pomodoro continua audível no stream de alarme.
 */
class PomodoroNotificationController(context: Context) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(NotificationManager::class.java)
    private val prefs = SecurePrefsManager(appContext).prefs

    fun hasPolicyAccess(): Boolean = manager?.isNotificationPolicyAccessGranted == true

    fun policyAccessIntent(): Intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun notificationListenerIntent(): Intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun hasNotificationListenerAccess(listenerClass: Class<*>): Boolean {
        val notificationManager = manager ?: return false
        val component = android.content.ComponentName(appContext, listenerClass)
        return notificationManager.isNotificationListenerAccessGranted(component)
    }

    fun apply(config: PomodoroPlanConfig): Boolean {
        if (!config.silenceNotifications) return true
        val notificationManager = manager ?: return false
        if (!notificationManager.isNotificationPolicyAccessGranted) return false

        if (!prefs.contains(KEY_PREVIOUS_FILTER)) {
            prefs.edit()
                .putInt(KEY_PREVIOUS_FILTER, notificationManager.currentInterruptionFilter)
                .commit()
        }
        return runCatching {
            notificationManager.setInterruptionFilter(
                NotificationManager.INTERRUPTION_FILTER_ALARMS
            )
            true
        }.getOrDefault(false)
    }

    fun restore() {
        val notificationManager = manager ?: return
        val previous = prefs.getInt(
            KEY_PREVIOUS_FILTER,
            NotificationManager.INTERRUPTION_FILTER_ALL
        )
        if (notificationManager.isNotificationPolicyAccessGranted) {
            runCatching { notificationManager.setInterruptionFilter(previous) }
        }
        prefs.edit().remove(KEY_PREVIOUS_FILTER).commit()
    }

    companion object {
        private const val KEY_PREVIOUS_FILTER = "pomodoro.previous_interruption_filter"
    }
}
