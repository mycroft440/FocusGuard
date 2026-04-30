package com.focusguard.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.focusguard.manager.BlockingSessionManager
import com.focusguard.manager.PomodoroManager
import com.focusguard.utils.FocusGuardLogger
import com.focusguard.MainActivity
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            FocusGuardLogger.init(context)
            FocusGuardLogger.log("BootReceiver", "Dispositivo foi reiniciado. Acordando o FocusGuard...")
            
            val pendingResult = goAsync()
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                try {
                    val sessionManager = BlockingSessionManager.getInstance(context)
                    val pomodoroManager = PomodoroManager.getInstance(context)
                    
                    sessionManager.checkAndEnforce()
                    
                    val hasActiveSessions = sessionManager.activeSessionsFlow.first().isNotEmpty()
                    val isPomodoroActive = pomodoroManager.isPomodoroActive()
                    
                    FocusGuardLogger.log("BootReceiver", "Status após boot: Sessões=$hasActiveSessions, Pomodoro=$isPomodoroActive")

                    if (hasActiveSessions || isPomodoroActive) {
                        FocusGuardLogger.log("BootReceiver", "Restaurando interface principal devido a bloqueio ativo.")
                        val i = Intent(context, MainActivity::class.java)
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(i)
                    }
                    
                    FocusGuardLogger.log("BootReceiver", "Bloqueios restaurados com sucesso após Boot.")
                } catch (e: Exception) {
                    FocusGuardLogger.logError("BootReceiver", "Falha ao reagendar FocusGuard após o Boot", e)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
