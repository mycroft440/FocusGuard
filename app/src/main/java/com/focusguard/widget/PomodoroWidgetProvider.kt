package com.focusguard.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.Toast
import com.focusguard.MainActivity
import com.focusguard.R
import com.focusguard.manager.PomodoroManager
import com.focusguard.pomodoro.PomodoroPlanStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Widget de início rápido do Pomodoro.
 *
 * Mostra sempre o plano salvo mais recente. O botão com a seta circular inicia
 * exatamente esse plano; alterações feitas em "Configurar Pomodoro" atualizam
 * todos os widgets imediatamente.
 */
class PomodoroWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach { widgetId ->
            appWidgetManager.updateAppWidget(widgetId, buildRemoteViews(context))
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION_START_POMODORO) return

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val manager = PomodoroManager.getInstance(context.applicationContext)
                if (!manager.isPomodoroActive()) {
                    val config = PomodoroPlanStore(context.applicationContext).loadConfig()
                    manager.startPlan(config)
                }
                updateAll(context)
            } catch (_: Exception) {
                launch(Dispatchers.Main) {
                    Toast.makeText(
                        context.applicationContext,
                        context.getString(R.string.pomodoro_widget_start_error),
                        Toast.LENGTH_LONG
                    ).show()
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val ACTION_START_POMODORO =
            "com.focusguard.action.START_POMODORO_FROM_WIDGET"
        private const val START_REQUEST_CODE = 5201
        private const val OPEN_REQUEST_CODE = 5202

        fun updateAll(context: Context) {
            val appContext = context.applicationContext
            val manager = AppWidgetManager.getInstance(appContext)
            val component = ComponentName(appContext, PomodoroWidgetProvider::class.java)
            val ids = manager.getAppWidgetIds(component)
            if (ids.isEmpty()) return
            val views = buildRemoteViews(appContext)
            ids.forEach { manager.updateAppWidget(it, views) }
        }

        private fun buildRemoteViews(context: Context): RemoteViews {
            val config = PomodoroPlanStore(context).loadConfig()
            val manager = PomodoroManager.getInstance(context.applicationContext)
            val active = manager.isPomodoroActive()
            val longBreak = formatDuration(config.longBreakMinutes)
            val sessions = if (config.targetSessions == 0) {
                context.getString(R.string.pomodoro_widget_sessions_infinite)
            } else {
                context.getString(
                    R.string.pomodoro_widget_sessions_finite,
                    config.targetSessions
                )
            }

            return RemoteViews(context.packageName, R.layout.widget_pomodoro).apply {
                setTextViewText(R.id.widget_pomodoro_title, context.getString(R.string.pomodoro_widget_title))
                setTextViewText(
                    R.id.widget_pomodoro_summary,
                    context.getString(
                        R.string.pomodoro_widget_summary,
                        config.focusMinutes,
                        config.shortBreakMinutes,
                        longBreak
                    )
                )
                setTextViewText(R.id.widget_pomodoro_sessions, sessions)
                setTextViewText(
                    R.id.widget_pomodoro_start,
                    context.getString(
                        if (active) R.string.pomodoro_widget_active
                        else R.string.pomodoro_widget_start
                    )
                )

                val startIntent = Intent(context, PomodoroWidgetProvider::class.java)
                    .setAction(ACTION_START_POMODORO)
                val startPendingIntent = PendingIntent.getBroadcast(
                    context,
                    START_REQUEST_CODE,
                    startIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                setOnClickPendingIntent(R.id.widget_pomodoro_start, startPendingIntent)

                val openIntent = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                val openPendingIntent = PendingIntent.getActivity(
                    context,
                    OPEN_REQUEST_CODE,
                    openIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                setOnClickPendingIntent(R.id.widget_pomodoro_root, openPendingIntent)
            }
        }

        private fun formatDuration(minutes: Int): String {
            val safe = minutes.coerceAtLeast(0)
            return if (safe < 60) {
                "${safe}m"
            } else {
                "%02d:%02d".format(safe / 60, safe % 60)
            }
        }
    }
}
