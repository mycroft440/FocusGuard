package com.focusguard.security

import java.util.concurrent.TimeUnit

/**
 * Turns a block deadline into the coarsest unit that is still honest.
 *
 * Kept separate from the UI so the rounding rules are testable: "faltam 2 dias"
 * when 1 day and 23 hours remain would be a lie the user could catch, and a
 * dopamine fast is exactly the feature where an inflated number destroys trust.
 * Everything here rounds *down*, so the real wait is never longer than shown.
 */
object BlockCountdownPolicy {

    sealed interface Remaining {
        /** One day or more left; [days] is floored. */
        data class Days(val days: Int) : Remaining

        /** Under a day, one hour or more; [hours] is floored. */
        data class Hours(val hours: Int) : Remaining

        /** Under an hour — deliberately not shown in minutes. */
        data object LessThanAnHour : Remaining

        /** Deadline already passed; the block is no longer holding. */
        data object Elapsed : Remaining
    }

    /**
     * @return null when there is no deadline at all — an open-ended block, which
     *   the caller should render as "until you end it" rather than a countdown.
     */
    fun remaining(
        unlockAtMillis: Long?,
        nowMillis: Long = System.currentTimeMillis()
    ): Remaining? {
        val deadline = unlockAtMillis ?: return null
        val leftMillis = deadline - nowMillis
        if (leftMillis <= 0L) return Remaining.Elapsed

        val days = TimeUnit.MILLISECONDS.toDays(leftMillis)
        if (days >= 1L) return Remaining.Days(days.toInt())

        val hours = TimeUnit.MILLISECONDS.toHours(leftMillis)
        if (hours >= 1L) return Remaining.Hours(hours.toInt())

        return Remaining.LessThanAnHour
    }

    /** True while the deadline still holds the block shut. */
    fun isStillBlocking(
        unlockAtMillis: Long?,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean = when (remaining(unlockAtMillis, nowMillis)) {
        null -> true // open-ended: holds until ended by hand
        Remaining.Elapsed -> false
        else -> true
    }
}
