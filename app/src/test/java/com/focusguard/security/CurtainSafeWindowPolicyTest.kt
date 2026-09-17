package com.focusguard.security

import com.focusguard.security.CurtainSafeWindowPolicy.Decision
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test

class CurtainSafeWindowPolicyTest {

    @Before
    fun resetTargetScope() {
        SelfProtectionTargetScope.clear()
    }

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
    fun `blocked app remains unsafe independently of self protection target`() {
        val blocked = setOf("com.example.blocked")
        val settings = setOf("com.android.settings")

        assertThat(
            CurtainSafeWindowPolicy.isUnsafePackage(
                visiblePackage = "com.example.blocked",
                focusGuardPackage = "com.focusguard.v2",
                blockedPackages = blocked,
                protectSettings = false,
                protectedSettingsPackages = settings
            )
        ).isTrue()
    }

    @Test
    fun `generic settings window is safe when FocusGuard target is not confirmed`() {
        assertThat(
            CurtainSafeWindowPolicy.isUnsafePackage(
                visiblePackage = "com.android.settings",
                focusGuardPackage = "com.focusguard.v2",
                blockedPackages = emptySet(),
                protectSettings = true,
                protectedSettingsPackages = setOf("com.android.settings")
            )
        ).isFalse()
    }

    @Test
    fun `settings window remains unsafe only for confirmed FocusGuard target`() {
        SelfProtectionTargetScope.confirmFocusGuardTarget()

        assertThat(
            CurtainSafeWindowPolicy.isUnsafePackage(
                visiblePackage = "com.android.settings",
                focusGuardPackage = "com.focusguard.v2",
                blockedPackages = emptySet(),
                protectSettings = true,
                protectedSettingsPackages = setOf("com.android.settings")
            )
        ).isTrue()

        SelfProtectionTargetScope.clear()

        assertThat(
            CurtainSafeWindowPolicy.isUnsafePackage(
                visiblePackage = "com.android.settings",
                focusGuardPackage = "com.focusguard.v2",
                blockedPackages = emptySet(),
                protectSettings = true,
                protectedSettingsPackages = setOf("com.android.settings")
            )
        ).isFalse()
    }

    @Test
    fun `FocusGuard activity itself is never an unsafe underlying window`() {
        SelfProtectionTargetScope.confirmFocusGuardTarget()

        assertThat(
            CurtainSafeWindowPolicy.isUnsafePackage(
                visiblePackage = "com.focusguard.v2",
                focusGuardPackage = "com.focusguard.v2",
                blockedPackages = emptySet(),
                protectSettings = true,
                protectedSettingsPackages = setOf("com.android.settings")
            )
        ).isFalse()
    }
}
