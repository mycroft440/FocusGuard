package com.focusguard.security

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AccessibilityProtectionGateTest {

    @Test
    fun `consumer mode always allows Android accessibility settings`() {
        assertThat(
            AccessibilityProtectionGate.shouldAllowSystemAccessibilitySettings()
        ).isTrue()
    }

    @Test
    fun `consumer mode has no hidden maintenance window`() {
        assertThat(AccessibilityProtectionGate.UNLOCK_DURATION_MILLIS).isEqualTo(0L)
    }
}
