package com.focusguard.di

import com.focusguard.domain.port.BlockingEnforcementPort
import com.focusguard.domain.port.BlockingRuntimePort
import com.focusguard.domain.port.FocusModeRuntimePort
import com.focusguard.domain.port.FocusModeSystemPort
import com.focusguard.domain.port.PomodoroRuntimePort
import com.focusguard.focusmode.FocusModeKioskController
import com.focusguard.manager.BlockingSessionManager
import com.focusguard.platform.AndroidBlockingRuntimeAdapter
import com.focusguard.platform.AndroidFocusModeRuntimeAdapter
import com.focusguard.platform.AndroidPomodoroRuntimeAdapter
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class RuntimeBindingsModule {
    @Binds
    abstract fun bindBlockingEnforcement(
        implementation: BlockingSessionManager
    ): BlockingEnforcementPort

    @Binds
    abstract fun bindBlockingRuntime(
        implementation: AndroidBlockingRuntimeAdapter
    ): BlockingRuntimePort

    @Binds
    abstract fun bindPomodoroRuntime(
        implementation: AndroidPomodoroRuntimeAdapter
    ): PomodoroRuntimePort

    @Binds
    abstract fun bindFocusModeRuntime(
        implementation: AndroidFocusModeRuntimeAdapter
    ): FocusModeRuntimePort

    @Binds
    abstract fun bindFocusModeSystem(
        implementation: FocusModeKioskController
    ): FocusModeSystemPort
}
