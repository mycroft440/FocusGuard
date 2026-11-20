package com.focusguard.security

import com.focusguard.manager.BlockingSessionManager.BlockOverview
import com.focusguard.ui.compose.screens.BlockTypeUi
import com.focusguard.ui.compose.screens.canCancelTimeBlock
import com.focusguard.ui.compose.screens.canRemoveDailyLimit
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

    @Test
    fun `pause limit can always be changed, except while the pause is running`() {
        val ruleEnd = start + 30L * 24 * 3_600_000L
        // Mesmo criado sem as 48 h (travado desde a criação no outro modo).
        val lockAfter = MasterCredentialPolicy.limitLockAfter(start, cancelWindowEnabled = false)
        fun gate(lockMode: String, now: Long, paused: Boolean) =
            MasterCredentialPolicy.evaluateLimitMutation(
                lockMode = lockMode,
                lockUntilTimestamp = ruleEnd,
                safetyModeEnabled = false,
                hasMasterCredential = false,
                masterCredentialVerified = false,
                nowMillis = now,
                lockAfter = lockAfter,
                pauseActive = paused
            )
        val pause = "PAUSE_30:com.example.app"
        val tomorrow = "BLOCK_UNTIL_TOMORROW:com.example.app"

        assertThat(gate(pause, until + 1, paused = false))
            .isEqualTo(MasterCredentialPolicy.MutationGate.ALLOWED)
        assertThat(gate(pause, start, paused = true))
            .isEqualTo(MasterCredentialPolicy.MutationGate.BLOCKED_BY_ACTIVE_PAUSE)
        assertThat(gate(tomorrow, until + 1, paused = false))
            .isEqualTo(MasterCredentialPolicy.MutationGate.BLOCKED_BY_TIME_HARDENING)
        // Sempre removível, a pausa nunca vira compromisso que impede desinstalar.
        assertThat(
            MasterCredentialPolicy.isLimitCommitmentActive(
                isEnabled = true,
                lockMode = pause,
                lockUntilTimestamp = ruleEnd,
                lockAfter = lockAfter,
                nowMillis = until + 1
            )
        ).isFalse()
    }

    @Test
    fun `daily limit list offers removal within 48 h, or for pause limits outside the pause`() {
        val untilTomorrow = BlockOverview.Entry(
            identifier = "com.example.app",
            isWebsite = false,
            dailyLimitMinutes = 30,
            cancelableUntilMillis = until
        )
        assertThat(canRemoveDailyLimit(BlockTypeUi.DAILY_LIMIT, untilTomorrow, start)).isTrue()
        assertThat(canRemoveDailyLimit(BlockTypeUi.DAILY_LIMIT, untilTomorrow, until)).isFalse()
        assertThat(canRemoveDailyLimit(BlockTypeUi.DOPAMINE_FAST, untilTomorrow, start)).isFalse()
        assertThat(
            canRemoveDailyLimit(
                BlockTypeUi.DAILY_LIMIT,
                untilTomorrow.copy(cancelableUntilMillis = null),
                start
            )
        ).isFalse()

        val pauseEnd = start + 30L * 60_000L
        val pause = BlockOverview.Entry(
            identifier = "com.example.app",
            isWebsite = false,
            dailyLimitMinutes = 30,
            isPauseLimit = true
        )
        assertThat(canRemoveDailyLimit(BlockTypeUi.DAILY_LIMIT, pause, until * 10)).isTrue()
        val paused = pause.copy(pauseUntilMillis = pauseEnd)
        assertThat(canRemoveDailyLimit(BlockTypeUi.DAILY_LIMIT, paused, start)).isFalse()
        assertThat(canRemoveDailyLimit(BlockTypeUi.DAILY_LIMIT, paused, pauseEnd)).isTrue()
    }
}
