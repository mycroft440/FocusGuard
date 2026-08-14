package com.focusguard.pomodoro

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PomodoroCyclePolicyTest {

    @Test
    fun `infinite plan never stops automatically`() {
        val config = PomodoroPlanConfig(
            targetSessions = 0,
            longBreakEvery = 4
        )

        assertThat(PomodoroCyclePolicy.nextBreakAfterFocus(config, 1))
            .isEqualTo(PomodoroPhase.SHORT_BREAK)
        assertThat(PomodoroCyclePolicy.nextBreakAfterFocus(config, 4))
            .isEqualTo(PomodoroPhase.LONG_BREAK)
        assertThat(PomodoroCyclePolicy.nextBreakAfterFocus(config, 100))
            .isEqualTo(PomodoroPhase.LONG_BREAK)
    }

    @Test
    fun `finite plan ends exactly after configured focus sessions`() {
        val config = PomodoroPlanConfig(
            targetSessions = 3,
            longBreakEvery = 4
        )

        assertThat(PomodoroCyclePolicy.nextBreakAfterFocus(config, 1))
            .isEqualTo(PomodoroPhase.SHORT_BREAK)
        assertThat(PomodoroCyclePolicy.nextBreakAfterFocus(config, 2))
            .isEqualTo(PomodoroPhase.SHORT_BREAK)
        assertThat(PomodoroCyclePolicy.nextBreakAfterFocus(config, 3)).isNull()
    }

    @Test
    fun `long break occurs on configured cadence`() {
        val config = PomodoroPlanConfig(
            targetSessions = 10,
            longBreakEvery = 3
        )

        assertThat(PomodoroCyclePolicy.nextBreakAfterFocus(config, 2))
            .isEqualTo(PomodoroPhase.SHORT_BREAK)
        assertThat(PomodoroCyclePolicy.nextBreakAfterFocus(config, 3))
            .isEqualTo(PomodoroPhase.LONG_BREAK)
        assertThat(PomodoroCyclePolicy.nextBreakAfterFocus(config, 6))
            .isEqualTo(PomodoroPhase.LONG_BREAK)
    }

    @Test
    fun `phase duration uses configured values`() {
        val config = PomodoroPlanConfig(
            focusMinutes = 40,
            shortBreakMinutes = 7,
            longBreakMinutes = 25
        )

        assertThat(PomodoroCyclePolicy.durationMinutes(config, PomodoroPhase.FOCUS))
            .isEqualTo(40)
        assertThat(PomodoroCyclePolicy.durationMinutes(config, PomodoroPhase.SHORT_BREAK))
            .isEqualTo(7)
        assertThat(PomodoroCyclePolicy.durationMinutes(config, PomodoroPhase.LONG_BREAK))
            .isEqualTo(25)
    }

    @Test
    fun `configuration clamps widget and alarm limits`() {
        val normalized = PomodoroPlanConfig(
            focusMinutes = 0,
            shortBreakMinutes = 999,
            longBreakMinutes = 9999,
            longBreakEvery = 0,
            targetSessions = 999,
            alarmDurationSeconds = 0,
            soundIndex = 99
        ).normalized()

        assertThat(normalized.focusMinutes).isEqualTo(1)
        assertThat(normalized.shortBreakMinutes).isEqualTo(120)
        assertThat(normalized.longBreakMinutes).isEqualTo(720)
        assertThat(normalized.longBreakEvery).isEqualTo(1)
        assertThat(normalized.targetSessions).isEqualTo(100)
        assertThat(normalized.alarmDurationSeconds).isEqualTo(1)
        assertThat(normalized.soundIndex).isEqualTo(9)
    }
}
