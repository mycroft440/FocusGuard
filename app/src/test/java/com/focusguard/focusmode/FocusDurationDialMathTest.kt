package com.focusguard.focusmode

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FocusDurationDialMathTest {
    @Test
    fun `dial endpoints and sweep map to the expected range`() {
        assertThat(FocusDurationDialMath.minutesForAngle(135f)).isEqualTo(1)
        assertThat(FocusDurationDialMath.minutesForAngle(45f)).isEqualTo(480)
        assertThat(FocusDurationDialMath.minutesForAngle(270f)).isIn(240..242)
    }

    @Test
    fun `dead zone snaps to nearest endpoint instead of always eight hours`() {
        assertThat(FocusDurationDialMath.minutesForAngle(70f)).isEqualTo(480)
        assertThat(FocusDurationDialMath.minutesForAngle(120f)).isEqualTo(1)
    }

    @Test
    fun `hour display preserves minute precision`() {
        assertThat(FocusDurationDialMath.displayValue(40)).isEqualTo("40")
        assertThat(FocusDurationDialMath.displayValue(60)).isEqualTo("1:00")
        assertThat(FocusDurationDialMath.displayValue(65)).isEqualTo("1:05")
        assertThat(FocusDurationDialMath.displayValue(480)).isEqualTo("8:00")
    }
}
