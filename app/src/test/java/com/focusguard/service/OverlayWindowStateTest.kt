package com.focusguard.service

import android.view.WindowManager
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OverlayWindowStateTest {

    @Test
    fun `hidden overlays are inert and visible overlays remain touchable without key focus`() {
        val base = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        val hiddenCurtain = BlockingAccessibilityService.hiddenOverlayFlags(base)
        val visibleCurtain = BlockingAccessibilityService.visibleOverlayFlags(hiddenCurtain)
        val hiddenPower = ProtectedPowerMenuController.hiddenFlags(base)
        val visiblePower = ProtectedPowerMenuController.visibleFlags(hiddenPower)

        assertThat(hiddenCurtain and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE).isNotEqualTo(0)
        assertThat(hiddenCurtain and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE).isNotEqualTo(0)
        assertThat(visibleCurtain and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE).isEqualTo(0)
        assertThat(visibleCurtain and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE).isNotEqualTo(0)
        assertThat(hiddenPower and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE).isNotEqualTo(0)
        assertThat(visiblePower and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE).isEqualTo(0)
        assertThat(visiblePower and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE).isNotEqualTo(0)
    }

    @Test
    fun `confirmed absence hides while a present menu stays shielded`() {
        assertThat(
            ProtectedPowerMenuController.recheckDecision(
                overlayVisible = true,
                presence = ProtectedPowerMenuController.PowerMenuPresence.PRESENT,
                visibleForMillis = 350L,
                closeStage = ProtectedPowerMenuController.CloseStage.NONE,
                closeStageForMillis = 0L,
                unconfirmedSignalGraceExpired = false
            )
        ).isEqualTo(ProtectedPowerMenuController.RecheckDecision.KEEP_CHECKING)
        assertThat(
            ProtectedPowerMenuController.recheckDecision(
                overlayVisible = true,
                presence = ProtectedPowerMenuController.PowerMenuPresence.ABSENT_CONFIRMED,
                visibleForMillis = 700L,
                closeStage = ProtectedPowerMenuController.CloseStage.NONE,
                closeStageForMillis = 0L,
                unconfirmedSignalGraceExpired = false
            )
        ).isEqualTo(ProtectedPowerMenuController.RecheckDecision.HIDE)
    }

    @Test
    fun `undefined false positive hides without any navigation`() {
        assertThat(
            ProtectedPowerMenuController.recheckDecision(
                overlayVisible = true,
                presence = ProtectedPowerMenuController.PowerMenuPresence.UNKNOWN,
                visibleForMillis = ProtectedPowerMenuController.UNDEFINED_WINDOW_GRACE_MILLIS,
                closeStage = ProtectedPowerMenuController.CloseStage.NONE,
                closeStageForMillis = 0L,
                unconfirmedSignalGraceExpired = true
            )
        ).isEqualTo(ProtectedPowerMenuController.RecheckDecision.HIDE)
    }

    @Test
    fun `automatic close may retry back but never escalates to home`() {
        assertThat(
            ProtectedPowerMenuController.recheckDecision(
                overlayVisible = true,
                presence = ProtectedPowerMenuController.PowerMenuPresence.PRESENT,
                visibleForMillis = 5_000L,
                closeStage = ProtectedPowerMenuController.CloseStage.BACK_REQUESTED,
                closeStageForMillis = ProtectedPowerMenuController.BACK_RETRY_MILLIS,
                closeBackAttempts = 1,
                unconfirmedSignalGraceExpired = false
            )
        ).isEqualTo(ProtectedPowerMenuController.RecheckDecision.REQUEST_BACK)
        assertThat(
            ProtectedPowerMenuController.recheckDecision(
                overlayVisible = true,
                presence = ProtectedPowerMenuController.PowerMenuPresence.PRESENT,
                visibleForMillis = 7_000L,
                closeStage = ProtectedPowerMenuController.CloseStage.BACK_REQUESTED,
                closeStageForMillis = 4_000L,
                closeBackAttempts = ProtectedPowerMenuController.MAX_AUTOMATIC_BACK_ATTEMPTS,
                unconfirmedSignalGraceExpired = false
            )
        ).isEqualTo(ProtectedPowerMenuController.RecheckDecision.KEEP_CHECKING)
    }

    @Test
    fun `matched power menu without overlay falls back to back not home`() {
        assertThat(
            ProtectedPowerMenuController.powerMatchOverlayDecision(
                powerMatched = true,
                overlayShown = false
            )
        ).isEqualTo(ProtectedPowerMenuController.PowerMatchOverlayDecision.REQUEST_BACK_FALLBACK)
        assertThat(
            ProtectedPowerMenuController.powerMatchOverlayDecision(
                powerMatched = true,
                overlayShown = true
            )
        ).isEqualTo(ProtectedPowerMenuController.PowerMatchOverlayDecision.SHIELD_AND_CONSUME)
    }

    @Test
    fun `screen off keeps the shield and pauses rechecks until screen on`() {
        assertThat(ProtectedPowerMenuController.shouldKeepPowerOverlayOnScreenOff(true)).isTrue()
        assertThat(ProtectedPowerMenuController.shouldKeepPowerOverlayOnScreenOff(false)).isFalse()
        assertThat(ProtectedPowerMenuController.shouldScheduleRecheck(false, screenOff = true)).isFalse()
        assertThat(ProtectedPowerMenuController.shouldRecheckPowerOverlayOnScreenOn(true)).isTrue()
        assertThat(ProtectedPowerMenuController.shouldRecheckPowerOverlayOnScreenOn(false)).isFalse()
    }

    @Test
    fun `system ui event storm cannot postpone an already scheduled recheck`() {
        assertThat(ProtectedPowerMenuController.shouldScheduleRecheck(false)).isTrue()
        repeat(1_000) {
            assertThat(ProtectedPowerMenuController.shouldScheduleRecheck(true)).isFalse()
        }
    }

    @Test
    fun `blank package inspects only the exact candidate window`() {
        assertThat(
            ProtectedPowerMenuController.shouldInspectExactPowerWindow("", relevantEvent = true)
        ).isTrue()
        assertThat(
            ProtectedPowerMenuController.shouldInspectExactPowerWindow(
                "com.example.app",
                relevantEvent = true
            )
        ).isFalse()
    }

    @Test
    fun `external window does not get consumed by the power shield`() {
        assertThat(ProtectedPowerMenuController.shouldConsumeExternalWindowEvent()).isFalse()
    }

    @Test
    fun `feedback interrupt rechecks only while overlay is visible`() {
        assertThat(ProtectedPowerMenuController.shouldRecheckAfterFeedbackInterrupt(true)).isTrue()
        assertThat(ProtectedPowerMenuController.shouldRecheckAfterFeedbackInterrupt(false)).isFalse()
    }
}
