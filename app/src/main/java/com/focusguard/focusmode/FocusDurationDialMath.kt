package com.focusguard.focusmode

import kotlin.math.roundToInt

/** Pure geometry/formatting for the 270-degree Focus Mode duration dial. */
internal object FocusDurationDialMath {
    const val MINUTES_MIN = 1
    const val MINUTES_MAX = 480
    private const val START_ANGLE = 135f
    private const val END_ANGLE = 45f
    private const val GAP_MIDPOINT = 90f
    private const val SWEEP = 270f

    fun minutesForAngle(rawDegrees: Float): Int {
        val degrees = ((rawDegrees % 360f) + 360f) % 360f
        val relative = when {
            degrees >= START_ANGLE -> degrees - START_ANGLE
            degrees <= END_ANGLE -> degrees + (360f - START_ANGLE)
            // Pointer is in the inactive 90-degree gap. Snap to the nearest
            // endpoint instead of coercing the entire gap to eight hours.
            degrees <= GAP_MIDPOINT -> SWEEP
            else -> 0f
        }
        return (
            MINUTES_MIN + (relative / SWEEP) * (MINUTES_MAX - MINUTES_MIN)
        ).roundToInt().coerceIn(MINUTES_MIN, MINUTES_MAX)
    }

    fun displayValue(minutes: Int): String {
        val safe = minutes.coerceIn(MINUTES_MIN, MINUTES_MAX)
        if (safe < 60) return safe.toString()
        return "%d:%02d".format(safe / 60, safe % 60)
    }
}
