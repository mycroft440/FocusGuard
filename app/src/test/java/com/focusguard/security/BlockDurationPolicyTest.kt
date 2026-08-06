package com.focusguard.security

import com.focusguard.security.BlockDurationPolicy.Duration
import com.focusguard.security.BlockDurationPolicy.Unit
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BlockDurationPolicyTest {

    @Test
    fun `hours pass through unchanged`() {
        assertThat(BlockDurationPolicy.resolve(Unit.HOURS, 6))
            .isEqualTo(Duration.Finite(6))
    }

    @Test
    fun `days become hours`() {
        assertThat(BlockDurationPolicy.resolve(Unit.DAYS, 30))
            .isEqualTo(Duration.Finite(30 * 24))
    }

    @Test
    fun `months are thirty days each`() {
        assertThat(BlockDurationPolicy.resolve(Unit.MONTHS, 2))
            .isEqualTo(Duration.Finite(60 * 24))
    }

    @Test
    fun `forever needs no amount`() {
        assertThat(BlockDurationPolicy.resolve(Unit.FOREVER, null))
            .isEqualTo(Duration.Forever)
        assertThat(BlockDurationPolicy.requiresAmount(Unit.FOREVER)).isFalse()
    }

    @Test
    fun `every finite unit needs an amount`() {
        listOf(Unit.HOURS, Unit.DAYS, Unit.MONTHS).forEach { unit ->
            assertThat(BlockDurationPolicy.requiresAmount(unit)).isTrue()
        }
    }

    @Test
    fun `a blank amount resolves to nothing`() {
        // The caller keeps the confirm button disabled instead of guessing a
        // default — guessing here arms an irrevocable block the user never chose.
        listOf(Unit.HOURS, Unit.DAYS, Unit.MONTHS).forEach { unit ->
            assertThat(BlockDurationPolicy.resolve(unit, null)).isNull()
        }
    }

    @Test
    fun `zero and negative amounts resolve to nothing`() {
        assertThat(BlockDurationPolicy.resolve(Unit.DAYS, 0)).isNull()
        assertThat(BlockDurationPolicy.resolve(Unit.DAYS, -5)).isNull()
    }

    @Test
    fun `finite durations are capped at the maximum`() {
        val capHours = BlockDurationPolicy.MAX_DAYS * 24

        assertThat(BlockDurationPolicy.resolve(Unit.DAYS, 9_999))
            .isEqualTo(Duration.Finite(capHours))
        assertThat(BlockDurationPolicy.resolve(Unit.MONTHS, 500))
            .isEqualTo(Duration.Finite(capHours))
        assertThat(BlockDurationPolicy.resolve(Unit.HOURS, 1_000_000))
            .isEqualTo(Duration.Finite(capHours))
    }

    @Test
    fun `forever is not capped`() {
        // The cap exists to keep a finite countdown sane, not to quietly turn an
        // open-ended block into a 120-day one.
        assertThat(BlockDurationPolicy.resolve(Unit.FOREVER, 9_999))
            .isEqualTo(Duration.Forever)
    }

    @Test
    fun `max amount matches the unit`() {
        assertThat(BlockDurationPolicy.maxAmountFor(Unit.DAYS))
            .isEqualTo(BlockDurationPolicy.MAX_DAYS)
        assertThat(BlockDurationPolicy.maxAmountFor(Unit.HOURS))
            .isEqualTo(BlockDurationPolicy.MAX_DAYS * 24)
        assertThat(BlockDurationPolicy.maxAmountFor(Unit.MONTHS)).isEqualTo(4)
    }

    @Test
    fun `an amount at the cap is not altered`() {
        assertThat(BlockDurationPolicy.resolve(Unit.DAYS, BlockDurationPolicy.MAX_DAYS))
            .isEqualTo(Duration.Finite(BlockDurationPolicy.MAX_DAYS * 24))
    }
}
