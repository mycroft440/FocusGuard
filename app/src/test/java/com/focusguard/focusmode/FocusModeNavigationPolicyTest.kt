package com.focusguard.focusmode

import com.focusguard.focusmode.FocusModePolicy.NavigationKeyDecision
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FocusModeNavigationPolicyTest {

    @Test
    fun `back or home down outside FocusGuard immediately returns to hardblock`() {
        val decision = FocusModePolicy.focusNavigationKeyDecision(
            focusModeActive = true,
            focusGuardForeground = false,
            powerMenuVisible = false,
            isBackOrHomeKey = true,
            actionDown = true,
            repeatCount = 0
        )

        assertThat(decision).isEqualTo(NavigationKeyDecision.RETURN_TO_FOCUS_GUARD)
    }

    @Test
    fun `remaining events from intercepted navigation key are consumed`() {
        assertThat(
            FocusModePolicy.focusNavigationKeyDecision(
                focusModeActive = true,
                focusGuardForeground = false,
                powerMenuVisible = false,
                isBackOrHomeKey = true,
                actionDown = false,
                repeatCount = 0
            )
        ).isEqualTo(NavigationKeyDecision.CONSUME)

        assertThat(
            FocusModePolicy.focusNavigationKeyDecision(
                focusModeActive = true,
                focusGuardForeground = false,
                powerMenuVisible = false,
                isBackOrHomeKey = true,
                actionDown = true,
                repeatCount = 2
            )
        ).isEqualTo(NavigationKeyDecision.CONSUME)
    }

    @Test
    fun `power menu keeps navigation keys while visible`() {
        val decision = FocusModePolicy.focusNavigationKeyDecision(
            focusModeActive = true,
            focusGuardForeground = false,
            powerMenuVisible = true,
            isBackOrHomeKey = true,
            actionDown = true,
            repeatCount = 0
        )

        assertThat(decision).isEqualTo(NavigationKeyDecision.PASS)
    }

    @Test
    fun `focusguard itself keeps ownership of back handling`() {
        val decision = FocusModePolicy.focusNavigationKeyDecision(
            focusModeActive = true,
            focusGuardForeground = true,
            powerMenuVisible = false,
            isBackOrHomeKey = true,
            actionDown = true,
            repeatCount = 0
        )

        assertThat(decision).isEqualTo(NavigationKeyDecision.PASS)
    }

    @Test
    fun `inactive focus mode never consumes navigation`() {
        val decision = FocusModePolicy.focusNavigationKeyDecision(
            focusModeActive = false,
            focusGuardForeground = false,
            powerMenuVisible = false,
            isBackOrHomeKey = true,
            actionDown = true,
            repeatCount = 0
        )

        assertThat(decision).isEqualTo(NavigationKeyDecision.PASS)
    }

    @Test
    fun `launcher returns to focusguard even on native device owner path`() {
        assertThat(
            FocusModePolicy.shouldRedirectToFocusGuard(
                focusModeFallbackActive = false,
                foregroundPackage = "com.launcher",
                focusGuardPackage = "com.focusguard",
                launcherPackage = "com.launcher",
                focusModeBlockedPackages = emptySet(),
                focusModeActive = true
            )
        ).isTrue()
    }

    @Test
    fun `consumer fallback still redirects blocked apps`() {
        assertThat(
            FocusModePolicy.shouldRedirectToFocusGuard(
                focusModeFallbackActive = true,
                foregroundPackage = "com.social",
                focusGuardPackage = "com.focusguard",
                launcherPackage = "com.launcher",
                focusModeBlockedPackages = setOf("com.social"),
                focusModeActive = true
            )
        ).isTrue()
    }
}
