package com.focusguard

import android.app.Application
import com.focusguard.utils.FocusGuardLogger
import dagger.hilt.android.HiltAndroidApp

/**
 * Application class para o FocusGuard.
 *
 * `@HiltAndroidApp` habilita injeção de dependência em toda a árvore de componentes
 * do app (Activities, Fragments, Services, ViewModels). Antes da Fase 3, os managers
 * (PomodoroManager, AuthManager, BlockingSessionManager, DeviceOwnerManager) e
 * AppDatabase eram instanciados manualmente em cada tela — agora são fornecidos
 * pelo Hilt a partir dos modules em com.focusguard.di.
 */
@HiltAndroidApp
class FocusGuardApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        FocusGuardLogger.init(this)
    }
}
