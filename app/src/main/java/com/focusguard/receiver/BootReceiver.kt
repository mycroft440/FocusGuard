package com.focusguard.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.focusguard.MainActivity
import com.focusguard.admin.DeviceOwnerManager
import com.focusguard.focusmode.FocusModeKioskController
import com.focusguard.focusmode.FocusModeManager
import com.focusguard.manager.BlockingSessionManager
import com.focusguard.manager.PomodoroManager
import com.focusguard.manager.StrictPomodoroLock
import com.focusguard.service.PomodoroForegroundService
import com.focusguard.ui.PomodoroLockActivity
import com.focusguard.utils.AccessibilityStateMonitor
import com.focusguard.utils.FocusGuardLogger
import com.focusguard.utils.UsageAccessStateMonitor
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Boot receiver that restores FocusGuard blocking state after device reboot.
 * Supports Direct Boot (LOCKED_BOOT_COMPLETED) for pre-unlock restoration.
 * Native policies are restored before unlock; credential-backed sessions and Pomodoro UI
 * are restored only after BOOT_COMPLETED makes their storage available.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {
    @Inject lateinit var deviceOwnerManager: DeviceOwnerManager
    @Inject lateinit var sessionManager: BlockingSessionManager
    @Inject lateinit var pomodoroManager: PomodoroManager
    @Inject lateinit var focusModeManager: FocusModeManager
    @Inject lateinit var kioskController: FocusModeKioskController
    @Inject lateinit var accessibilityStateMonitor: AccessibilityStateMonitor
    @Inject lateinit var usageAccessStateMonitor: UsageAccessStateMonitor

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val storageContext = context

        FocusGuardLogger.init(storageContext)
        FocusGuardLogger.log("BootReceiver", "BOOT_COMPLETED detectado")

        deviceOwnerManager.applyNuclearShield()
        accessibilityStateMonitor.start()
        usageAccessStateMonitor.start()

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
                sessionManager.checkAndEnforce()
                val focusModeActive = focusModeManager.ensureEnforced()
                kioskController.reconcileSystemRestrictions()

                val hasActiveSessions = sessionManager.activeSessionsFlow.first().isNotEmpty()
                val isPomodoroActive = pomodoroManager.isPomodoroActive()

                FocusGuardLogger.log(
                    "BootReceiver",
                    "Status após boot: Sessões=$hasActiveSessions, Pomodoro=$isPomodoroActive, " +
                        "ModoFoco=$focusModeActive"
                )

                if (focusModeActive) {
                    // Focus Mode takes precedence over the normal home restore. On
                    // Device Owner Android 9+, this launch enters Lock Task in the
                    // same startActivity call, eliminating the launcher escape gap.
                    val restored = kioskController.launchFocusGuardHome()
                    if (!restored) {
                        context.startActivity(
                            Intent(context, MainActivity::class.java).apply {
                                addFlags(
                                    Intent.FLAG_ACTIVITY_NEW_TASK or
                                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                                )
                            }
                        )
                    }
                    FocusGuardLogger.log(
                        "BootReceiver",
                        "Interface do Modo Foco restaurada após boot"
                    )
                } else if (hasActiveSessions && !isPomodoroActive) {
                    FocusGuardLogger.log(
                        "BootReceiver",
                        "Restaurando interface principal devido a bloqueio ativo."
                    )
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
