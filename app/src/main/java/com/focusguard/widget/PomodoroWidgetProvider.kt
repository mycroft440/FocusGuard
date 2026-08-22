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
import com.focusguard.pomodoro.PomodoroPhase
import com.focusguard.pomodoro.PomodoroPlanStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class PomodoroWidgetProvider : AppWidgetProvider() {
    @Inject lateinit var pomodoroManager: PomodoroManager
    @Inject lateinit var pomodoroPlanStore: PomodoroPlanStore
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach { appWidgetId ->
            updateWidget(context, appWidgetManager, appWidgetId, pomodoroPlanStore)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION_START_POMODORO) return

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                // "Iniciar" nunca deve reiniciar silenciosamente um ciclo que já
                // está rodando. A edição pelo relógio continua valendo para o
                // próximo plano, mas o runtime atual permanece imutável.
                if (pomodoroPlanStore.readRuntime()?.active == true) {
                    requestUpdate(context)
                    return@launch
                }

                pomodoroManager.startPlan(pomodoroPlanStore.loadConfig())
                requestUpdate(context)
            } catch (error: Exception) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(
                        context.applicationContext,
                        error.message ?: context.getString(
                            R.string.fg_pomodoro_widget_permissions_error
                        ),
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
            val widgetIds = manager.getAppWidgetIds(component)
            appContext.sendBroadcast(
                Intent(appContext, PomodoroWidgetProvider::class.java).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, widgetIds)
                }
            )
        }

        private fun updateWidget(
            context: Context,
            manager: AppWidgetManager,
            appWidgetId: Int,
            store: PomodoroPlanStore
        ) {
            val savedConfig = store.loadConfig()
            val runtime = store.readRuntime()?.takeIf { it.active }
            val displayConfig = runtime?.config ?: savedConfig
            val views = RemoteViews(context.packageName, R.layout.widget_pomodoro)

            val phaseLabel = runtime?.let {
                context.getString(
                    when (it.phase) {
                        PomodoroPhase.FOCUS -> R.string.fg_pomodoro_phase_focus
                        PomodoroPhase.SHORT_BREAK -> R.string.fg_pomodoro_phase_short_break
                        PomodoroPhase.LONG_BREAK -> R.string.fg_pomodoro_phase_long_break
                    }
                )
            }
            views.setTextViewText(
                R.id.widget_pomodoro_title,
                phaseLabel?.let {
                    context.getString(R.string.fg_pomodoro_widget_title_phase, it)
                } ?: context.getString(R.string.fg_pomodoro_title)
            )
            views.setTextViewText(
                R.id.widget_pomodoro_focus,
                context.getString(
                    R.string.fg_pomodoro_widget_focus_break,
                    formatMinutes(displayConfig.focusMinutes),
                    formatMinutes(displayConfig.shortBreakMinutes)
                )
            )
            views.setTextViewText(
                R.id.widget_pomodoro_break,
                context.getString(
                    R.string.fg_pomodoro_widget_long_break,
                    formatMinutes(displayConfig.longBreakMinutes),
                    displayConfig.longBreakEvery
                )
            )
            views.setTextViewText(
                R.id.widget_pomodoro_sessions,
                if (runtime != null) {
                    if (runtime.config.targetSessions == 0) {
                        context.getString(
                            R.string.fg_pomodoro_widget_completed_unlimited,
                            runtime.completedFocusSessions
                        )
                    } else {
                        context.getString(
                            R.string.fg_pomodoro_widget_completed_target,
                            runtime.completedFocusSessions,
                            runtime.config.targetSessions
                        )
                    }
                } else {
                    val target = if (savedConfig.targetSessions == 0) {
                        context.getString(R.string.fg_pomodoro_until_i_stop)
                    } else {
                        savedConfig.targetSessions.toString()
                    }
                    context.getString(R.string.fg_pomodoro_widget_sessions, target)
                }
            )
            views.setTextViewText(
                R.id.widget_pomodoro_start,
                context.getString(
                    if (runtime != null) {
                        R.string.fg_pomodoro_running
                    } else {
                        R.string.fg_pomodoro_start
                    }
                )
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
