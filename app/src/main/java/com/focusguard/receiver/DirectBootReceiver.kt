package com.focusguard.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.focusguard.admin.DeviceOwnerManager
import com.focusguard.focusmode.FocusModeKioskController
import com.focusguard.utils.FocusGuardLogger
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** Restores only device-protected policy before credential storage is available. */
@AndroidEntryPoint
class DirectBootReceiver : BroadcastReceiver() {
    @Inject lateinit var deviceOwnerManager: DeviceOwnerManager
    @Inject lateinit var kioskController: FocusModeKioskController

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return

        val storageContext = runCatching {
            context.createDeviceProtectedStorageContext()
        }.getOrDefault(context)
        FocusGuardLogger.init(storageContext)

        deviceOwnerManager.applyDirectBootShield()
        deviceOwnerManager.applyFocusModeAtDirectBoot()
        kioskController.reconcileSystemRestrictions()
        FocusGuardLogger.log(
            "DirectBootReceiver",
            "Proteções nativas restauradas antes do primeiro desbloqueio"
        )
    }
}
