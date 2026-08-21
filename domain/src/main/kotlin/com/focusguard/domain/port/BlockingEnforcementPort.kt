package com.focusguard.domain.port

/** Narrow command surface shared by timers and Focus Mode. */
interface BlockingEnforcementPort {
    fun startPomodoroSession(durationMs: Long, isBlockingEnabled: Boolean = true)
    suspend fun checkAndEnforceStrict()
}

