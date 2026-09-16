package com.focusguard.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.focusguard.admin.DeviceOwnerManager
import com.focusguard.security.DeviceOwnerMaintenanceGate
import com.focusguard.utils.FocusGuardLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class DeviceOwnerMaintenanceExpiryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_EXPIRE_MAINTENANCE) return

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                DeviceOwnerMaintenanceGate.revoke(context)
                DeviceOwnerManager.getInstance(context.applicationContext).applyNuclearShield()
            } catch (error: Exception) {
                FocusGuardLogger.logError(
                    "DeviceOwnerMaintenance",
                    "Falha ao reaplicar políticas após expirar manutenção",
                    error
                )
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_EXPIRE_MAINTENANCE =
            "com.focusguard.action.EXPIRE_DEVICE_OWNER_MAINTENANCE"
    }

}
