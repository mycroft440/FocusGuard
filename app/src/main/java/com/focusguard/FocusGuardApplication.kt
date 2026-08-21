package com.focusguard

import android.app.Application
import android.os.UserManager
import com.focusguard.admin.DeviceOwnerManager
import com.focusguard.focusmode.FocusModeManager
import com.focusguard.state.FocusModeStore
import com.focusguard.utils.AccessibilityStateMonitor
import com.focusguard.utils.FocusGuardLogger
import com.focusguard.utils.UsageAccessStateMonitor
import dagger.hilt.android.HiltAndroidApp
import dagger.Lazy
import javax.inject.Inject
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
    @Inject lateinit var deviceOwnerManager: DeviceOwnerManager
    @Inject lateinit var focusModeManager: Lazy<FocusModeManager>
    @Inject lateinit var accessibilityStateMonitor: Lazy<AccessibilityStateMonitor>
    @Inject lateinit var usageAccessStateMonitor: Lazy<UsageAccessStateMonitor>

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

        if (userUnlocked) {
            // Reaplica as políticas oficiais e inicia dependências que usam Room/Keystore.
            deviceOwnerManager.applyNuclearShield()
            accessibilityStateMonitor.get().start()
            usageAccessStateMonitor.get().start()
            // Instanciar o manager também instancia dependências protegidas pelo
            // AndroidKeyStore. Sem sessão persistida não existe nada a restaurar,
            // então evitamos esse custo no boot normal e em ambientes de teste
            // que corretamente não oferecem o AndroidKeyStore real.
            if (FocusModeStore.readSession(this) != null) {
                applicationScope.launch {
                    focusModeManager.get().ensureEnforced()
                }
            }
        } else {
            // Antes do primeiro desbloqueio, usa somente DPM + Device Protected Storage.
            deviceOwnerManager.applyDirectBootShield()
            deviceOwnerManager.applyFocusModeAtDirectBoot()
        }
    }
}
