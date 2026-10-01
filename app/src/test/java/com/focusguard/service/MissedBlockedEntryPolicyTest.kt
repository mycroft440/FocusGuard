package com.focusguard.service

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Rede de segurança: um app bloqueado em uso sem ter passado pela tela de bloqueio. */
class MissedBlockedEntryPolicyTest {

    @Test
    fun `an app in use without a grant and without a block in progress is checked`() {
        assertThat(check()).isTrue()
    }

    @Test
    fun `an app unlocked by password is left alone`() {
        assertThat(check(granted = true)).isFalse()
    }

    @Test
    fun `a block already on its way is not started twice`() {
        assertThat(check(blockInProgress = true)).isFalse()
        assertThat(check(recentlyBlockedSamePackage = true)).isFalse()
    }

    @Test
    fun `checks are spaced out`() {
        assertThat(check(elapsedSinceLastCheck = 100L)).isFalse()
        assertThat(check(elapsedSinceLastCheck = 300L)).isTrue()
    }

    private fun check(
        granted: Boolean = false,
        blockInProgress: Boolean = false,
        recentlyBlockedSamePackage: Boolean = false,
        elapsedSinceLastCheck: Long = 10_000L
    ) = BlockingAccessibilityService.shouldCheckMissedEntry(
        granted = granted,
        blockInProgress = blockInProgress,
        recentlyBlockedSamePackage = recentlyBlockedSamePackage,
        elapsedSinceLastCheck = elapsedSinceLastCheck
    )
}
