package com.focusguard.ui.compose.screens

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PermissionsScreenFlowTest {

    @Test
    fun `pending flow shows only the essential permissions that are missing`() {
        assertThat(
            permissionStepsForFlow(
                flowMode = PermissionFlowMode.PendingEssentials,
                state = PermissionState(accessibility = false, usageAccess = false)
            )
        ).containsExactly(
            PermissionStepType.Accessibility,
            PermissionStepType.UsageAccess
        ).inOrder()

        assertThat(
            permissionStepsForFlow(
                flowMode = PermissionFlowMode.PendingEssentials,
                state = PermissionState(accessibility = true, usageAccess = false)
            )
        ).containsExactly(PermissionStepType.UsageAccess)

        assertThat(
            permissionStepsForFlow(
                flowMode = PermissionFlowMode.PendingEssentials,
                state = PermissionState(accessibility = false, usageAccess = true)
            )
        ).containsExactly(PermissionStepType.Accessibility)

        assertThat(
            permissionStepsForFlow(
                flowMode = PermissionFlowMode.PendingEssentials,
                state = PermissionState(accessibility = true, usageAccess = true)
            )
        ).isEmpty()
    }

    @Test
    fun `full setup keeps optional permissions after the essential ones`() {
        assertThat(
            permissionStepsForFlow(
                flowMode = PermissionFlowMode.FullSetup,
                state = PermissionState(accessibility = true, usageAccess = true)
            )
        ).containsExactly(
            PermissionStepType.Accessibility,
            PermissionStepType.UsageAccess,
            PermissionStepType.Notifications,
            PermissionStepType.BatteryOptimization,
            PermissionStepType.DeviceAdmin
        ).inOrder()
    }

    @Test
    fun `first render after an app update skips permissions that remain granted`() {
        val stateAfterUpdate = PermissionState(
            accessibility = true,
            usageAccess = true,
            notifications = false,
            batteryOptimization = false,
            deviceAdmin = false
        )
        val steps = permissionStepsForFlow(
            flowMode = PermissionFlowMode.FullSetup,
            state = stateAfterUpdate
        )

        assertThat(
            firstActionablePermissionStepIndex(
                steps = steps,
                state = stateAfterUpdate
            )
        ).isEqualTo(2)
        assertThat(steps[2]).isEqualTo(PermissionStepType.Notifications)
    }

    @Test
    fun `first render goes straight to summary when every permission is already granted`() {
        val grantedState = PermissionState(
            notifications = true,
            batteryOptimization = true,
            accessibility = true,
            usageAccess = true,
            deviceAdmin = true
        )
        val steps = permissionStepsForFlow(
            flowMode = PermissionFlowMode.FullSetup,
            state = grantedState
        )

        assertThat(
            firstActionablePermissionStepIndex(
                steps = steps,
                state = grantedState
            )
        ).isEqualTo(steps.size)
    }

    @Test
    fun `live preflight prevents requesting a permission granted after screen opened`() {
        val stateGrantedWhileOpen = PermissionState(accessibility = true)

        assertThat(
            shouldRequestPermission(
                step = PermissionStepType.Accessibility,
                state = stateGrantedWhileOpen
            )
        ).isFalse()
    }

    @Test
    fun `live preflight still requests a permission that is actually missing`() {
        assertThat(
            shouldRequestPermission(
                step = PermissionStepType.UsageAccess,
                state = PermissionState(usageAccess = false)
            )
        ).isTrue()
    }
}
