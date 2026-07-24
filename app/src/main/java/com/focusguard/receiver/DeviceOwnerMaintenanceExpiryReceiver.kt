package com.focusguard.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.focusguard.admin.DeviceOwnerManager
import com.focusguard.security.DeviceOwnerMaintenanceGate
import com.focusguard.utils.FocusGuardLogger

/** Reapplies Device Owner protection when the temporary maintenance window expires. */
class DeviceOwnerMaintenanceExpiryReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_EXPIRE_MAINTENANCE) return

        runCatching {
            DeviceOwnerMaintenanceGate.revoke(context)
            DeviceOwnerManager.getInstance(context).applyNuclearShield()
        }.onSuccess {
            FocusGuardLogger.log("DeviceOwner", "Janela de manutenção expirada; proteção reaplicada")
        }.onFailure { error ->
            FocusGuardLogger.logError(
                "DeviceOwner",
                "Falha ao reaplicar proteção após manutenção",
                error
            )
        }
    }

    companion object {
        const val ACTION_EXPIRE_MAINTENANCE =
            "com.focusguard.action.EXPIRE_DEVICE_OWNER_MAINTENANCE"
    }
}
