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
import com.focusguard.MainActivity
import com.focusguard.R
import com.focusguard.manager.PomodoroManager
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
        private const val REQUEST_CONFIGURE = 5101
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
            val now = System.currentTimeMillis()

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

            val remainingMillis = runtime?.let {
                (it.intervalEndTime - now).coerceAtLeast(0L)
            } ?: displayConfig.focusMinutes.coerceAtLeast(1) * 60_000L
            val durationMillis = runtime?.intervalDurationMillis
                ?.takeIf { it > 0L }
                ?: phaseDurationMillis(runtime?.phase, displayConfig)
            val activeProgress = runtime?.let {
                if (durationMillis <= 0L) 0f
                else (remainingMillis.toFloat() / durationMillis.toFloat()).coerceIn(0f, 1f)
            }

            views.setImageViewBitmap(
                R.id.widget_pomodoro_clock,
                PomodoroWidgetClockRenderer.render(
                    context = context,
                    minutes = displayConfig.focusMinutes.coerceIn(1, 180),
                    maxMinutes = 180,
                    activeProgress = activeProgress,
                    remainingMillis = remainingMillis
                )
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
            views.setBoolean(R.id.widget_pomodoro_start, "setEnabled", runtime == null)

            val configureIntent = Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_POMODORO, true)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            val configurePendingIntent = PendingIntent.getActivity(
                context,
                REQUEST_CONFIGURE,
                configureIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(
                R.id.widget_pomodoro_configure,
                configurePendingIntent
            )

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

        private fun phaseDurationMillis(
            phase: PomodoroPhase?,
            config: com.focusguard.pomodoro.PomodoroPlanConfig
        ): Long {
            val minutes = when (phase) {
                PomodoroPhase.SHORT_BREAK -> config.shortBreakMinutes
                PomodoroPhase.LONG_BREAK -> config.longBreakMinutes
                PomodoroPhase.FOCUS,
                null -> config.focusMinutes
            }
            return minutes.coerceAtLeast(1) * 60_000L
        }
    }
}
