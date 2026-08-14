package com.focusguard.pomodoro

object PomodoroCyclePolicy {
    /**
     * Retorna a próxima pausa depois de uma sessão de foco concluída.
     * null significa que a quantidade configurada de sessões foi atingida.
     */
    fun nextBreakAfterFocus(
        config: PomodoroPlanConfig,
        completedFocusSessions: Int
    ): PomodoroPhase? {
        val normalized = config.normalized()
        val completed = completedFocusSessions.coerceAtLeast(0)
        if (normalized.targetSessions > 0 && completed >= normalized.targetSessions) {
            return null
        }
        return if (completed > 0 && completed % normalized.longBreakEvery == 0) {
            PomodoroPhase.LONG_BREAK
        } else {
            PomodoroPhase.SHORT_BREAK
        }
    }

    fun durationMinutes(config: PomodoroPlanConfig, phase: PomodoroPhase): Int {
        val normalized = config.normalized()
        return when (phase) {
            PomodoroPhase.FOCUS -> normalized.focusMinutes
            PomodoroPhase.SHORT_BREAK -> normalized.shortBreakMinutes
            PomodoroPhase.LONG_BREAK -> normalized.longBreakMinutes
        }
    }

    fun targetLabel(config: PomodoroPlanConfig): String =
        if (config.targetSessions == 0) "Até eu parar" else config.targetSessions.toString()
}
