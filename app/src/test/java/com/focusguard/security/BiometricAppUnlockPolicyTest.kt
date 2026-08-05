package com.focusguard.security

import com.focusguard.security.BiometricAppUnlockPolicy.BlockOrigin
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BiometricAppUnlockPolicyTest {

    // ------------------------------------------------- credential acceptance

    @Test
    fun `password session and password-unlock limit accept a credential`() {
        assertThat(
            BiometricAppUnlockPolicy.acceptsCredentialUnlock(BlockOrigin.PASSWORD_SESSION)
        ).isTrue()
        assertThat(
            BiometricAppUnlockPolicy.acceptsCredentialUnlock(
                BlockOrigin.USAGE_LIMIT_PASSWORD_UNLOCK
            )
        ).isTrue()
    }

    @Test
    fun `dopamine fast never accepts a credential`() {
        assertThat(
            BiometricAppUnlockPolicy.acceptsCredentialUnlock(BlockOrigin.DOPAMINE_FAST)
        ).isFalse()
    }

    @Test
    fun `strict pomodoro never accepts a credential`() {
        assertThat(
            BiometricAppUnlockPolicy.acceptsCredentialUnlock(BlockOrigin.STRICT_POMODORO)
        ).isFalse()
    }

    @Test
    fun `time hardened limit never accepts a credential`() {
        assertThat(
            BiometricAppUnlockPolicy.acceptsCredentialUnlock(
                BlockOrigin.USAGE_LIMIT_TIME_HARDENED
            )
        ).isFalse()
    }

    @Test
    fun `limit configured without password unlock does not accept a credential`() {
        assertThat(
            BiometricAppUnlockPolicy.acceptsCredentialUnlock(BlockOrigin.USAGE_LIMIT_NO_UNLOCK)
        ).isFalse()
    }

    // ------------------------------------------------------------- offering

    @Test
    fun `fingerprint is offered only when block, preference and hardware all agree`() {
        val offered = BiometricAppUnlockPolicy.shouldOfferBiometricUnlock(
            origin = BlockOrigin.PASSWORD_SESSION,
            biometricUnlockEnabled = true,
            biometricAvailable = true
        )

        assertThat(offered).isTrue()
    }

    @Test
    fun `preference off hides the fingerprint even where password works`() {
        val offered = BiometricAppUnlockPolicy.shouldOfferBiometricUnlock(
            origin = BlockOrigin.PASSWORD_SESSION,
            biometricUnlockEnabled = false,
            biometricAvailable = true
        )

        assertThat(offered).isFalse()
    }

    @Test
    fun `no enrolled biometrics hides the fingerprint`() {
        val offered = BiometricAppUnlockPolicy.shouldOfferBiometricUnlock(
            origin = BlockOrigin.PASSWORD_SESSION,
            biometricUnlockEnabled = true,
            biometricAvailable = false
        )

        assertThat(offered).isFalse()
    }

    @Test
    fun `preference on cannot open a dopamine fast`() {
        // The headline guarantee: enabling fingerprint unlock must not punch a
        // hole in the fast.
        val offered = BiometricAppUnlockPolicy.shouldOfferBiometricUnlock(
            origin = BlockOrigin.DOPAMINE_FAST,
            biometricUnlockEnabled = true,
            biometricAvailable = true
        )

        assertThat(offered).isFalse()
    }

    @Test
    fun `preference on cannot open a time hardened limit`() {
        val offered = BiometricAppUnlockPolicy.shouldOfferBiometricUnlock(
            origin = BlockOrigin.USAGE_LIMIT_TIME_HARDENED,
            biometricUnlockEnabled = true,
            biometricAvailable = true
        )

        assertThat(offered).isFalse()
    }

    // -------------------------------------------------------- result guard

    @Test
    fun `success callback is rejected for a sealed block`() {
        // Re-checked after the prompt returns: a late or misrouted callback must
        // not unlock a fast.
        assertThat(
            BiometricAppUnlockPolicy.canAcceptBiometricResult(
                origin = BlockOrigin.DOPAMINE_FAST,
                biometricUnlockEnabled = true
            )
        ).isFalse()
    }

    @Test
    fun `success callback is rejected once the preference is turned off`() {
        assertThat(
            BiometricAppUnlockPolicy.canAcceptBiometricResult(
                origin = BlockOrigin.PASSWORD_SESSION,
                biometricUnlockEnabled = false
            )
        ).isFalse()
    }

    @Test
    fun `success callback is accepted for a password session`() {
        assertThat(
            BiometricAppUnlockPolicy.canAcceptBiometricResult(
                origin = BlockOrigin.PASSWORD_SESSION,
                biometricUnlockEnabled = true
            )
        ).isTrue()
    }

    // --------------------------------------------------------- classification

    @Test
    fun `strict pomodoro outranks everything else`() {
        val origin = BiometricAppUnlockPolicy.classify(
            strictPomodoroActive = true,
            activeSessionType = "PASSWORD",
            usageLimitExceeded = true,
            limitLockMode = "NONE",
            limitLockUntilTimestamp = null,
            limitUnlockWithPassword = true
        )

        assertThat(origin).isEqualTo(BlockOrigin.STRICT_POMODORO)
    }

    @Test
    fun `dopamine fast outranks a permissive usage limit`() {
        // Getting this precedence backwards would let a permissive limit unseal
        // a fast.
        val origin = BiometricAppUnlockPolicy.classify(
            strictPomodoroActive = false,
            activeSessionType = "TIME",
            usageLimitExceeded = true,
            limitLockMode = "NONE",
            limitLockUntilTimestamp = null,
            limitUnlockWithPassword = true
        )

        assertThat(origin).isEqualTo(BlockOrigin.DOPAMINE_FAST)
    }

    @Test
    fun `exceeded limit with password unlock is classified as unlockable`() {
        val origin = BiometricAppUnlockPolicy.classify(
            strictPomodoroActive = false,
            activeSessionType = null,
            usageLimitExceeded = true,
            limitLockMode = "PASSWORD",
            limitLockUntilTimestamp = null,
            limitUnlockWithPassword = true
        )

        assertThat(origin).isEqualTo(BlockOrigin.USAGE_LIMIT_PASSWORD_UNLOCK)
    }

    @Test
    fun `time hardening outranks the password unlock flag on the same limit`() {
        val origin = BiometricAppUnlockPolicy.classify(
            strictPomodoroActive = false,
            activeSessionType = null,
            usageLimitExceeded = true,
            limitLockMode = "TIME",
            limitLockUntilTimestamp = 2_000L,
            limitUnlockWithPassword = true,
            nowMillis = 1_000L
        )

        assertThat(origin).isEqualTo(BlockOrigin.USAGE_LIMIT_TIME_HARDENED)
    }

    @Test
    fun `expired time lock falls back to the password unlock flag`() {
        val origin = BiometricAppUnlockPolicy.classify(
            strictPomodoroActive = false,
            activeSessionType = null,
            usageLimitExceeded = true,
            limitLockMode = "TIME",
            limitLockUntilTimestamp = 1_000L,
            limitUnlockWithPassword = true,
            nowMillis = 5_000L
        )

        assertThat(origin).isEqualTo(BlockOrigin.USAGE_LIMIT_PASSWORD_UNLOCK)
    }

    @Test
    fun `exceeded limit without password unlock is sealed`() {
        val origin = BiometricAppUnlockPolicy.classify(
            strictPomodoroActive = false,
            activeSessionType = null,
            usageLimitExceeded = true,
            limitLockMode = "NONE",
            limitLockUntilTimestamp = null,
            limitUnlockWithPassword = false
        )

        assertThat(origin).isEqualTo(BlockOrigin.USAGE_LIMIT_NO_UNLOCK)
    }

    @Test
    fun `password session with no limit is classified as a password session`() {
        val origin = BiometricAppUnlockPolicy.classify(
            strictPomodoroActive = false,
            activeSessionType = "PASSWORD",
            usageLimitExceeded = false,
            limitLockMode = null,
            limitLockUntilTimestamp = null,
            limitUnlockWithPassword = false
        )

        assertThat(origin).isEqualTo(BlockOrigin.PASSWORD_SESSION)
    }

    @Test
    fun `nothing blocking yields no origin`() {
        val origin = BiometricAppUnlockPolicy.classify(
            strictPomodoroActive = false,
            activeSessionType = null,
            usageLimitExceeded = false,
            limitLockMode = null,
            limitLockUntilTimestamp = null,
            limitUnlockWithPassword = false
        )

        assertThat(origin).isNull()
    }

    @Test
    fun `session type classification is case insensitive`() {
        val origin = BiometricAppUnlockPolicy.classify(
            strictPomodoroActive = false,
            activeSessionType = "time",
            usageLimitExceeded = false,
            limitLockMode = null,
            limitLockUntilTimestamp = null,
            limitUnlockWithPassword = false
        )

        assertThat(origin).isEqualTo(BlockOrigin.DOPAMINE_FAST)
    }

    @Test
    fun `every origin is covered by the acceptance rule`() {
        // Guards against a new BlockOrigin silently defaulting to unlockable.
        val unlockable = BlockOrigin.entries.filter {
            BiometricAppUnlockPolicy.acceptsCredentialUnlock(it)
        }

        assertThat(unlockable).containsExactly(
            BlockOrigin.PASSWORD_SESSION,
            BlockOrigin.USAGE_LIMIT_PASSWORD_UNLOCK
        )
    }
}
