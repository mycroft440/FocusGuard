package com.focusguard.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AppUsageLimitActivationUsageTest {

    @Test
    fun `usage before activation is not charged to a new daily limit`() {
        val dayStart = 1_000_000L
        val activatedAt = dayStart + 10 * 60_000L
        val baseline = 8 * 60_000L
        val currentDayUsage = 10 * 60_000L

        assertThat(
            AppUsageLimitActivationUsage.usageSinceActivationMillis(
                currentDayUsageMillis = currentDayUsage,
                activationBaselineMillis = baseline,
                activatedAtMillis = activatedAt,
                dayStartMillis = dayStart
            )
        ).isEqualTo(2 * 60_000L)
    }

    @Test
    fun `limit becomes an ordinary midnight based daily allowance on later days`() {
        val dayStart = 10_000_000L

        assertThat(
            AppUsageLimitActivationUsage.usageSinceActivationMillis(
                currentDayUsageMillis = 3 * 60_000L,
                activationBaselineMillis = 100 * 60_000L,
                activatedAtMillis = dayStart - 1L,
                dayStartMillis = dayStart
            )
        ).isEqualTo(3 * 60_000L)
    }

    @Test
    fun `counter never becomes negative if Android usage stats move backwards`() {
        val dayStart = 1_000_000L

        assertThat(
            AppUsageLimitActivationUsage.usageSinceActivationMillis(
                currentDayUsageMillis = 2 * 60_000L,
                activationBaselineMillis = 5 * 60_000L,
                activatedAtMillis = dayStart + 1L,
                dayStartMillis = dayStart
            )
        ).isEqualTo(0L)
    }

    @Test
    fun `three minute allowance is reached only after three post activation minutes`() {
        val baseline = 25 * 60_000L
        val dayStart = 1_000_000L
        val activatedAt = dayStart + 30 * 60_000L
        val beforeThreeMinutes = AppUsageLimitActivationUsage.usageSinceActivationMillis(
            currentDayUsageMillis = baseline + 179_999L,
            activationBaselineMillis = baseline,
            activatedAtMillis = activatedAt,
            dayStartMillis = dayStart
        )
        val atThreeMinutes = AppUsageLimitActivationUsage.usageSinceActivationMillis(
            currentDayUsageMillis = baseline + 180_000L,
            activationBaselineMillis = baseline,
            activatedAtMillis = activatedAt,
            dayStartMillis = dayStart
        )

        assertThat(UsageLimitForegroundPolicy.usedMinutes(beforeThreeMinutes)).isEqualTo(2L)
        assertThat(UsageLimitForegroundPolicy.usedMinutes(atThreeMinutes)).isEqualTo(3L)
    }

    @Test
    fun `event fallback cuts the baseline exactly at activation`() {
        val start = 1_000L
        val activation = 11_000L
        val transitions = listOf(
            AppUsageLimitActivationUsage.ForegroundTransition(2_000L, true, 7),
            AppUsageLimitActivationUsage.ForegroundTransition(6_000L, false, 7),
            AppUsageLimitActivationUsage.ForegroundTransition(8_000L, true, 8)
        )

        assertThat(
            AppUsageLimitActivationUsage.foregroundUsageMillis(
                transitions = transitions,
                startMillis = start,
                endMillis = activation
            )
        ).isEqualTo(7_000L)
    }

    @Test
    fun `event fallback does not double count overlapping activities`() {
        val transitions = listOf(
            AppUsageLimitActivationUsage.ForegroundTransition(1_000L, true, 1),
            AppUsageLimitActivationUsage.ForegroundTransition(2_000L, true, 2),
            AppUsageLimitActivationUsage.ForegroundTransition(3_000L, false, 1),
            AppUsageLimitActivationUsage.ForegroundTransition(5_000L, false, 2)
        )

        assertThat(
            AppUsageLimitActivationUsage.foregroundUsageMillis(
                transitions = transitions,
                startMillis = 0L,
                endMillis = 6_000L
            )
        ).isEqualTo(4_000L)
    }
}
