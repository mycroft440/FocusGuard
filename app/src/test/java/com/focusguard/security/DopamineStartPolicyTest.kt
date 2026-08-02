package com.focusguard.security

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DopamineStartPolicyTest {

    @Test
    fun `accessibility alone starts the simple blocking mode`() {
        val level = DopamineStartPolicy.protectionLevel(
            DopamineStartPolicy.Capabilities(
                accessibilityEnabled = true,
                usageAccessEnabled = false,
                batteryOptimizationExempt = false,
                deviceOwnerActive = false
            )
        )

        assertThat(level).isEqualTo(DopamineStartPolicy.ProtectionLevel.SIMPLE)
    }

    @Test
    fun `device owner automatically upgrades an available block`() {
        val level = DopamineStartPolicy.protectionLevel(
            DopamineStartPolicy.Capabilities(
                accessibilityEnabled = true,
                usageAccessEnabled = true,
                batteryOptimizationExempt = true,
                deviceOwnerActive = true
            )
        )

        assertThat(level).isEqualTo(DopamineStartPolicy.ProtectionLevel.ARMORED)
    }

    @Test
    fun `blocking cannot intercept apps or sites without accessibility`() {
        val level = DopamineStartPolicy.protectionLevel(
            DopamineStartPolicy.Capabilities(
                accessibilityEnabled = false,
                usageAccessEnabled = true,
                batteryOptimizationExempt = true,
                deviceOwnerActive = true
            )
        )

        assertThat(level).isEqualTo(DopamineStartPolicy.ProtectionLevel.UNAVAILABLE)
    }
}
