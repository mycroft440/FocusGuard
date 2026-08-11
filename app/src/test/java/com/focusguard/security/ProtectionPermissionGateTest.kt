package com.focusguard.security

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProtectionPermissionGateTest {

    @Test
    fun `block configuration requires all five protection permissions`() {
        val state = ProtectionPermissionState(
            accessibility = true,
            usageAccess = true,
            notifications = false,
            batteryOptimization = true,
            deviceAdmin = false
        )

        assertThat(state.isReady).isFalse()
        assertThat(state.missingPermissions).containsExactly(
            ProtectionPermission.NOTIFICATIONS,
            ProtectionPermission.DEVICE_ADMIN
        ).inOrder()
    }

    @Test
    fun `configuration is ready only when every permission is active`() {
        val state = ProtectionPermissionState(
            accessibility = true,
            usageAccess = true,
            notifications = true,
            batteryOptimization = true,
            deviceAdmin = true
        )

        assertThat(state.isReady).isTrue()
        assertThat(state.missingPermissions).isEmpty()
    }

    @Test
    fun `missing permissions keep the same order shown by setup`() {
        val state = ProtectionPermissionState(
            accessibility = false,
            usageAccess = false,
            notifications = false,
            batteryOptimization = false,
            deviceAdmin = false
        )

        assertThat(state.missingPermissions).containsExactly(
            ProtectionPermission.ACCESSIBILITY,
            ProtectionPermission.USAGE_ACCESS,
            ProtectionPermission.NOTIFICATIONS,
            ProtectionPermission.BATTERY_OPTIMIZATION,
            ProtectionPermission.DEVICE_ADMIN
        ).inOrder()
    }
}
