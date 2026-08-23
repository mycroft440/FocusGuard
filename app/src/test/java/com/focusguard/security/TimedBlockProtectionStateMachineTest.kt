package com.focusguard.security

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TimedBlockProtectionStateMachineTest {

    @Test
    fun `idle when nothing is protected or pending`() {
        assertThat(
            TimedBlockProtectionStateMachine.afterReconcile(
                hasProtectedSessions = false,
                pendingCreation = false,
                policyApplied = true
            )
        ).isEqualTo(TimedBlockProtectionStateMachine.Phase.IDLE)
    }

    @Test
    fun `preparing survives a crash between native policy and room commit`() {
        assertThat(
            TimedBlockProtectionStateMachine.afterReconcile(
                hasProtectedSessions = false,
                pendingCreation = true,
                policyApplied = true
            )
        ).isEqualTo(TimedBlockProtectionStateMachine.Phase.PREPARING)
    }

    @Test
    fun `active wins after exact session is committed`() {
        assertThat(
            TimedBlockProtectionStateMachine.afterReconcile(
                hasProtectedSessions = true,
                pendingCreation = false,
                policyApplied = true
            )
        ).isEqualTo(TimedBlockProtectionStateMachine.Phase.ACTIVE)
    }

    @Test
    fun `policy failure is explicit instead of pretending active`() {
        assertThat(
            TimedBlockProtectionStateMachine.afterReconcile(
                hasProtectedSessions = true,
                pendingCreation = false,
                policyApplied = false
            )
        ).isEqualTo(TimedBlockProtectionStateMachine.Phase.ERROR)
    }

    @Test
    fun `unknown persisted state falls back safely to idle`() {
        assertThat(TimedBlockProtectionStateMachine.Phase.fromStorage("corrupted"))
            .isEqualTo(TimedBlockProtectionStateMachine.Phase.IDLE)
    }
}
