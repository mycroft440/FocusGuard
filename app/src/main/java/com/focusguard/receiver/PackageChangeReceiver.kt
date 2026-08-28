package com.focusguard.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.focusguard.manager.BlockingSessionManager
import com.focusguard.utils.FocusGuardLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PackageChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_PACKAGE_ADDED &&
            intent.action != Intent.ACTION_PACKAGE_REPLACED &&
            intent.action != Intent.ACTION_PACKAGE_CHANGED
        ) return

        val changedPackage = intent.data?.schemeSpecificPart.orEmpty()
        if (changedPackage.isBlank() || changedPackage == context.packageName) return

        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                BlockingSessionManager.getInstance(context.applicationContext).checkAndEnforce()
            } catch (error: Exception) {
                FocusGuardLogger.logError(
                    "PackageChangeReceiver",
                    "Falha ao reaplicar bloqueio após mudança de pacote: $changedPackage",
                    error
                )
            } finally {
                pending.finish()
            }
        }
    }
}
