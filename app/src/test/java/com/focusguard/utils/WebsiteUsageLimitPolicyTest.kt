package com.focusguard.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WebsiteUsageLimitPolicyTest {

    @Test
    fun `blocks when the daily threshold is reached`() {
        assertThat(
            WebsiteUsageLimitPolicy.shouldBlock(
                usedMillis = 30 * 60_000L,
                dailyLimitMinutes = 30,
                lockMode = "NONE",
                lockUntilTimestamp = null,
                nowMillis = 1_000L
            )
        ).isTrue()
    }

    @Test
    fun `does not block before the daily threshold`() {
        assertThat(
            WebsiteUsageLimitPolicy.shouldBlock(
                usedMillis = 29 * 60_000L,
                dailyLimitMinutes = 30,
                lockMode = "PASSWORD",
                lockUntilTimestamp = null,
                nowMillis = 1_000L
            )
        ).isFalse()
    }

    @Test
    fun `warning mode never becomes a hard block`() {
        assertThat(
            WebsiteUsageLimitPolicy.shouldBlock(
                usedMillis = 60 * 60_000L,
                dailyLimitMinutes = 30,
                lockMode = "warning",
                lockUntilTimestamp = null,
                nowMillis = 1_000L
            )
        ).isFalse()
    }

    @Test
    fun `time mode stops blocking after its lock expires`() {
        assertThat(
            WebsiteUsageLimitPolicy.shouldBlock(
                usedMillis = 60 * 60_000L,
                dailyLimitMinutes = 30,
                lockMode = "TIME",
                lockUntilTimestamp = 999L,
                nowMillis = 1_000L
            )
        ).isFalse()
        assertThat(
            WebsiteUsageLimitPolicy.shouldBlock(
                usedMillis = 60 * 60_000L,
                dailyLimitMinutes = 30,
                lockMode = "TIME",
                lockUntilTimestamp = 1_001L,
                nowMillis = 1_000L
            )
        ).isTrue()
    }

    @Test
    fun `time mode without an expiration does not create a permanent lock`() {
        assertThat(
            WebsiteUsageLimitPolicy.shouldBlock(
                usedMillis = 60 * 60_000L,
                dailyLimitMinutes = 30,
                lockMode = "TIME",
                lockUntilTimestamp = null,
                nowMillis = 1_000L
            )
        ).isFalse()
    }

    @Test
    fun `password mode remains active without an expiration`() {
        assertThat(
            WebsiteUsageLimitPolicy.isBlockingModeActive(
                lockMode = "password",
                lockUntilTimestamp = null,
                nowMillis = 1_000L
            )
        ).isTrue()
    }
}
