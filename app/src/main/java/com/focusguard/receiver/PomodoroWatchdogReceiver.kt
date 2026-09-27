package com.focusguard.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.focusguard.pomodoro.PomodoroPlanStore
import com.focusguard.service.PomodoroForegroundService
import com.focusguard.utils.FocusGuardLogger

/**
 * AlarmManager-triggered watchdog receiver.
 *
 * Cobre qualquer plano Pomodoro ativo. Se o processo/serviço for morto, restaura
 * o foreground service a partir do runtime persistido.
 */
class PomodoroWatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        try {
            FocusGuardLogger.init(context)

            val runtimeActive = PomodoroPlanStore(context.applicationContext)
                .readRuntime()
                ?.active == true

            if (!runtimeActive) {
                FocusGuardLogger.log(
                    "WatchdogReceiver",
                    "Nenhum Pomodoro ativo. Ignorando alarme."
                )
                PomodoroForegroundService.cancelWatchdogAlarm(context)
                return
            }

            FocusGuardLogger.log(
                "WatchdogReceiver",
                "Alarme watchdog disparado. Restaurando integridade do Pomodoro..."
            )

            // 1. Garantir que o serviço foreground e o PomodoroManager sejam restaurados.
            PomodoroForegroundService.start(context)

            // 2. Manter uma nova reserva de recuperação para qualquer ciclo ativo.
            PomodoroForegroundService.scheduleWatchdogAlarm(context)

            FocusGuardLogger.log(
                "WatchdogReceiver",
                "Recuperação concluída. Próximo watchdog agendado."
            )
        } catch (error: Exception) {
            FocusGuardLogger.logError("WatchdogReceiver", "Erro no watchdog receiver", error)
            // Mesmo em erro, tenta manter a reserva somente se ainda houver plano ativo.
            runCatching { PomodoroForegroundService.scheduleWatchdogAlarm(context) }
        }
    }
}
