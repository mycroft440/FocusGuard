package com.focusguard.security

import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionRevocationFlowTest {

    @Test
    fun manualSteps_returnsOnlyActiveUserManagedPermissionsInSafeOrder() {
        val state = state(
            accessibility = true,
            usageAccess = true,
            notifications = true,
            batteryOptimization = true,
            deviceAdmin = true
        )

        assertEquals(
            listOf(
                PermissionRevocationStep.NOTIFICATIONS,
                PermissionRevocationStep.BATTERY_OPTIMIZATION,
                PermissionRevocationStep.USAGE_ACCESS,
                PermissionRevocationStep.ACCESSIBILITY
            ),
            PermissionRevocationFlow.manualSteps(
                state = state,
                sdkInt = Build.VERSION_CODES.TIRAMISU
            )
        )
    }

    @Test
    fun manualSteps_doesNotTreatNotificationsAsRuntimePermissionBeforeAndroid13() {
        val state = state(notifications = true)

        assertTrue(
            PermissionRevocationFlow.manualSteps(
                state = state,
                sdkInt = Build.VERSION_CODES.S_V2
            ).isEmpty()
        )
        assertFalse(
            PermissionRevocationFlow.hasRevocablePermissions(
                state = state,
                sdkInt = Build.VERSION_CODES.S_V2
            )
        )
    }

    @Test
    fun hasRevocablePermissions_includesAdministrativeRoleEvenWithoutManualSteps() {
        val state = state(deviceAdmin = true)

        assertTrue(
            PermissionRevocationFlow.hasRevocablePermissions(
                state = state,
                sdkInt = Build.VERSION_CODES.TIRAMISU
            )
        )
    }

    private fun state(
        accessibility: Boolean = false,
        usageAccess: Boolean = false,
        notifications: Boolean = false,
        batteryOptimization: Boolean = false,
        deviceAdmin: Boolean = false
    ) = ProtectionPermissionState(
        selfProtectionConsent = true,
        accessibility = accessibility,
        usageAccess = usageAccess,
        notifications = notifications,
        batteryOptimization = batteryOptimization,
        deviceAdmin = deviceAdmin
    )
}
