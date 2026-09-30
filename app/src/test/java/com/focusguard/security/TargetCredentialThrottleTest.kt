package com.focusguard.security

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test

class TargetCredentialThrottleTest {
    @After
    fun tearDown() = TargetCredentialThrottle.resetForTest()

    @Test
    fun `five wrong attempts lock the target and the lock doubles on the next round`() {
        val target = "com.example.app"
        repeat(4) { TargetCredentialThrottle.recordFailure(target, nowElapsed = 1_000L) }
        assertThat(TargetCredentialThrottle.remainingLockoutMillis(target, 1_000L)).isEqualTo(0L)

        TargetCredentialThrottle.recordFailure(target, nowElapsed = 1_000L)
        assertThat(TargetCredentialThrottle.remainingLockoutMillis(target, 1_000L))
            .isEqualTo(TargetCredentialThrottle.BASE_LOCKOUT_MILLIS)

        val afterLock = 1_000L + TargetCredentialThrottle.BASE_LOCKOUT_MILLIS
        repeat(5) { TargetCredentialThrottle.recordFailure(target, nowElapsed = afterLock) }
        assertThat(TargetCredentialThrottle.remainingLockoutMillis(target, afterLock))
            .isEqualTo(TargetCredentialThrottle.BASE_LOCKOUT_MILLIS * 2)
    }

    @Test
    fun `a correct credential clears the count and targets are independent`() {
        repeat(5) { TargetCredentialThrottle.recordFailure("a", nowElapsed = 0L) }
        assertThat(TargetCredentialThrottle.remainingLockoutMillis("b", 0L)).isEqualTo(0L)

        TargetCredentialThrottle.recordSuccess("a")
        assertThat(TargetCredentialThrottle.remainingLockoutMillis("a", 0L)).isEqualTo(0L)
    }
}
