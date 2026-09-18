package com.focusguard.ui.compose.screens

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BlockTypeHomeOptionsTest {
    @Test
    fun homeIncludesDailyPeriodBlockAsFourthProtectionChoice() {
        assertThat(BlockTypeUi.entries).containsExactly(
            BlockTypeUi.PASSWORD,
            BlockTypeUi.DAILY_LIMIT,
            BlockTypeUi.DAILY_PERIODS,
            BlockTypeUi.DOPAMINE_FAST
        ).inOrder()
    }

    @Test
    fun timeBlockModeDefaultsToContinuousForLegacyCallers() {
        assertThat(TimeBlockConfigMode.fromSerialized(null))
            .isEqualTo(TimeBlockConfigMode.CONTINUOUS)
        assertThat(TimeBlockConfigMode.fromSerialized("DAILY_PERIODS"))
            .isEqualTo(TimeBlockConfigMode.DAILY_PERIODS)
    }
}
