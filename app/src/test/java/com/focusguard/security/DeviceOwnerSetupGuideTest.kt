package com.focusguard.security

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DeviceOwnerSetupGuideTest {

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
}
