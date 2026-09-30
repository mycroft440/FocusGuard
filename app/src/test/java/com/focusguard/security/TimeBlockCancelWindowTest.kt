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

    @Test
    fun `limit with the option is editable for 48 h, then locked until the rule ends`() {
        val lockAfter = MasterCredentialPolicy.limitLockAfter(start, cancelWindowEnabled = true)
        val ruleEnd = start + 30L * 24 * 3_600_000L
        fun gate(now: Long) = MasterCredentialPolicy.evaluateLimitMutation(
            lockMode = "BLOCK_UNTIL_TOMORROW:com.example.app",
            lockUntilTimestamp = ruleEnd,
            safetyModeEnabled = false,
            hasMasterCredential = false,
            masterCredentialVerified = false,
            nowMillis = now,
            lockAfter = lockAfter
        )

        assertThat(lockAfter).isEqualTo(until)
        assertThat(gate(start)).isEqualTo(MasterCredentialPolicy.MutationGate.ALLOWED)
        assertThat(gate(until)).isEqualTo(MasterCredentialPolicy.MutationGate.BLOCKED_BY_TIME_HARDENING)
        assertThat(gate(ruleEnd)).isEqualTo(MasterCredentialPolicy.MutationGate.ALLOWED)
    }

    @Test
    fun `limit without the option is locked from the start, old limits never lock`() {
        val ruleEnd = start + 3_600_000L
        assertThat(
            MasterCredentialPolicy.isLimitLockedAfterCancelWindow(
                lockAfter = MasterCredentialPolicy.limitLockAfter(start, cancelWindowEnabled = false),
                ruleEndMillis = ruleEnd,
                nowMillis = start
            )
        ).isTrue()
        assertThat(
            MasterCredentialPolicy.isLimitLockedAfterCancelWindow(
                lockAfter = null,
                ruleEndMillis = ruleEnd,
                nowMillis = start
            )
        ).isFalse()
    }
}
