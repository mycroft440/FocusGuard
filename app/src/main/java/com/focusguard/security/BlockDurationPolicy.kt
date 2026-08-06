package com.focusguard.security

import java.util.concurrent.TimeUnit

/**
 * Converts "30 dias" into the hours the session layer actually stores.
 *
 * Pure so the conversions and the edge cases are testable: a wrong multiplier
 * here arms an irrevocable fast for the wrong length, and the user has no way to
 * correct it afterwards.
 */
object BlockDurationPolicy {

    /** Months are rendered as 30 days — the app never promises calendar months. */
    private const val DAYS_PER_MONTH = 30

    /** Ceiling for a finite block, matching the 120-day cap used elsewhere. */
    const val MAX_DAYS = 120

    enum class Unit {
        HOURS,
        DAYS,
        MONTHS,

        /**
         * No end date. Stored as an open-ended session rather than a huge number
         * of days, so the UI can say "para sempre" instead of counting down from
         * an arbitrary figure.
         */
        FOREVER
    }

    /** Whether the amount field should be shown at all. */
    fun requiresAmount(unit: Unit): Boolean = unit != Unit.FOREVER

    sealed interface Duration {
        /** Finite block; [totalHours] is what the session layer stores. */
        data class Finite(val totalHours: Int) : Duration

        /** Open-ended: no deadline is written. */
        data object Forever : Duration
    }

    /**
     * @return null when the input cannot arm anything — a blank or zero amount on
     *   a unit that needs one. Callers should keep the confirm button disabled
     *   rather than guessing a default.
     */
    fun resolve(unit: Unit, amount: Int?): Duration? {
        if (unit == Unit.FOREVER) return Duration.Forever

        val value = amount ?: return null
        if (value <= 0) return null

        val hours = when (unit) {
            Unit.HOURS -> value.toLong()
            Unit.DAYS -> TimeUnit.DAYS.toHours(value.toLong())
            Unit.MONTHS -> TimeUnit.DAYS.toHours(value.toLong() * DAYS_PER_MONTH)
            Unit.FOREVER -> return Duration.Forever
        }

        val cappedHours = TimeUnit.DAYS.toHours(MAX_DAYS.toLong())
        return Duration.Finite(hours.coerceAtMost(cappedHours).toInt())
    }

    /** Largest amount that still makes sense for the unit, for input clamping. */
    fun maxAmountFor(unit: Unit): Int = when (unit) {
        Unit.HOURS -> MAX_DAYS * 24
        Unit.DAYS -> MAX_DAYS
        Unit.MONTHS -> MAX_DAYS / DAYS_PER_MONTH
        Unit.FOREVER -> 0
    }
}
