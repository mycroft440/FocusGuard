package com.focusguard.security

import com.focusguard.database.AppUsageLimit
import com.focusguard.security.MasterCredentialPolicy.CreationGate
import com.focusguard.security.MasterCredentialPolicy.MutationGate
import com.focusguard.security.MasterCredentialPolicy.UninstallGate
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MasterCredentialPolicyTest {

    @Test
    fun `password block does not require master credential to be created`() {
        assertThat(
            MasterCredentialPolicy.evaluateCreation("PASSWORD", hasMasterCredential = false)
        ).isEqualTo(CreationGate.ALLOWED)
        assertThat(MasterCredentialPolicy.requiresMasterCredentialToCreate("PASSWORD")).isFalse()
    }

    @Test
    fun `no session type uses master credential as a creation gate`() {
        listOf("PASSWORD", "TIME", "POMODORO", "SOMETHING_ELSE").forEach { type ->
            assertThat(MasterCredentialPolicy.requiresMasterCredentialToCreate(type)).isFalse()
            assertThat(
                MasterCredentialPolicy.evaluateCreation(type, hasMasterCredential = false)
            ).isEqualTo(CreationGate.ALLOWED)
        }
    }

    @Test
    fun `future time lock remains immutable regardless of master credential`() {
        assertThat(
            MasterCredentialPolicy.evaluateLimitMutation(
                lockMode = "TIME",
                lockUntilTimestamp = 2_000L,
                safetyModeEnabled = false,
                hasMasterCredential = true,
                masterCredentialVerified = true,
                nowMillis = 1_000L
            )
        ).isEqualTo(MutationGate.BLOCKED_BY_TIME_HARDENING)
    }

    @Test
    fun `safety mode remains immutable regardless of master credential`() {
        assertThat(
            MasterCredentialPolicy.evaluateLimitMutation(
                lockMode = "NONE",
                lockUntilTimestamp = null,
                safetyModeEnabled = true,
                hasMasterCredential = true,
                masterCredentialVerified = true
            )
        ).isEqualTo(MutationGate.BLOCKED_BY_SAFETY_MODE)
    }

    @Test
    fun `password limit mutation never asks for master credential`() {
        assertThat(
            MasterCredentialPolicy.evaluateLimitMutation(
                lockMode = "PASSWORD",
                lockUntilTimestamp = null,
                safetyModeEnabled = false,
                hasMasterCredential = false,
                masterCredentialVerified = false
            )
        ).isEqualTo(MutationGate.ALLOWED)
    }

    @Test
    fun `expired time limit is mutable without master credential`() {
        assertThat(
            MasterCredentialPolicy.evaluateLimitMutation(
                lockMode = "TIME",
                lockUntilTimestamp = 1_000L,
                safetyModeEnabled = false,
                hasMasterCredential = false,
                masterCredentialVerified = false,
                nowMillis = 5_000L
            )
        ).isEqualTo(MutationGate.ALLOWED)
    }

    @Test
    fun `limit row overload preserves hardening only`() {
        val limit = AppUsageLimit(
            packageName = "com.example.app",
            appName = "Example",
            dailyLimitMinutes = 30,
            lockMode = "TIME",
            lockUntilTimestamp = 2_000L,
            createdAt = 0L,
            lastResetDate = 0L
        )
        assertThat(
            MasterCredentialPolicy.evaluateLimitMutation(
                limit = limit,
                safetyModeEnabled = false,
                hasMasterCredential = false,
                masterCredentialVerified = false,
                nowMillis = 1_000L
            )
        ).isEqualTo(MutationGate.BLOCKED_BY_TIME_HARDENING)
    }

    @Test
    fun `uninstall does not use master credential when no irreversible block runs`() {
        assertThat(
            MasterCredentialPolicy.evaluateUninstall(
                hasActiveIrreversibleBlock = false,
                hasMasterCredential = false,
                masterCredentialVerified = false
            )
        ).isEqualTo(UninstallGate.ALLOWED)
    }

    @Test
    fun `irreversible block still refuses uninstall`() {
        assertThat(
            MasterCredentialPolicy.evaluateUninstall(
                hasActiveIrreversibleBlock = true,
                hasMasterCredential = true,
                masterCredentialVerified = true
            )
        ).isEqualTo(UninstallGate.BLOCKED_BY_ACTIVE_IRREVERSIBLE_BLOCK)
    }

    @Test
    fun `maintenance window still permits uninstall during time block`() {
        assertThat(
            MasterCredentialPolicy.evaluateUninstall(
                hasActiveIrreversibleBlock = true,
                hasMasterCredential = false,
                masterCredentialVerified = false,
                maintenanceWindowActive = true
            )
        ).isEqualTo(UninstallGate.ALLOWED)
    }

    @Test
    fun `time and pomodoro are irreversible while password is not`() {
        assertThat(MasterCredentialPolicy.isIrreversibleSessionType("TIME")).isTrue()
        assertThat(MasterCredentialPolicy.isIrreversibleSessionType("POMODORO")).isTrue()
        assertThat(MasterCredentialPolicy.isIrreversibleSessionType("PASSWORD")).isFalse()
    }

    @Test
    fun `only explicit time block prevents uninstall`() {
        assertThat(MasterCredentialPolicy.blocksUninstall("TIME")).isTrue()
        assertThat(MasterCredentialPolicy.blocksUninstall("PASSWORD")).isFalse()
        assertThat(MasterCredentialPolicy.blocksUninstall("POMODORO")).isFalse()
    }
}
