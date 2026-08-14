package com.focusguard.pomodoro

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PomodoroCyclePolicyTest {

    @Test
    fun `finite plan ends immediately after its last focus session`() {
        val config = PomodoroPlanConfig(targetSessions = 4, longBreakEvery = 4)

        assertThat(
            PomodoroCyclePolicy.nextBreakAfterFocus(config, completedFocusSessions = 4)
        ).isNull()
    }

    @Test
    fun `long break is selected after configured amount of completed sessions`() {
        val config = PomodoroPlanConfig(targetSessions = 8, longBreakEvery = 4)

        assertThat(
            PomodoroCyclePolicy.nextBreakAfterFocus(config, completedFocusSessions = 4)
        ).isEqualTo(PomodoroPhase.LONG_BREAK)
    }

    @Test
    fun `normal completed session receives short break`() {
        val config = PomodoroPlanConfig(targetSessions = 8, longBreakEvery = 4)

        assertThat(
            PomodoroCyclePolicy.nextBreakAfterFocus(config, completedFocusSessions = 3)
        ).isEqualTo(PomodoroPhase.SHORT_BREAK)
    }

    @Test
    fun `unbounded plan continues past one hundred completed sessions`() {
        val config = PomodoroPlanConfig(targetSessions = 0, longBreakEvery = 4)

        assertThat(
            PomodoroCyclePolicy.nextBreakAfterFocus(config, completedFocusSessions = 101)
        ).isEqualTo(PomodoroPhase.SHORT_BREAK)
    }

    @Test
    fun `duration follows each configured interval`() {
        val config = PomodoroPlanConfig(
            focusMinutes = 50,
            shortBreakMinutes = 10,
            longBreakMinutes = 45
        )

        assertThat(PomodoroCyclePolicy.durationMinutes(config, PomodoroPhase.FOCUS)).isEqualTo(50)
        assertThat(PomodoroCyclePolicy.durationMinutes(config, PomodoroPhase.SHORT_BREAK)).isEqualTo(10)
        assertThat(PomodoroCyclePolicy.durationMinutes(config, PomodoroPhase.LONG_BREAK)).isEqualTo(45)
    }
}
