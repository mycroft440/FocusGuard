package com.focusguard.admin

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DeviceOwnerProtectionDiagnosticsTest {

    @Test
    fun `full protection requires verified device owner policies`() {
        val diagnostics = protectedDiagnostics()

        assertThat(diagnostics.isFullyProtected).isTrue()
        assertThat(diagnostics.failedChecks).isEqualTo(0)
    }

    @Test
    fun `legacy device admin is never reported as fully protected`() {
        val diagnostics = protectedDiagnostics().copy(
            deviceOwnerActive = false,
            uninstallBlocked = false,
            factoryResetBlocked = false
        )

        assertThat(diagnostics.isFullyProtected).isFalse()
        assertThat(diagnostics.failedChecks).isEqualTo(3)
    }

    @Test
    fun `maintenance is intentionally not reported as full protection`() {
        val diagnostics = protectedDiagnostics().copy(
            maintenanceActive = true,
            uninstallBlocked = false,
            appsControlBlocked = false,
            userControlDisabled = false,
            grantAdminBlocked = false,
            privateDnsChangesBlocked = false,
            vpnConfigurationBlocked = false
        )

        assertThat(diagnostics.isFullyProtected).isFalse()
        assertThat(diagnostics.failedChecks).isEqualTo(6)
    }

    @Test
    fun `unsupported optional checks do not create false failure`() {
        val diagnostics = protectedDiagnostics().copy(
            userControlDisabled = null,
            grantAdminBlocked = null,
            automaticTimeEnabled = null,
            automaticTimeZoneEnabled = null
        )

        assertThat(diagnostics.isFullyProtected).isTrue()
        assertThat(diagnostics.failedChecks).isEqualTo(0)
    }

    @Test
    fun `a failed supported API query can never report full protection`() {
        val diagnostics = protectedDiagnostics().copy(
            userControlDisabled = false,
            automaticTimeEnabled = false,
            automaticTimeZoneEnabled = false
        )

        assertThat(diagnostics.isFullyProtected).isFalse()
        assertThat(diagnostics.failedChecks).isEqualTo(3)
    }

    @Test
    fun `enabled adult filter requires verified dns and settings lock`() {
        val diagnostics = protectedDiagnostics().copy(
            adultDnsEnforced = false,
            privateDnsChangesBlocked = false,
            vpnConfigurationBlocked = false
        )

        assertThat(diagnostics.isFullyProtected).isFalse()
        assertThat(diagnostics.failedChecks).isEqualTo(3)
    }

    private fun protectedDiagnostics() = DeviceOwnerProtectionDiagnostics(
        deviceAdminActive = true,
        deviceOwnerActive = true,
        maintenanceActive = false,
        uninstallBlocked = true,
        appsControlBlocked = true,
        userControlDisabled = true,
        factoryResetBlocked = true,
        safeBootBlocked = true,
        dateTimeChangesBlocked = true,
        grantAdminBlocked = true,
        automaticTimeEnabled = true,
        automaticTimeZoneEnabled = true,
        adultFilterEnabled = true,
        adultDnsEnforced = true,
        privateDnsChangesBlocked = true,
        vpnConfigurationBlocked = true
    )
}
