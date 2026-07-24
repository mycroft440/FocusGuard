package com.focusguard

import android.app.Application
import com.focusguard.admin.DeviceOwnerManager
import com.focusguard.utils.AccessibilityStateMonitor
import com.focusguard.utils.FocusGuardLogger
import dagger.hilt.android.HiltAndroidApp

/**
 * Application class para o FocusGuard.
 *
 * `@HiltAndroidApp` habilita injeção de dependência em toda a árvore de componentes.
 */
@HiltAndroidApp
class FocusGuardApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        FocusGuardLogger.init(this)

        // Reaplica as políticas oficiais do Android sempre que o processo inicia.
        // Se uma janela de manutenção válida estiver ativa, o manager preserva apenas
        // as liberações temporárias e mantém as proteções críticas.
        DeviceOwnerManager.getInstance(this).applyNuclearShield()

        // Inicia monitor de desativação do Accessibility Service.
        AccessibilityStateMonitor.start(this)
    }
}
