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
        val action = intent.action
        if (action != ACTION_START_POMODORO &&
            action != ACTION_STOP_POMODORO &&
            action != ACTION_DECREMENT_SESSIONS &&
            action != ACTION_INCREMENT_SESSIONS
        ) {
            return
        }

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                when (action) {
                    ACTION_START_POMODORO -> startPomodoro(context)
                    ACTION_STOP_POMODORO -> stopPomodoro(context)
                    ACTION_DECREMENT_SESSIONS -> adjustTargetSessions(context, -1)
                    ACTION_INCREMENT_SESSIONS -> adjustTargetSessions(context, 1)
                }
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

    private suspend fun startPomodoro(context: Context) {
        val store = PomodoroPlanStore(context)
        if (store.readRuntime()?.active == true) return

        val manager = PomodoroManager.getInstance(context.applicationContext)
        manager.startPlan(store.loadConfig().copy(strictBlocking = false))
    }

    private suspend fun stopPomodoro(context: Context) {
        val store = PomodoroPlanStore(context)
        if (store.readRuntime()?.active != true) return

        val manager = PomodoroManager.getInstance(context.applicationContext)
        manager.stopSession()
    }

    private fun adjustTargetSessions(context: Context, delta: Int) {
        val store = PomodoroPlanStore(context)
        if (store.readRuntime()?.active == true) return

        val current = store.loadConfig()
        val target = (current.targetSessions + delta).coerceIn(
            MIN_TARGET_SESSIONS,
            MAX_TARGET_SESSIONS
        )
        if (target != current.targetSessions) {
            store.saveConfig(current.copy(targetSessions = target))
        }
    }

    companion object {
        private const val ACTION_START_POMODORO =
            "com.focusguard.action.START_POMODORO_FROM_WIDGET"
        private const val ACTION_STOP_POMODORO =
            "com.focusguard.action.STOP_POMODORO_FROM_WIDGET"
        private const val ACTION_DECREMENT_SESSIONS =
            "com.focusguard.action.DECREMENT_POMODORO_SESSIONS_FROM_WIDGET"
        private const val ACTION_INCREMENT_SESSIONS =
            "com.focusguard.action.INCREMENT_POMODORO_SESSIONS_FROM_WIDGET"

        private const val REQUEST_CONFIGURE = 5101
        private const val REQUEST_START = 5102
        private const val REQUEST_STOP = 5103
        private const val REQUEST_DECREMENT_SESSIONS = 5104
        private const val REQUEST_INCREMENT_SESSIONS = 5105
        private const val REQUEST_DIAL = 5106
        private const val MIN_TARGET_SESSIONS = 0
        private const val MAX_TARGET_SESSIONS = 5

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

            val dialIntent = Intent(context, PomodoroWidgetDialActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val dialPendingIntent = PendingIntent.getActivity(
                context,
                REQUEST_DIAL,
                dialIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(
                R.id.widget_pomodoro_clock,
                if (runtime == null) dialPendingIntent else null
            )

            val targetSessions = displayConfig.targetSessions.coerceIn(
                MIN_TARGET_SESSIONS,
                MAX_TARGET_SESSIONS
            )
            val targetLabel = if (targetSessions == 0) {
                context.getString(R.string.fg_pomodoro_until_i_stop)
            } else {
                targetSessions.toString()
            }
            views.setTextViewText(
                R.id.widget_pomodoro_sessions_value,
                context.getString(R.string.fg_pomodoro_sessions_label, targetLabel)
            )

            val sessionsEditable = runtime == null
            views.setBoolean(
                R.id.widget_pomodoro_sessions_decrement,
                "setEnabled",
                sessionsEditable && targetSessions > MIN_TARGET_SESSIONS
            )
            views.setBoolean(
                R.id.widget_pomodoro_sessions_increment,
                "setEnabled",
                sessionsEditable && targetSessions < MAX_TARGET_SESSIONS
            )

            val decrementIntent = Intent(context, PomodoroWidgetProvider::class.java)
                .setAction(ACTION_DECREMENT_SESSIONS)
            val decrementPendingIntent = PendingIntent.getBroadcast(
                context,
                REQUEST_DECREMENT_SESSIONS,
                decrementIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(
                R.id.widget_pomodoro_sessions_decrement,
                decrementPendingIntent
            )

            val incrementIntent = Intent(context, PomodoroWidgetProvider::class.java)
                .setAction(ACTION_INCREMENT_SESSIONS)
            val incrementPendingIntent = PendingIntent.getBroadcast(
                context,
                REQUEST_INCREMENT_SESSIONS,
                incrementIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(
                R.id.widget_pomodoro_sessions_increment,
                incrementPendingIntent
            )

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

            val isRunning = runtime != null
            views.setTextViewText(
                R.id.widget_pomodoro_start,
                context.getString(
                    if (isRunning) R.string.fg_pomodoro_stop else R.string.fg_pomodoro_start
                )
            )
            views.setBoolean(R.id.widget_pomodoro_start, "setEnabled", true)

            val primaryIntent = Intent(context, PomodoroWidgetProvider::class.java)
                .setAction(if (isRunning) ACTION_STOP_POMODORO else ACTION_START_POMODORO)
            val primaryPendingIntent = PendingIntent.getBroadcast(
                context,
                if (isRunning) REQUEST_STOP else REQUEST_START,
                primaryIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_pomodoro_start, primaryPendingIntent)

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
