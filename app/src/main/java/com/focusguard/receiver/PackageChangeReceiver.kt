package com.focusguard.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.focusguard.accessibility.website.compatibility.BrowserCompatibilityStore
import com.focusguard.accessibility.website.compatibility.BrowserDetector
import com.focusguard.focusmode.FocusModeManager
import com.focusguard.focusmode.FocusModeStore
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

        BrowserDetector.invalidate(changedPackage)
        BrowserCompatibilityStore.invalidatePackageMetadata(changedPackage)

        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val appContext = context.applicationContext
                if (FocusModeStore.readSession(appContext) != null) {
                    FocusModeManager.getInstance(appContext).ensureEnforced()
                } else {
                    BlockingSessionManager.getInstance(appContext).checkAndEnforce()
                }
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
