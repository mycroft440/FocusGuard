package com.focusguard.security

import com.focusguard.database.AppUsageLimit
import com.focusguard.database.BlockSession
import com.focusguard.security.MasterCredentialPolicy.ConfigurationGate
import com.focusguard.security.MasterCredentialPolicy.CreationGate
import com.focusguard.security.MasterCredentialPolicy.MutationGate
import com.focusguard.security.MasterCredentialPolicy.UninstallGate
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MasterCredentialPolicyTest {

    @Test
    fun `block creation never requires master credential`() {
        listOf("PASSWORD", "TIME", "POMODORO", "SOMETHING_ELSE").forEach { type ->
            assertThat(MasterCredentialPolicy.requiresMasterCredentialToCreate(type)).isFalse()
            assertThat(
                MasterCredentialPolicy.evaluateCreation(type, hasMasterCredential = false)
            ).isEqualTo(CreationGate.ALLOWED)
        }
    }

    @Test
    fun `master credential can be configured before protected blocks exist`() {
        assertThat(
            MasterCredentialPolicy.evaluateCredentialConfiguration(
                activeSessions = emptyList(),
                hasActiveUsageLimit = false,
                nowMillis = 1_000L
            )
        ).isEqualTo(ConfigurationGate.ALLOWED)
    }

    @Test
    fun `password and pomodoro sessions do not block master credential configuration`() {
        val sessions = listOf(
            BlockSession(sessionType = "PASSWORD", endTime = null),
            BlockSession(sessionType = "POMODORO", endTime = 2_000L)
        )

        assertThat(
            MasterCredentialPolicy.evaluateCredentialConfiguration(
                activeSessions = sessions,
                hasActiveUsageLimit = false,
                nowMillis = 1_000L
            )
        ).isEqualTo(ConfigurationGate.ALLOWED)
    }

    @Test
    fun `active time session blocks master credential configuration`() {
        val session = BlockSession(
            sessionType = "TIME",
            endTime = 2_000L,
            isActive = true
        )

        assertThat(
            MasterCredentialPolicy.evaluateCredentialConfiguration(
                activeSessions = listOf(session),
                hasActiveUsageLimit = false,
                nowMillis = 1_000L
            )
        ).isEqualTo(ConfigurationGate.BLOCKED_BY_TIME_BLOCK)
    }

    @Test
    fun `expired or inactive time session does not block master credential configuration`() {
        val sessions = listOf(
            BlockSession(sessionType = "TIME", endTime = 500L, isActive = true),
            BlockSession(sessionType = "TIME", endTime = 2_000L, isActive = false)
        )

        assertThat(
            MasterCredentialPolicy.evaluateCredentialConfiguration(
                activeSessions = sessions,
                hasActiveUsageLimit = false,
                nowMillis = 1_000L
            )
        ).isEqualTo(ConfigurationGate.ALLOWED)
    }

    @Test
    fun `enabled usage limit blocks master credential configuration`() {
        assertThat(
            MasterCredentialPolicy.evaluateCredentialConfiguration(
                activeSessions = listOf(BlockSession(sessionType = "PASSWORD")),
                hasActiveUsageLimit = true,
                nowMillis = 1_000L
            )
        ).isEqualTo(ConfigurationGate.BLOCKED_BY_USAGE_LIMIT)
    }

    @Test
    fun `time block takes precedence when time and usage limit are both active`() {
        assertThat(
            MasterCredentialPolicy.evaluateCredentialConfiguration(
                activeSessions = listOf(
                    BlockSession(sessionType = "TIME", endTime = null, isActive = true)
                ),
                hasActiveUsageLimit = true,
                nowMillis = 1_000L
            )
        ).isEqualTo(ConfigurationGate.BLOCKED_BY_TIME_BLOCK)
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
    fun `unprotected usage limit target can enter setup without master credential`() {
        assertThat(
            MasterCredentialPolicy.evaluateLimitMutation(
                lockMode = "NONE",
                lockUntilTimestamp = null,
                safetyModeEnabled = false,
                hasMasterCredential = false,
                masterCredentialVerified = false
            )
        ).isEqualTo(MutationGate.ALLOWED)
    }

    @Test
    fun `protected editable usage limit requires master credential to exist`() {
        assertThat(
            MasterCredentialPolicy.evaluateLimitMutation(
                lockMode = "PASSWORD",
                lockUntilTimestamp = null,
                safetyModeEnabled = false,
                hasMasterCredential = false,
                masterCredentialVerified = false
            )
        ).isEqualTo(MutationGate.MASTER_CREDENTIAL_NOT_CONFIGURED)
    }

    @Test
    fun `protected editable usage limit is allowed after master credential exists`() {
        assertThat(
            MasterCredentialPolicy.evaluateLimitMutation(
                lockMode = "PASSWORD",
                lockUntilTimestamp = null,
                safetyModeEnabled = false,
                hasMasterCredential = true,
                masterCredentialVerified = false
            )
        ).isEqualTo(MutationGate.ALLOWED)
    }

    @Test
    fun `expired time limit still requires configured master credential`() {
        assertThat(
            MasterCredentialPolicy.evaluateLimitMutation(
                lockMode = "TIME",
                lockUntilTimestamp = 1_000L,
                safetyModeEnabled = false,
                hasMasterCredential = false,
                masterCredentialVerified = false,
                nowMillis = 5_000L
            )
        ).isEqualTo(MutationGate.MASTER_CREDENTIAL_NOT_CONFIGURED)
    }

    @Test
    fun `limit row overload preserves time hardening precedence`() {
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
