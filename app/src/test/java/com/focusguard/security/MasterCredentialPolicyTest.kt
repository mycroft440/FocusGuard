package com.focusguard.security

import com.focusguard.database.AppUsageLimit
import com.focusguard.security.MasterCredentialPolicy.CreationGate
import com.focusguard.security.MasterCredentialPolicy.MutationGate
import com.focusguard.security.MasterCredentialPolicy.UninstallGate
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MasterCredentialPolicyTest {

    // ---------------------------------------------------------------- creation

    @Test
    fun `password block cannot be armed without a master credential`() {
        val gate = MasterCredentialPolicy.evaluateCreation(
            sessionType = "PASSWORD",
            hasMasterCredential = false
        )

        assertThat(gate).isEqualTo(CreationGate.MASTER_CREDENTIAL_REQUIRED)
    }

    @Test
    fun `time block requires a master credential before arming`() {
        val gate = MasterCredentialPolicy.evaluateCreation(
            sessionType = "TIME",
            hasMasterCredential = false
        )

        assertThat(gate).isEqualTo(CreationGate.MASTER_CREDENTIAL_REQUIRED)
    }

    @Test
    fun `time stays sealed to generic mutation but has explicit master revocation`() {
        assertThat(MasterCredentialPolicy.requiresMasterCredentialToCreate("TIME")).isTrue()
        assertThat(MasterCredentialPolicy.isIrreversibleSessionType("TIME")).isTrue()
        assertThat(MasterCredentialPolicy.allowsExplicitMasterRevocation("TIME")).isTrue()
        assertThat(MasterCredentialPolicy.allowsExplicitMasterRevocation("POMODORO")).isFalse()
    }

    @Test
    fun `pomodoro does not demand a master credential to start`() {
        // A 25-minute focus timer is started many times a day and is not one of
        // the blocks the app calls "bloqueio por tempo/senha". Gating it would be
        // friction with no protective payoff.
        val gate = MasterCredentialPolicy.evaluateCreation(
            sessionType = "POMODORO",
            hasMasterCredential = false
        )

        assertThat(gate).isEqualTo(CreationGate.ALLOWED)
    }

    @Test
    fun `pomodoro is still irreversible even though it needs no credential`() {
        // Two different questions: "can it start?" vs "can it be ended early?".
        assertThat(MasterCredentialPolicy.requiresMasterCredentialToCreate("POMODORO")).isFalse()
        assertThat(MasterCredentialPolicy.isIrreversibleSessionType("POMODORO")).isTrue()
    }

    @Test
    fun `blocks are allowed once the master credential exists`() {
        val gate = MasterCredentialPolicy.evaluateCreation(
            sessionType = "PASSWORD",
            hasMasterCredential = true
        )

        assertThat(gate).isEqualTo(CreationGate.ALLOWED)
    }

    @Test
    fun `session type matching is case insensitive`() {
        assertThat(MasterCredentialPolicy.requiresMasterCredentialToCreate("Password")).isTrue()
        assertThat(MasterCredentialPolicy.requiresMasterCredentialToCreate("password")).isTrue()
    }

    @Test
    fun `password and explicit time blocks demand a credential up front`() {
        listOf("PASSWORD", "TIME", "POMODORO", "SOMETHING_ELSE")
            .filter(MasterCredentialPolicy::requiresMasterCredentialToCreate)
            .let { assertThat(it).containsExactly("PASSWORD", "TIME") }
    }

    @Test
    fun `unknown session types do not demand a credential`() {
        val gate = MasterCredentialPolicy.evaluateCreation(
            sessionType = "SOMETHING_ELSE",
            hasMasterCredential = false
        )

        assertThat(gate).isEqualTo(CreationGate.ALLOWED)
    }

    // ------------------------------------------------------- time hardening

    @Test
    fun `future time lock is hardening`() {
        val hardened = MasterCredentialPolicy.isTimeHardened(
            lockMode = "TIME",
            lockUntilTimestamp = 2_000L,
            nowMillis = 1_000L
        )

        assertThat(hardened).isTrue()
    }

    @Test
    fun `elapsed time lock is not hardening`() {
        // An expired lock must never strand the user.
        val hardened = MasterCredentialPolicy.isTimeHardened(
            lockMode = "TIME",
            lockUntilTimestamp = 1_000L,
            nowMillis = 2_000L
        )

        assertThat(hardened).isFalse()
    }

    @Test
    fun `time lock exactly at expiry is not hardening`() {
        val hardened = MasterCredentialPolicy.isTimeHardened(
            lockMode = "TIME",
            lockUntilTimestamp = 1_000L,
            nowMillis = 1_000L
        )

        assertThat(hardened).isFalse()
    }

    @Test
    fun `time mode without a timestamp is not hardening`() {
        val hardened = MasterCredentialPolicy.isTimeHardened(
            lockMode = "TIME",
            lockUntilTimestamp = null,
            nowMillis = 1_000L
        )

        assertThat(hardened).isFalse()
    }

    @Test
    fun `password mode is never hardening even with a future timestamp`() {
        val hardened = MasterCredentialPolicy.isTimeHardened(
            lockMode = "PASSWORD",
            lockUntilTimestamp = Long.MAX_VALUE,
            nowMillis = 1_000L
        )

        assertThat(hardened).isFalse()
    }

    // ------------------------------------------------------- limit mutation

    @Test
    fun `time hardened limit refuses mutation even with a verified credential`() {
        // The app promises "impossível de burlar" — the master credential is a
        // key for reversible protection, not an override.
        val gate = MasterCredentialPolicy.evaluateLimitMutation(
            lockMode = "TIME",
            lockUntilTimestamp = 2_000L,
            safetyModeEnabled = false,
            hasMasterCredential = true,
            masterCredentialVerified = true,
            nowMillis = 1_000L
        )

        assertThat(gate).isEqualTo(MutationGate.BLOCKED_BY_TIME_HARDENING)
    }

    @Test
    fun `safety mode refuses mutation even with a verified credential`() {
        val gate = MasterCredentialPolicy.evaluateLimitMutation(
            lockMode = "NONE",
            lockUntilTimestamp = null,
            safetyModeEnabled = true,
            hasMasterCredential = true,
            masterCredentialVerified = true
        )

        assertThat(gate).isEqualTo(MutationGate.BLOCKED_BY_SAFETY_MODE)
    }

    @Test
    fun `unbreakable refusals are evaluated before the credential prompt`() {
        // The user must never be asked for a password that cannot unlock anything.
        val gate = MasterCredentialPolicy.evaluateLimitMutation(
            lockMode = "TIME",
            lockUntilTimestamp = 2_000L,
            safetyModeEnabled = false,
            hasMasterCredential = false,
            masterCredentialVerified = false,
            nowMillis = 1_000L
        )

        assertThat(gate).isEqualTo(MutationGate.BLOCKED_BY_TIME_HARDENING)
    }

    @Test
    fun `time hardening outranks safety mode in the refusal reason`() {
        val gate = MasterCredentialPolicy.evaluateLimitMutation(
            lockMode = "TIME",
            lockUntilTimestamp = 2_000L,
            safetyModeEnabled = true,
            hasMasterCredential = true,
            masterCredentialVerified = true,
            nowMillis = 1_000L
        )

        assertThat(gate).isEqualTo(MutationGate.BLOCKED_BY_TIME_HARDENING)
    }

    @Test
    fun `password limit requires the credential prompt`() {
        val gate = MasterCredentialPolicy.evaluateLimitMutation(
            lockMode = "PASSWORD",
            lockUntilTimestamp = null,
            safetyModeEnabled = false,
            hasMasterCredential = true,
            masterCredentialVerified = false
        )

        assertThat(gate).isEqualTo(MutationGate.MASTER_CREDENTIAL_REQUIRED)
    }

    @Test
    fun `password limit is mutable once the credential is verified`() {
        val gate = MasterCredentialPolicy.evaluateLimitMutation(
            lockMode = "PASSWORD",
            lockUntilTimestamp = null,
            safetyModeEnabled = false,
            hasMasterCredential = true,
            masterCredentialVerified = true
        )

        assertThat(gate).isEqualTo(MutationGate.ALLOWED)
    }

    @Test
    fun `password limit reports a missing credential distinctly`() {
        val gate = MasterCredentialPolicy.evaluateLimitMutation(
            lockMode = "PASSWORD",
            lockUntilTimestamp = null,
            safetyModeEnabled = false,
            hasMasterCredential = false,
            masterCredentialVerified = false
        )

        assertThat(gate).isEqualTo(MutationGate.MASTER_CREDENTIAL_NOT_CONFIGURED)
    }

    @Test
    fun `passwordless limit can be configured without a credential`() {
        val gate = MasterCredentialPolicy.evaluateLimitMutation(
            lockMode = "NONE",
            lockUntilTimestamp = null,
            safetyModeEnabled = false,
            hasMasterCredential = false,
            masterCredentialVerified = false
        )

        assertThat(gate).isEqualTo(MutationGate.ALLOWED)
    }

    @Test
    fun `expired time lock becomes mutable with a verified credential`() {
        val gate = MasterCredentialPolicy.evaluateLimitMutation(
            lockMode = "TIME",
            lockUntilTimestamp = 1_000L,
            safetyModeEnabled = false,
            hasMasterCredential = true,
            masterCredentialVerified = true,
            nowMillis = 5_000L
        )

        assertThat(gate).isEqualTo(MutationGate.ALLOWED)
    }

    @Test
    fun `limit row overload agrees with the primitive overload`() {
        val limit = AppUsageLimit(
            packageName = "com.example.app",
            appName = "Example",
            dailyLimitMinutes = 30,
            lockMode = "TIME",
            lockUntilTimestamp = 2_000L,
            createdAt = 0L,
            lastResetDate = 0L
        )

        val gate = MasterCredentialPolicy.evaluateLimitMutation(
            limit = limit,
            safetyModeEnabled = false,
            hasMasterCredential = true,
            masterCredentialVerified = true,
            nowMillis = 1_000L
        )

        assertThat(gate).isEqualTo(MutationGate.BLOCKED_BY_TIME_HARDENING)
    }

    // ----------------------------------------------------------- uninstall

    @Test
    fun `uninstall is refused while an irreversible block runs`() {
        // Uninstalling must not become the way out of a fast the user chose.
        val gate = MasterCredentialPolicy.evaluateUninstall(
            hasActiveIrreversibleBlock = true,
            hasMasterCredential = true,
            masterCredentialVerified = true
        )

        assertThat(gate).isEqualTo(UninstallGate.BLOCKED_BY_ACTIVE_IRREVERSIBLE_BLOCK)
    }

    @Test
    fun `uninstall requires the credential prompt`() {
        val gate = MasterCredentialPolicy.evaluateUninstall(
            hasActiveIrreversibleBlock = false,
            hasMasterCredential = true,
            masterCredentialVerified = false
        )

        assertThat(gate).isEqualTo(UninstallGate.MASTER_CREDENTIAL_REQUIRED)
    }

    @Test
    fun `uninstall is allowed with a verified credential and no active fast`() {
        val gate = MasterCredentialPolicy.evaluateUninstall(
            hasActiveIrreversibleBlock = false,
            hasMasterCredential = true,
            masterCredentialVerified = true
        )

        assertThat(gate).isEqualTo(UninstallGate.ALLOWED)
    }

    @Test
    fun `uninstall without any configured credential reports it distinctly`() {
        val gate = MasterCredentialPolicy.evaluateUninstall(
            hasActiveIrreversibleBlock = false,
            hasMasterCredential = false,
            masterCredentialVerified = false
        )

        assertThat(gate).isEqualTo(UninstallGate.MASTER_CREDENTIAL_NOT_CONFIGURED)
    }

    @Test
    fun `day 15 maintenance window allows uninstall during a time block`() {
        val gate = MasterCredentialPolicy.evaluateUninstall(
            hasActiveIrreversibleBlock = true,
            hasMasterCredential = false,
            masterCredentialVerified = false,
            maintenanceWindowActive = true
        )

        assertThat(gate).isEqualTo(UninstallGate.ALLOWED)
    }

    // ------------------------------------------------- irreversible session

    @Test
    fun `time and pomodoro are irreversible, password is not`() {
        assertThat(MasterCredentialPolicy.isIrreversibleSessionType("TIME")).isTrue()
        assertThat(MasterCredentialPolicy.isIrreversibleSessionType("POMODORO")).isTrue()
        assertThat(MasterCredentialPolicy.isIrreversibleSessionType("PASSWORD")).isFalse()
    }

    @Test
    fun `only explicit time block prevents uninstall`() {
        assertThat(MasterCredentialPolicy.blocksUninstall("TIME")).isTrue()
        assertThat(MasterCredentialPolicy.blocksUninstall("time")).isTrue()
        assertThat(MasterCredentialPolicy.blocksUninstall("PASSWORD")).isFalse()
        assertThat(MasterCredentialPolicy.blocksUninstall("POMODORO")).isFalse()
    }
}
