package com.focusguard.security

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MasterCredentialAttemptLimiterTest {

    @Test
    fun `first two mistakes do not delay legitimate retries`() {
        assertThat(MasterCredentialAttemptLimiter.delayForFailureCount(1)).isEqualTo(0L)
        assertThat(MasterCredentialAttemptLimiter.delayForFailureCount(2)).isEqualTo(0L)
    }

    @Test
    fun `third and later failures progressively slow guessing`() {
        assertThat(MasterCredentialAttemptLimiter.delayForFailureCount(3)).isEqualTo(5_000L)
        assertThat(MasterCredentialAttemptLimiter.delayForFailureCount(4)).isEqualTo(15_000L)
        assertThat(MasterCredentialAttemptLimiter.delayForFailureCount(5)).isEqualTo(30_000L)
        assertThat(MasterCredentialAttemptLimiter.delayForFailureCount(6)).isEqualTo(60_000L)
        assertThat(MasterCredentialAttemptLimiter.delayForFailureCount(7)).isEqualTo(120_000L)
    }

    @Test
    fun `backoff is capped so recovery never becomes permanent`() {
        assertThat(MasterCredentialAttemptLimiter.delayForFailureCount(30))
            .isEqualTo(MasterCredentialAttemptLimiter.MAX_DELAY_MILLIS)
    }
}
