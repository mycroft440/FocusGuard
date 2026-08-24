package com.focusguard.security

import android.os.SystemClock

/**
 * Process-local handoff while MasterRemovalActivity clears the Settings task.
 * The accessibility curtain is already touch-blocking; this marker prevents the
 * service from mistaking its own ACTION_SETTINGS reset for a user bypass attempt.
 */
object ProtectedSettingsResetWindow {
    internal const val DURATION_MILLIS = 3_000L

    @Volatile private var generation = 0L
    @Volatile private var deadlineElapsed = 0L

    fun open(curtainGeneration: Long) {
        if (curtainGeneration <= 0L) return
        generation = curtainGeneration
        deadlineElapsed = SystemClock.elapsedRealtime() + DURATION_MILLIS
    }

    fun isActive(
        curtainGeneration: Long,
        nowElapsed: Long = SystemClock.elapsedRealtime()
    ): Boolean {
        val active = evaluate(
            expectedGeneration = curtainGeneration,
            storedGeneration = generation,
            deadlineElapsed = deadlineElapsed,
            nowElapsed = nowElapsed
        )
        if (!active && nowElapsed >= deadlineElapsed) close(generation)
        return active
    }

    fun close(curtainGeneration: Long) {
        if (curtainGeneration <= 0L || generation != curtainGeneration) return
        generation = 0L
        deadlineElapsed = 0L
    }

    internal fun evaluate(
        expectedGeneration: Long,
        storedGeneration: Long,
        deadlineElapsed: Long,
        nowElapsed: Long
    ): Boolean = expectedGeneration > 0L &&
        expectedGeneration == storedGeneration &&
        deadlineElapsed > nowElapsed
}
