package com.focusguard.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.focusguard.MainActivity
import com.focusguard.admin.DeviceOwnerManager
import com.focusguard.manager.BlockingSessionManager
import com.focusguard.manager.PomodoroManager
import com.focusguard.manager.StrictPomodoroLock
import com.focusguard.service.PomodoroForegroundService
import com.focusguard.ui.PomodoroLockActivity
import com.focusguard.utils.AccessibilityStateMonitor
import com.focusguard.utils.FocusGuardLogger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Boot receiver that restores FocusGuard blocking state after device reboot.
 * Supports Direct Boot (LOCKED_BOOT_COMPLETED) for pre-unlock restoration.
 * Native policies are restored before unlock; credential-backed sessions and Pomodoro UI
 * are restored only after BOOT_COMPLETED makes their storage available.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) return

        val isDirectBoot = action == Intent.ACTION_LOCKED_BOOT_COMPLETED

        // Use Device Protected Storage for Direct Boot
        val storageContext = if (isDirectBoot && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching { context.createDeviceProtectedStorageContext() }.getOrDefault(context)
        } else {
            context
        }

        FocusGuardLogger.init(storageContext)
        FocusGuardLogger.log("BootReceiver", "Boot detectado (action=$action, directBoot=$isDirectBoot)")

        val deviceOwnerManager = DeviceOwnerManager.getInstance(context)
        if (isDirectBoot) {
            // Room, Keystore and normal SharedPreferences are still unavailable here.
            // Native Device Owner policy restores app-removal protection before unlock.
            // USB and ADB intentionally remain outside FocusGuard's restriction set.
            deviceOwnerManager.applyDirectBootShield()
            FocusGuardLogger.log(
                "BootReceiver",
                "Proteção nativa restaurada antes do primeiro desbloqueio"
            )
            return
        }

        deviceOwnerManager.applyNuclearShield()
        AccessibilityStateMonitor.start(context)

        // Após BOOT_COMPLETED, os dois armazenamentos estão disponíveis.
        val isPomodoroStrictActive = StrictPomodoroLock.isActive(storageContext)
        
        if (isPomodoroStrictActive) {
            FocusGuardLogger.log("BootReceiver", "Pomodoro rigoroso ativo detectado! Restaurando imediatamente...")
            
            // 1. Iniciar serviço foreground watchdog
            PomodoroForegroundService.start(context)
            
            // 2. Agendar alarme watchdog como failsafe
            PomodoroForegroundService.scheduleWatchdogAlarm(context)
            
            // 3. Lançar tela de bloqueio
            try {
                val lockIntent = Intent(context, PomodoroLockActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                    )
                }
                context.startActivity(lockIntent)
                FocusGuardLogger.log("BootReceiver", "PomodoroLockActivity lançada após boot")
            } catch (e: Exception) {
                FocusGuardLogger.logError("BootReceiver", "Falha ao lançar LockActivity pós-boot", e)
            }
        }

        // Processamento completo em background
        val pendingResult = goAsync()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                val sessionManager = BlockingSessionManager.getInstance(context)
                val pomodoroManager = PomodoroManager.getInstance(context)

                sessionManager.checkAndEnforce()

                val hasActiveSessions = sessionManager.activeSessionsFlow.first().isNotEmpty()
                val isPomodoroActive = pomodoroManager.isPomodoroActive()

                FocusGuardLogger.log("BootReceiver", "Status após boot: Sessões=$hasActiveSessions, Pomodoro=$isPomodoroActive")

                if (hasActiveSessions && !isPomodoroActive) {
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
