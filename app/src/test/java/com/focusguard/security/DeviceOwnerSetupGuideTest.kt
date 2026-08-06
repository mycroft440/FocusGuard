package com.focusguard.security

import com.focusguard.security.DeviceOwnerSetupGuide.Requirement
import com.focusguard.security.DeviceOwnerSetupGuide.Status
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DeviceOwnerSetupGuideTest {

    private fun environment(
        runningOnMainUser: Boolean = true,
        usbDebuggingEnabled: Boolean = true,
        setupWizardCompleted: Boolean = false
    ) = DeviceOwnerSetupGuide.Environment(
        runningOnMainUser = runningOnMainUser,
        usbDebuggingEnabled = usbDebuggingEnabled,
        setupWizardCompleted = setupWizardCompleted
    )

    private fun statusOf(
        environment: DeviceOwnerSetupGuide.Environment,
        requirement: Requirement
    ): Status = DeviceOwnerSetupGuide.inspect(environment)
        .first { it.requirement == requirement }
        .status

    @Test
    fun `command uses the installed application id and the declared receiver`() {
        assertThat(DeviceOwnerSetupGuide.buildAdbCommand("com.focusguard.v2"))
            .isEqualTo(
                "adb shell dpm set-device-owner " +
                    "com.focusguard.v2/com.focusguard.admin.FocusGuardDeviceAdminReceiver"
            )
    }

    @Test
    fun `debug builds use their actual application id`() {
        assertThat(DeviceOwnerSetupGuide.buildAdbCommand("com.focusguard.v2.debug"))
            .startsWith("adb shell dpm set-device-owner com.focusguard.v2.debug/")
    }

    @Test
    fun `every requirement gets a row, always in the same order`() {
        // The dialog renders whatever comes back, so a new requirement must not be
        // silently dropped from the checklist.
        val requirements = DeviceOwnerSetupGuide.inspect(environment()).map { it.requirement }

        assertThat(requirements).containsExactlyElementsIn(Requirement.entries).inOrder()
    }

    @Test
    fun `a device still in its first setup has nothing pending`() {
        val checks = DeviceOwnerSetupGuide.inspect(environment())

        assertThat(checks.map { it.status }).containsExactly(
            Status.READY,
            Status.READY,
            Status.READY
        )
    }

    @Test
    fun `a secondary user blocks the command`() {
        // The command provisions the main user's install, not the one on screen.
        val secondaryUser = environment(runningOnMainUser = false)

        assertThat(statusOf(secondaryUser, Requirement.MAIN_USER)).isEqualTo(Status.BLOCKING)
        assertThat(DeviceOwnerSetupGuide.hasBlockingCondition(secondaryUser)).isTrue()
    }

    @Test
    fun `usb debugging off is pending, not blocking`() {
        // It is off by default and the person can fix it in a few taps.
        val noDebugging = environment(usbDebuggingEnabled = false)

        assertThat(statusOf(noDebugging, Requirement.USB_DEBUGGING)).isEqualTo(Status.PENDING)
        assertThat(DeviceOwnerSetupGuide.hasBlockingCondition(noDebugging)).isFalse()
    }

    @Test
    fun `a device past its setup wizard is uncertain, never blocked`() {
        // Plenty of models still accept the command once every account is gone;
        // calling it blocked would send those people into a needless factory reset.
        val provisioned = environment(setupWizardCompleted = true)

        assertThat(statusOf(provisioned, Requirement.FRESH_DEVICE)).isEqualTo(Status.UNCERTAIN)
        assertThat(DeviceOwnerSetupGuide.hasBlockingCondition(provisioned)).isFalse()
    }
}
