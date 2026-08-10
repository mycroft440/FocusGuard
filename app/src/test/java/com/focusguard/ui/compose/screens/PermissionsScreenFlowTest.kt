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
}
