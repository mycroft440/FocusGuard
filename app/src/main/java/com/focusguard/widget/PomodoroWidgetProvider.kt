package com.focusguard.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.RemoteViews
import android.widget.Toast
import com.focusguard.R
import com.focusguard.manager.PomodoroManager
import com.focusguard.pomodoro.PomodoroCyclePolicy
import com.focusguard.pomodoro.PomodoroPhase
import com.focusguard.pomodoro.PomodoroPlanStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PomodoroWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach { appWidgetId ->
            updateWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION_START_POMODORO) return

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val store = PomodoroPlanStore(context)
                // "Iniciar" nunca deve reiniciar silenciosamente um ciclo que já
                // está rodando. A edição pelo relógio continua valendo para o
                // próximo plano, mas o runtime atual permanece imutável.
                if (store.readRuntime()?.active == true) {
                    requestUpdate(context)
                    return@launch
                }

                val manager = PomodoroManager.getInstance(context.applicationContext)
                manager.startPlan(store.loadConfig())
                requestUpdate(context)
            } catch (error: Exception) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(
                        context.applicationContext,
                        error.message ?: "Abra o FocusGuard para revisar as permissões do Pomodoro.",
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
        private const val REQUEST_OPEN_DIAL = 5101
        private const val REQUEST_START = 5102

        fun requestUpdate(context: Context) {
            val appContext = context.applicationContext
            val manager = AppWidgetManager.getInstance(appContext)
            val component = ComponentName(appContext, PomodoroWidgetProvider::class.java)
            manager.getAppWidgetIds(component).forEach { widgetId ->
                updateWidget(appContext, manager, widgetId)
            }
        }

        private fun updateWidget(
            context: Context,
            manager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val store = PomodoroPlanStore(context)
            val savedConfig = store.loadConfig()
            val runtime = store.readRuntime()?.takeIf { it.active }
            val displayConfig = runtime?.config ?: savedConfig
            val views = RemoteViews(context.packageName, R.layout.widget_pomodoro)

            views.setTextViewText(
                R.id.widget_pomodoro_title,
                runtime?.let {
                    when (it.phase) {
                        PomodoroPhase.FOCUS -> "Pomodoro • Foco"
                        PomodoroPhase.SHORT_BREAK -> "Pomodoro • Pausa"
                        PomodoroPhase.LONG_BREAK -> "Pomodoro • Pausa longa"
                    }
                } ?: "Pomodoro"
            )
            views.setTextViewText(
                R.id.widget_pomodoro_focus,
                "Foco ${formatMinutes(displayConfig.focusMinutes)} • pausa ${formatMinutes(displayConfig.shortBreakMinutes)}"
            )
            views.setTextViewText(
                R.id.widget_pomodoro_break,
                "Longa ${formatMinutes(displayConfig.longBreakMinutes)} a cada ${displayConfig.longBreakEvery} sessões"
            )
            views.setTextViewText(
                R.id.widget_pomodoro_sessions,
                if (runtime != null) {
                    val target = if (runtime.config.targetSessions == 0) {
                        "até eu parar"
                    } else {
                        "${runtime.config.targetSessions}"
                    }
                    "Concluídas ${runtime.completedFocusSessions} • $target"
                } else {
                    "Sessões: ${PomodoroCyclePolicy.targetLabel(savedConfig)}"
                }
            )
            views.setTextViewText(
                R.id.widget_pomodoro_start,
                if (runtime != null) "Em andamento" else "Iniciar"
            )

            val dialIntent = Intent(context, PomodoroWidgetDialActivity::class.java)
            val dialPendingIntent = PendingIntent.getActivity(
                context,
                REQUEST_OPEN_DIAL,
                dialIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_pomodoro_dial, dialPendingIntent)

            val startIntent = Intent(context, PomodoroWidgetProvider::class.java)
                .setAction(ACTION_START_POMODORO)
            val startPendingIntent = PendingIntent.getBroadcast(
                context,
                REQUEST_START,
                startIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_pomodoro_start, startPendingIntent)

            manager.updateAppWidget(appWidgetId, views)
        }

        private fun formatMinutes(minutes: Int): String {
            val hours = minutes / 60
            val remaining = minutes % 60
            return when {
                hours == 0 -> "${minutes}m"
                remaining == 0 -> "${hours}h"
                else -> "${hours}h${remaining}m"
            }
        }
    }
}
