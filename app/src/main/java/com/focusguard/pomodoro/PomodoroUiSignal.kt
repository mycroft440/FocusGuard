package com.focusguard.pomodoro

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Permite que o botão do cabeçalho abra a configuração da aba Pomodoro. */
object PomodoroUiSignal {
    private val _configRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val configRequests = _configRequests.asSharedFlow()

    fun requestConfig() {
        _configRequests.tryEmit(Unit)
    }
}
