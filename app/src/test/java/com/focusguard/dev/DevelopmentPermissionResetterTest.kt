package com.focusguard.dev

import android.Manifest
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DevelopmentPermissionResetterTest {

    @Test
    fun `broadcast request alone does not count as protection disarmed`() {
        val result = completeResult(
            accessibilityRelinquishRequested = true,
            accessibilityDisabled = false
        )

        assertFalse(result.coreProtectionDisarmed)
    }

    @Test
    fun `persistent focus mode must be stopped before protection counts as disarmed`() {
        val result = completeResult(focusModeStopped = false)

        assertFalse(result.coreProtectionDisarmed)
    }

    @Test
    fun `verified reset counts as core protection disarmed`() {
        assertTrue(completeResult().coreProtectionDisarmed)
    }

    @Test
    fun `runtime permission list matches dangerous manifest permissions by sdk`() {
        assertEquals(
            listOf(Manifest.permission.CAMERA),
            DevelopmentPermissionResetter.runtimePermissionsForSdk(Build.VERSION_CODES.S_V2)
        )
        assertEquals(
            listOf(Manifest.permission.CAMERA, Manifest.permission.POST_NOTIFICATIONS),
            DevelopmentPermissionResetter.runtimePermissionsForSdk(Build.VERSION_CODES.TIRAMISU)
        )
    }

    private fun completeResult(
        blocksRemoved: Boolean = true,
        focusModeStopped: Boolean = true,
        administrativeRolesReleased: Boolean = true,
        accessibilityRelinquishRequested: Boolean = true,
        accessibilityDisabled: Boolean = true
    ) = DevelopmentPermissionResetter.Result(
        blocksRemoved = blocksRemoved,
        focusModeStopped = focusModeStopped,
        administrativeRolesReleased = administrativeRolesReleased,
        accessibilityRelinquishRequested = accessibilityRelinquishRequested,
        accessibilityDisabled = accessibilityDisabled,
        runtimeRevocationScheduled = false
    )
}
