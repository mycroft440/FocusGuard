package com.focusguard.security

/**
 * Authorization policy for Settings > Remove all blocks.
 *
 * The dialog always shows what is about to be removed and asks for confirmation.
 * PASSWORD blocks are already guarded by their own credential, so when they are
 * the only thing active the confirmation is enough. Any other protection (daily
 * limit, scheduled period, time block without password, adult filter or Focus
 * Mode) additionally requires the master credential.
 */
object RemoveAllBlocksAuthorizationPolicy {
    enum class Gate {
        NOTHING_TO_REMOVE,
        CONFIRM_ONLY,
        REQUIRE_MASTER_CREDENTIAL
    }

    /** What is currently active, grouped as the Home screen shows it. */
    data class Summary(
        val passwordBlocks: Int = 0,
        val dailyLimits: Int = 0,
        val scheduledPeriods: Int = 0,
        val timeBlocks: Int = 0,
        val adultFilterActive: Boolean = false,
        val focusModeActive: Boolean = false
    ) {
        val hasNonPasswordProtection: Boolean
            get() = dailyLimits > 0 ||
                scheduledPeriods > 0 ||
                timeBlocks > 0 ||
                adultFilterActive ||
                focusModeActive

        val isEmpty: Boolean
            get() = passwordBlocks == 0 && !hasNonPasswordProtection
    }

    fun evaluate(summary: Summary): Gate = when {
        summary.isEmpty -> Gate.NOTHING_TO_REMOVE
        summary.hasNonPasswordProtection -> Gate.REQUIRE_MASTER_CREDENTIAL
        else -> Gate.CONFIRM_ONLY
    }
}
