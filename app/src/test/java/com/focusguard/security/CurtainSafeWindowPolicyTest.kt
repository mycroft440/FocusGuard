package com.focusguard.security

import com.focusguard.security.CurtainSafeWindowPolicy.Decision
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CurtainSafeWindowPolicyTest {
    @Test
    fun `late target cannot hide curtain before settle window`() {
        assertThat(
            CurtainSafeWindowPolicy.decide(
                settleElapsed = false,
                unsafeWindowVisible = false
            )
        ).isEqualTo(Decision.WAIT_FOR_SETTLE)
        assertThat(
            CurtainSafeWindowPolicy.decide(
                settleElapsed = true,
                unsafeWindowVisible = true
            )
        ).isEqualTo(Decision.KEEP_AND_EVACUATE)
    }

    @Test
    fun `split screen blocked app remains unsafe beside safe activity`() {
        val blocked = setOf("com.example.blocked")
        val settings = setOf("com.android.settings")

        assertThat(
            CurtainSafeWindowPolicy.isUnsafePackage(
                visiblePackage = "com.focusguard.v2",
                focusGuardPackage = "com.focusguard.v2",
                blockedPackages = blocked,
                protectSettings = false,
                protectedSettingsPackages = settings
            )
        ).isFalse()
        assertThat(
            CurtainSafeWindowPolicy.isUnsafePackage(
                visiblePackage = "com.example.blocked",
                focusGuardPackage = "com.focusguard.v2",
                blockedPackages = blocked,
                protectSettings = false,
                protectedSettingsPackages = settings
            )
        ).isTrue()
        assertThat(
            CurtainSafeWindowPolicy.isUnsafePackage(
                visiblePackage = "com.android.settings",
                focusGuardPackage = "com.focusguard.v2",
                blockedPackages = blocked,
                protectSettings = true,
                protectedSettingsPackages = settings
            )
        ).isTrue()
    }
}
