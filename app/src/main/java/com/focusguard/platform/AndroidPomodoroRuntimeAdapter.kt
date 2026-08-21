package com.focusguard.platform

import android.content.Context
import android.content.Intent
import com.focusguard.contract.EnforcementUiContract
import com.focusguard.domain.port.PomodoroRuntimePort
import com.focusguard.pomodoro.PomodoroNotificationController
import com.focusguard.service.BlockingAccessibilityService
import com.focusguard.service.FocusModeNotificationService
import com.focusguard.service.PomodoroForegroundService
import com.focusguard.ui.PomodoroLockActivity
import com.focusguard.utils.FocusGuardLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidPomodoroRuntimeAdapter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val notificationController: PomodoroNotificationController
) : PomodoroRuntimePort {

    override fun hasNotificationListenerAccess(): Boolean =
        notificationController.hasNotificationListenerAccess(
            EnforcementUiContract.FOCUS_MODE_NOTIFICATION_SERVICE_CLASS_NAME
        )

    override fun requestNotificationRefresh() {
        FocusModeNotificationService.requestRefresh(context)
    }

    override fun startForegroundTimer() {
        PomodoroForegroundService.start(context)
    }

    override fun stopForegroundTimer() {
        PomodoroForegroundService.stop(context)
    }

    override fun scheduleWatchdog() {
        PomodoroForegroundService.scheduleWatchdogAlarm(context)
    }

    override fun cancelWatchdog() {
        PomodoroForegroundService.cancelWatchdogAlarm(context)
    }

    override fun publishBlockingChanged() {
        context.sendBroadcast(
            Intent(BlockingAccessibilityService.ACTION_REFRESH_BLOCKING)
                .setPackage(context.packageName)
        )
    }

    override fun launchStrictLock() {
        val intent = Intent(context, PomodoroLockActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
        }
        runCatching { context.startActivity(intent) }
            .onFailure { error ->
                FocusGuardLogger.logError(
                    "PomodoroRuntime",
                    "Falha ao abrir bloqueio rigoroso",
                    error
                )
            }
    }
}
