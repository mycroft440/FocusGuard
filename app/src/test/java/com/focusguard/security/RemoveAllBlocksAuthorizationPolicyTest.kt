package com.focusguard.security

import com.focusguard.security.RemoveAllBlocksAuthorizationPolicy.Gate
import com.focusguard.security.RemoveAllBlocksAuthorizationPolicy.Summary
import org.junit.Assert.assertEquals
import org.junit.Test

class RemoveAllBlocksAuthorizationPolicyTest {

    @Test
    fun `reports nothing to remove when no protection is active`() {
        assertEquals(Gate.NOTHING_TO_REMOVE, RemoveAllBlocksAuthorizationPolicy.evaluate(Summary()))
    }

    @Test
    fun `password blocks alone only need confirmation`() {
        assertEquals(
            Gate.CONFIRM_ONLY,
            RemoveAllBlocksAuthorizationPolicy.evaluate(Summary(passwordBlocks = 2))
        )
    }

    @Test
    fun `any other block requires the master credential`() {
        listOf(
            Summary(dailyLimits = 1),
            Summary(scheduledPeriods = 1),
            Summary(timeBlocks = 3),
            Summary(adultFilterActive = true),
            Summary(focusModeActive = true),
            Summary(passwordBlocks = 2, timeBlocks = 3)
        ).forEach { summary ->
            assertEquals(
                summary.toString(),
                Gate.REQUIRE_MASTER_CREDENTIAL,
                RemoveAllBlocksAuthorizationPolicy.evaluate(summary)
            )
        }
    }
}
