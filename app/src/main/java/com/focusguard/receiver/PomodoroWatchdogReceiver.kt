package com.focusguard.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.focusguard.manager.StrictPomodoroLock
import com.focusguard.pomodoro.PomodoroPlanStore
import com.focusguard.service.PomodoroForegroundService
import com.focusguard.ui.PomodoroLockActivity
import com.focusguard.utils.FocusGuardLogger

/**
 * AlarmManager-triggered watchdog receiver.
 *
 * Cobre qualquer plano Pomodoro ativo. Se o processo/serviço for morto, restaura
 * o foreground service a partir do runtime persistido. A LockActivity continua
 * sendo relançada somente quando o bloqueio rigoroso estiver ativo.
 */
class PomodoroWatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        try {
            FocusGuardLogger.init(context)

            val strict = StrictPomodoroLock.isActive(context)
            val runtimeActive = PomodoroPlanStore(context.applicationContext)
                .readRuntime()
                ?.active == true

            if (!runtimeActive && !strict) {
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

            // 2. A tela de bloqueio só pertence ao Pomodoro rigoroso.
            if (strict) {
                try {
                    val lockIntent = Intent(context, PomodoroLockActivity::class.java).apply {
                        addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP
                        )
                    }
                    context.startActivity(lockIntent)
                } catch (error: Exception) {
                    FocusGuardLogger.logError(
                        "WatchdogReceiver",
                        "Falha ao relançar LockActivity",
                        error
                    )
                }
            }

            // 3. Manter uma nova reserva de recuperação para qualquer ciclo ativo.
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
