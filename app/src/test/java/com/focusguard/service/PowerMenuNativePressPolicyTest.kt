package com.focusguard.service

import com.focusguard.security.PowerMenuProtectionPolicy.Action
import com.focusguard.service.ProtectedPowerMenuController.NativePressMethod
import com.focusguard.service.ProtectedPowerMenuController.NativePressStep
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PowerMenuNativePressPolicyTest {
    @Test
    fun `power off starts with a real tap and alternates with the accessibility click`() {
        val methods = (0 until ProtectedPowerMenuController.MAX_PRIMARY_PRESS_ATTEMPTS).map {
            ProtectedPowerMenuController.nativePressMethod(Action.POWER_OFF, it)
        }
        assertThat(methods).containsExactly(
            NativePressMethod.TAP,
            NativePressMethod.CLICK,
            NativePressMethod.TAP,
            NativePressMethod.CLICK
        ).inOrder()
    }

    @Test
    fun `other actions start with the accessibility click`() {
        listOf(Action.RESTART, Action.EMERGENCY, Action.MEDICAL_INFO).forEach { action ->
            assertThat(ProtectedPowerMenuController.nativePressMethod(action, 0))
                .isEqualTo(NativePressMethod.CLICK)
            assertThat(ProtectedPowerMenuController.nativePressMethod(action, 1))
                .isEqualTo(NativePressMethod.TAP)
        }
    }

    @Test
    fun `a control that is still on screen is pressed again until attempts run out`() {
        val max = ProtectedPowerMenuController.MAX_PRIMARY_PRESS_ATTEMPTS
        (0 until max).forEach { attempt ->
            assertThat(step(attempt = attempt, controlPresent = true, maxAttempts = max))
                .isEqualTo(NativePressStep.PRESS)
        }
        assertThat(step(attempt = max, controlPresent = true, maxAttempts = max))
            .isEqualTo(NativePressStep.DONE_EXHAUSTED)
    }

    @Test
    fun `a control that disappears after a press means android took over`() {
        assertThat(step(attempt = 1, controlPresent = false))
            .isEqualTo(NativePressStep.DONE_SENT)
        assertThat(step(attempt = 3, controlPresent = false))
            .isEqualTo(NativePressStep.DONE_SENT)
    }

    @Test
    fun `a control never found is looked up once more before giving up`() {
        assertThat(step(attempt = 0, controlPresent = false, lookupRetried = false))
            .isEqualTo(NativePressStep.RETRY_LOOKUP)
        assertThat(step(attempt = 0, controlPresent = false, lookupRetried = true))
            .isEqualTo(NativePressStep.DONE_UNAVAILABLE)
    }

    @Test
    fun `emergency actions are pressed only once`() {
        assertThat(step(attempt = 0, controlPresent = true, maxAttempts = 1))
            .isEqualTo(NativePressStep.PRESS)
        assertThat(step(attempt = 1, controlPresent = true, maxAttempts = 1))
            .isEqualTo(NativePressStep.DONE_EXHAUSTED)
    }

    @Test
    fun `the real button outranks a label and a hint that only mentions the action`() {
        val button = score(visibleToUser = true, exactLabel = true, clickable = true)
        val label = score(visibleToUser = true, exactLabel = true, clickable = false)
        val hint = score(visibleToUser = true, exactLabel = false, clickable = false)
        val clickableHint = score(visibleToUser = true, exactLabel = false, clickable = true)
        val offScreenButton = score(visibleToUser = false, exactLabel = true, clickable = true)

        assertThat(button).isGreaterThan(label)
        assertThat(label).isGreaterThan(clickableHint)
        assertThat(clickableHint).isGreaterThan(hint)
        assertThat(hint).isGreaterThan(offScreenButton)
        assertThat(offScreenButton).isAtLeast(0)
    }

    @Test
    fun `the menu background is never an action target`() {
        val screenWidth = 1080
        val screenHeight = 2400
        fun plausible(width: Int, height: Int) = ProtectedPowerMenuController.isPlausibleActionTarget(
            width = width,
            height = height,
            screenWidth = screenWidth,
            screenHeight = screenHeight
        )

        // Full-screen scrim and a dialog that takes most of the screen.
        assertThat(plausible(screenWidth, screenHeight)).isFalse()
        assertThat(plausible(screenWidth, screenHeight / 2)).isFalse()
        // A round action button and a full-width list row.
        assertThat(plausible(260, 320)).isTrue()
        assertThat(plausible(screenWidth, 180)).isTrue()
        // Unknown bounds or screen size never veto the accessibility click.
        assertThat(plausible(0, 0)).isTrue()
        assertThat(
            ProtectedPowerMenuController.isPlausibleActionTarget(
                width = screenWidth,
                height = screenHeight,
                screenWidth = 0,
                screenHeight = 0
            )
        ).isTrue()
    }

    private fun step(
        attempt: Int,
        controlPresent: Boolean,
        lookupRetried: Boolean = true,
        maxAttempts: Int = ProtectedPowerMenuController.MAX_PRIMARY_PRESS_ATTEMPTS
    ) = ProtectedPowerMenuController.nativePressStep(
        attempt = attempt,
        controlPresent = controlPresent,
        lookupRetried = lookupRetried,
        maxAttempts = maxAttempts
    )

    private fun score(visibleToUser: Boolean, exactLabel: Boolean, clickable: Boolean) =
        ProtectedPowerMenuController.actionNodeScore(
            visibleToUser = visibleToUser,
            exactLabel = exactLabel,
            clickable = clickable
        )
}
