package com.focusguard.security

import com.focusguard.manager.BlockingSessionManager.BlockOverview
import com.focusguard.ui.compose.screens.BlockTypeUi
import com.focusguard.ui.compose.screens.canCancelTimeBlock
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Bloqueio por tempo com a opção de 48 h: cancelável só dentro do prazo. */
class TimeBlockCancelWindowTest {
    private val start = 1_000_000L
    private val until = start + MasterCredentialPolicy.CANCEL_WINDOW_HOURS * 3_600_000L

    @Test
    fun `block can be cancelled only before the window ends`() {
        assertThat(MasterCredentialPolicy.isWithinCancelWindow(until, nowMillis = start)).isTrue()
        assertThat(MasterCredentialPolicy.isWithinCancelWindow(until, nowMillis = until - 1)).isTrue()
        assertThat(MasterCredentialPolicy.isWithinCancelWindow(until, nowMillis = until)).isFalse()
    }

    @Test
    fun `blocks created without the option are irreversible from the start`() {
        assertThat(MasterCredentialPolicy.isWithinCancelWindow(null, nowMillis = start)).isFalse()
    }

    @Test
    fun `only time blocks offer the cancel button`() {
        val entry = BlockOverview.Entry(
            identifier = "com.example.app",
            isWebsite = false,
            sessionId = 7,
            cancelableUntilMillis = until
        )

        assertThat(canCancelTimeBlock(BlockTypeUi.DOPAMINE_FAST, entry, start)).isTrue()
        assertThat(canCancelTimeBlock(BlockTypeUi.DAILY_PERIODS, entry, start)).isTrue()
        assertThat(canCancelTimeBlock(BlockTypeUi.PASSWORD, entry, start)).isFalse()
        assertThat(canCancelTimeBlock(BlockTypeUi.DAILY_LIMIT, entry, start)).isFalse()
        assertThat(canCancelTimeBlock(BlockTypeUi.DOPAMINE_FAST, entry, until)).isFalse()
        assertThat(
            canCancelTimeBlock(BlockTypeUi.DOPAMINE_FAST, entry.copy(sessionId = null), start)
        ).isFalse()
    }
}
