package com.focusguard.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.focusguard.security.TimedBlockProtectionController
import com.focusguard.service.BlockingAccessibilityService
import com.focusguard.utils.FocusGuardLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Keeps the uninstall guard aligned with explicit protected TIME sessions. */
class TimedBlockProtectionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BlockingAccessibilityService.ACTION_REFRESH_BLOCKING &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                TimedBlockProtectionController.getInstance(context)
                    .reconcileFromDatabase()
            } catch (error: Exception) {
                FocusGuardLogger.logError(
                    "TimedBlockProtection",
                    "Falha ao reconciliar proteção após ${intent.action}",
                    error
                )
            } finally {
                pendingResult.finish()
            }
        }
    }
}
