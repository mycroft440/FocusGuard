package com.focusguard

import android.app.Application
import android.os.UserManager
import com.focusguard.admin.DeviceOwnerManager
import com.focusguard.focusmode.FocusModeManager
import com.focusguard.focusmode.FocusModeStore
import com.focusguard.security.DeviceAdminActivationWindow
import com.focusguard.security.DeviceOwnerMaintenanceGate
import com.focusguard.utils.AccessibilityStateMonitor
import com.focusguard.utils.FocusGuardLogger
import com.focusguard.utils.UsageAccessStateMonitor
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application class para o FocusGuard.
 *
 * `@HiltAndroidApp` habilita injeção de dependência em toda a árvore de componentes.
 */
@HiltAndroidApp
class FocusGuardApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        val userUnlocked = runCatching {
            getSystemService(UserManager::class.java).isUserUnlocked
        }.getOrDefault(true)
        val startupContext = if (userUnlocked) {
            this
        } else {
            runCatching { createDeviceProtectedStorageContext() }.getOrDefault(this)
        }
        FocusGuardLogger.init(startupContext)

        // Warm externally-backed authorization state before Accessibility can receive
        // its first event. Subsequent maintenance/admin decisions are memory-only in
        // the overwhelmingly common path instead of consulting Settings/Preferences.
        DeviceOwnerMaintenanceGate.preload(startupContext)
        DeviceAdminActivationWindow.preload(startupContext)

        val deviceOwnerManager = DeviceOwnerManager.getInstance(this)
        if (userUnlocked) {
            // Reaplica as políticas oficiais e inicia dependências que usam Room/Keystore.
            deviceOwnerManager.applyNuclearShield()
            AccessibilityStateMonitor.start(this)
            UsageAccessStateMonitor.start(this)
            // Instanciar o manager também instancia dependências protegidas pelo
            // AndroidKeyStore. Sem sessão persistida não existe nada a restaurar,
            // então evitamos esse custo no boot normal e em ambientes de teste
            // que corretamente não oferecem o AndroidKeyStore real.
            if (FocusModeStore.readSession(this) != null) {
                applicationScope.launch {
                    FocusModeManager.getInstance(this@FocusGuardApplication).ensureEnforced()
                }
            }
        } else {
            // Antes do primeiro desbloqueio, usa somente DPM + Device Protected Storage.
            deviceOwnerManager.applyDirectBootShield()
            deviceOwnerManager.applyFocusModeAtDirectBoot()
        }
    }
}
