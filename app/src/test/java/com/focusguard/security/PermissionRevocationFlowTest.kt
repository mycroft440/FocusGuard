package com.focusguard.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionRevocationFlowTest {

    @Test
    fun legacyDeviceAdmin_isTargetedOnlyWhenDeviceOwnerIsNotActive() {
        assertTrue(
            PermissionRevocationFlow.isLegacyDeviceAdminActive(
                deviceAdminActive = true,
                deviceOwnerActive = false
            )
        )
        assertFalse(
            PermissionRevocationFlow.isLegacyDeviceAdminActive(
                deviceAdminActive = true,
                deviceOwnerActive = true
            )
        )
        assertFalse(
            PermissionRevocationFlow.isLegacyDeviceAdminActive(
                deviceAdminActive = false,
                deviceOwnerActive = false
            )
        )
    }

    @Test
    fun result_requiresOnlyInitiallyActiveRequestedAccessToBeRevoked() {
        val complete = PermissionRevocationResult(
            accessibilityWasActive = true,
            accessibilityRevoked = true,
            deviceAdminWasActive = false,
            deviceAdminRevoked = true
        )
        val incomplete = complete.copy(accessibilityRevoked = false)

        assertTrue(complete.hadRequestedAccess)
        assertTrue(complete.allRequestedAccessRevoked)
        assertFalse(incomplete.allRequestedAccessRevoked)
    }

    @Test
    fun result_reportsNoRequestedAccessWhenBothTargetsWereAlreadyDisabled() {
        val result = PermissionRevocationResult(
            accessibilityWasActive = false,
            accessibilityRevoked = true,
            deviceAdminWasActive = false,
            deviceAdminRevoked = true
        )

        assertFalse(result.hadRequestedAccess)
        assertTrue(result.allRequestedAccessRevoked)
    }
}
