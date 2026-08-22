package com.focusguard.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.focusguard.pomodoro.StrictPomodoroLock
import com.focusguard.service.PomodoroForegroundService
import com.focusguard.ui.PomodoroLockActivity
import com.focusguard.utils.FocusGuardLogger

/**
 * AlarmManager-triggered watchdog receiver.
 * Fires every ~30s during an active strict Pomodoro session.
 * Guarantees recovery even if the foreground service is killed by the system.
 */
class PomodoroWatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        try {
            FocusGuardLogger.init(context)
            
            if (!StrictPomodoroLock.isActive(context)) {
                FocusGuardLogger.log("WatchdogReceiver", "Pomodoro não ativo. Ignorando alarme.")
                PomodoroForegroundService.cancelWatchdogAlarm(context)
                return
            }

            FocusGuardLogger.log("WatchdogReceiver", "Alarme watchdog disparado. Verificando integridade do bloqueio...")

            // 1. Garantir que o serviço foreground está rodando
            PomodoroForegroundService.start(context)

            // 2. Garantir que a LockActivity está visível
            try {
                val lockIntent = Intent(context, PomodoroLockActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                    )
                }
                context.startActivity(lockIntent)
            } catch (e: Exception) {
                FocusGuardLogger.logError("WatchdogReceiver", "Falha ao relançar LockActivity", e)
            }

            // 3. Reagendar o próximo alarme
            PomodoroForegroundService.scheduleWatchdogAlarm(context)

            FocusGuardLogger.log("WatchdogReceiver", "Verificação do watchdog concluída. Próximo alarme agendado.")
        } catch (e: Exception) {
            FocusGuardLogger.logError("WatchdogReceiver", "Erro no watchdog receiver", e)
            // Mesmo em erro, tenta reagendar
            runCatching { PomodoroForegroundService.scheduleWatchdogAlarm(context) }
        }
    }
}
