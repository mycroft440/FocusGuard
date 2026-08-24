package com.focusguard.service

import android.view.WindowManager
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OverlayWindowStateTest {

    @Test
    fun `hidden instant curtain stays attached but cannot consume touches`() {
        val base = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        val hidden = BlockingAccessibilityService.hiddenOverlayFlags(base)
        val visible = BlockingAccessibilityService.visibleOverlayFlags(hidden)

        assertThat(hidden and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE).isNotEqualTo(0)
        assertThat(hidden and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE).isNotEqualTo(0)
        assertThat(visible and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE).isEqualTo(0)
        assertThat(visible and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE).isNotEqualTo(0)
    }

    @Test
    fun `hidden power overlay is inert and visible power overlay is interactive`() {
        val base = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        val hidden = ProtectedPowerMenuController.hiddenFlags(base)
        val visible = ProtectedPowerMenuController.visibleFlags(hidden)

        assertThat(hidden and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE).isNotEqualTo(0)
        assertThat(hidden and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE).isNotEqualTo(0)
        assertThat(visible and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE).isEqualTo(0)
        assertThat(visible and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE).isEqualTo(0)
    }

    @Test
    fun `power overlay closes only after confirmed absence or staged evacuation`() {
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
                presence =
                    ProtectedPowerMenuController.PowerMenuPresence.ABSENT_CONFIRMED,
                visibleForMillis = 700L,
                closeStage = ProtectedPowerMenuController.CloseStage.NONE,
                closeStageForMillis = 0L,
                unconfirmedSignalGraceExpired = false
            )
        ).isEqualTo(ProtectedPowerMenuController.RecheckDecision.HIDE)
        assertThat(
            ProtectedPowerMenuController.recheckDecision(
                overlayVisible = true,
                presence = ProtectedPowerMenuController.PowerMenuPresence.PRESENT,
                visibleForMillis = ProtectedPowerMenuController.MAX_OVERLAY_VISIBLE_MILLIS,
                closeStage = ProtectedPowerMenuController.CloseStage.NONE,
                closeStageForMillis = 0L,
                unconfirmedSignalGraceExpired = false
            )
        ).isEqualTo(
            ProtectedPowerMenuController.RecheckDecision.REQUEST_BACK
        )
    }

    @Test
    fun `undefined window expiry requests back while overlay remains`() {
        assertThat(
            ProtectedPowerMenuController.recheckDecision(
                overlayVisible = true,
                presence = ProtectedPowerMenuController.PowerMenuPresence.UNKNOWN,
                visibleForMillis = ProtectedPowerMenuController.UNDEFINED_WINDOW_GRACE_MILLIS,
                closeStage = ProtectedPowerMenuController.CloseStage.NONE,
                closeStageForMillis = 0L,
                unconfirmedSignalGraceExpired = true
            )
        ).isEqualTo(
            ProtectedPowerMenuController.RecheckDecision.REQUEST_BACK
        )
    }

    @Test
    fun `ignored back escalates and a proven native window stays covered`() {
        assertThat(
            ProtectedPowerMenuController.recheckDecision(
                overlayVisible = true,
                presence = ProtectedPowerMenuController.PowerMenuPresence.PRESENT,
                visibleForMillis = 5_000L,
                closeStage = ProtectedPowerMenuController.CloseStage.BACK_REQUESTED,
                closeStageForMillis = ProtectedPowerMenuController.BACK_TO_HOME_MILLIS,
                unconfirmedSignalGraceExpired = false
            )
        ).isEqualTo(ProtectedPowerMenuController.RecheckDecision.REQUEST_HOME)
        assertThat(
            ProtectedPowerMenuController.recheckDecision(
                overlayVisible = true,
                presence = ProtectedPowerMenuController.PowerMenuPresence.PRESENT,
                visibleForMillis = 6_000L,
                closeStage = ProtectedPowerMenuController.CloseStage.HOME_REQUESTED,
                closeStageForMillis =
                    ProtectedPowerMenuController.HOME_CLOSE_HARD_CAP_MILLIS,
                unconfirmedSignalGraceExpired = false
            )
        ).isEqualTo(ProtectedPowerMenuController.RecheckDecision.REQUEST_HOME)
        assertThat(
            ProtectedPowerMenuController.recheckDecision(
                overlayVisible = true,
                presence = ProtectedPowerMenuController.PowerMenuPresence.UNKNOWN,
                visibleForMillis = 6_000L,
                closeStage = ProtectedPowerMenuController.CloseStage.HOME_REQUESTED,
                closeStageForMillis =
                    ProtectedPowerMenuController.HOME_CLOSE_HARD_CAP_MILLIS,
                homeFallbackAttempted = false,
                unconfirmedSignalGraceExpired = false
            )
        ).isEqualTo(ProtectedPowerMenuController.RecheckDecision.REQUEST_HOME)
        assertThat(
            ProtectedPowerMenuController.recheckDecision(
                overlayVisible = true,
                presence = ProtectedPowerMenuController.PowerMenuPresence.UNKNOWN,
                visibleForMillis = 7_050L,
                closeStage = ProtectedPowerMenuController.CloseStage.HOME_REQUESTED,
                closeStageForMillis =
                    ProtectedPowerMenuController.HOME_CLOSE_HARD_CAP_MILLIS,
                homeFallbackAttempted = true,
                unconfirmedSignalGraceExpired = false
            )
        ).isEqualTo(ProtectedPowerMenuController.RecheckDecision.HIDE)
    }

    @Test
    fun `accepted home with persistent native window also uses launcher fallback`() {
        assertThat(
            ProtectedPowerMenuController.shouldLaunchHomeIntentFallback(
                globalHomeAccepted = true,
                retryingPersistentWindow = true
            )
        ).isTrue()
        assertThat(
            ProtectedPowerMenuController.shouldLaunchHomeIntentFallback(
                globalHomeAccepted = true,
                retryingPersistentWindow = false
            )
        ).isFalse()
    }

    @Test
    fun `failed launcher fallback keeps unknown power menu covered for retry`() {
        val attempted = ProtectedPowerMenuController.shouldMarkHomeFallbackAttempted(
            fallbackIntentSucceeded = false
        )
        assertThat(attempted).isFalse()
        assertThat(
            ProtectedPowerMenuController.recheckDecision(
                overlayVisible = true,
                presence = ProtectedPowerMenuController.PowerMenuPresence.UNKNOWN,
                visibleForMillis = 8_000L,
                closeStage = ProtectedPowerMenuController.CloseStage.HOME_REQUESTED,
                closeStageForMillis =
                    ProtectedPowerMenuController.HOME_CLOSE_HARD_CAP_MILLIS,
                homeFallbackAttempted = attempted,
                unconfirmedSignalGraceExpired = false
            )
        ).isEqualTo(ProtectedPowerMenuController.RecheckDecision.REQUEST_HOME)
    }

    @Test
    fun `blank package inspects only the exact power window`() {
        assertThat(
            ProtectedPowerMenuController.shouldInspectExactPowerWindow(
                packageName = "",
                relevantEvent = true
            )
        ).isTrue()
        assertThat(
            ProtectedPowerMenuController.shouldInspectExactPowerWindow(
                packageName = "com.example.app",
                relevantEvent = true
            )
        ).isFalse()
        assertThat(
            ProtectedPowerMenuController.shouldInspectExactPowerWindow(
                packageName = "",
                relevantEvent = false
            )
        ).isFalse()
    }

    @Test
    fun `matched power menu without a drawn overlay closes instead of consuming`() {
        assertThat(
            ProtectedPowerMenuController.powerMatchOverlayDecision(
                powerMatched = true,
                overlayShown = false
            )
        ).isEqualTo(
            ProtectedPowerMenuController.PowerMatchOverlayDecision.REQUEST_HOME_FALLBACK
        )
        assertThat(
            ProtectedPowerMenuController.powerMatchOverlayDecision(
                powerMatched = true,
                overlayShown = true
            )
        ).isEqualTo(
            ProtectedPowerMenuController.PowerMatchOverlayDecision.SHIELD_AND_CONSUME
        )
    }

    @Test
    fun `cancel back request cannot dismiss on its old 120ms timer`() {
        assertThat(
            ProtectedPowerMenuController.recheckDecision(
                overlayVisible = true,
                presence = ProtectedPowerMenuController.PowerMenuPresence.PRESENT,
                visibleForMillis = 120L,
                closeStage = ProtectedPowerMenuController.CloseStage.BACK_REQUESTED,
                closeStageForMillis = 120L,
                unconfirmedSignalGraceExpired = false
            )
        ).isEqualTo(
            ProtectedPowerMenuController.RecheckDecision.KEEP_CHECKING
        )
    }

    @Test
    fun `system ui event storm cannot postpone an already scheduled recheck`() {
        assertThat(
            ProtectedPowerMenuController.shouldScheduleRecheck(alreadyScheduled = false)
        ).isTrue()
        repeat(1_000) {
            assertThat(
                ProtectedPowerMenuController.shouldScheduleRecheck(alreadyScheduled = true)
            ).isFalse()
        }
    }

    @Test
    fun `external window cannot uncover a still present native power menu`() {
        assertThat(ProtectedPowerMenuController.shouldConsumeExternalWindowEvent()).isFalse()
        assertThat(
            ProtectedPowerMenuController.recheckDecision(
                overlayVisible = true,
                presence = ProtectedPowerMenuController.PowerMenuPresence.PRESENT,
                visibleForMillis = 700L,
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
    fun `accessibility feedback interrupt rechecks instead of hiding power overlay`() {
        assertThat(
            ProtectedPowerMenuController.shouldRecheckAfterFeedbackInterrupt(
                overlayVisible = true
            )
        ).isTrue()
        assertThat(
            ProtectedPowerMenuController.shouldRecheckAfterFeedbackInterrupt(
                overlayVisible = false
            )
        ).isFalse()
    }

    @Test
    fun `screen off requests native close without uncovering power menu`() {
        assertThat(
            ProtectedPowerMenuController.shouldRequestCloseOnScreenOff(
                overlayVisible = true
            )
        ).isTrue()
        assertThat(
            ProtectedPowerMenuController.shouldRequestCloseOnScreenOff(
                overlayVisible = false
            )
        ).isFalse()
    }
}
