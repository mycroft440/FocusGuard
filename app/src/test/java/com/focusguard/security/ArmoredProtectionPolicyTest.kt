package com.focusguard.security

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ArmoredProtectionPolicyTest {

    @Test
    fun `armed protection rejects immediate credential maintenance`() {
        val phase = ArmoredProtectionPolicy.phase(
            deviceOwnerActive = true,
            protectionArmed = true,
            maintenanceActive = false
        )

        assertThat(phase).isEqualTo(ArmoredProtectionPolicy.Phase.ARMED)
        assertThat(ArmoredProtectionPolicy.canOpenMaintenanceWithCredential(phase)).isFalse()
    }

    @Test
    fun `completed protection permits credential maintenance`() {
        val phase = ArmoredProtectionPolicy.phase(
            deviceOwnerActive = true,
            protectionArmed = false,
            maintenanceActive = false
        )

        assertThat(phase).isEqualTo(ArmoredProtectionPolicy.Phase.READY)
        assertThat(ArmoredProtectionPolicy.canOpenMaintenanceWithCredential(phase)).isTrue()
    }

    @Test
    fun `device owner renounce requires maintenance phase`() {
        assertThat(
            ArmoredProtectionPolicy.canRenounceDeviceOwner(
                ArmoredProtectionPolicy.Phase.ARMED
            )
        ).isFalse()
        assertThat(
            ArmoredProtectionPolicy.canRenounceDeviceOwner(
                ArmoredProtectionPolicy.Phase.MAINTENANCE
            )
        ).isTrue()
    }
}
