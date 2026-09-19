package com.focusguard.service

import android.view.accessibility.AccessibilityEvent
import com.focusguard.service.BlockingAccessibilityService.WebsiteBlockTransitionGuard
import com.focusguard.accessibility.website.redirection.WebsiteRedirectionCoordinator
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ExternalRedirectNeutralizationTest {

    @Test
    fun `external Google window confirms after guarded rebind without close proof`() {
        val guard = WebsiteBlockTransitionGuard()
        val transition = guard.tryStart(
            browserPackageName = FIREFOX_PACKAGE,
            transitionId = 1L,
            destination = WebsiteRedirectionCoordinator.TerminalDestination.REDIRECT,
            expectedWindowId = BLOCKED_WINDOW_ID,
            inspectionGeneration = 1L,
            blockedCandidate = "https://blocked.example/path",
            blockedRules = setOf("blocked.example"),
            detectionEventUptimeMillis = 100L
        )!!

        assertThat(
            guard.markExternalRedirectRequested(
                browserPackageName = FIREFOX_PACKAGE,
                transitionId = transition.id,
                requestedAtUptimeMillis = 110L
            )
        ).isTrue()
        assertThat(
            guard.rebindExternalRedirectWindow(
                browserPackageName = FIREFOX_PACKAGE,
                transitionId = transition.id,
                windowId = SAFE_WINDOW_ID,
                inspectionGeneration = 2L,
                eventUptimeMillis = 120L
            )
        ).isTrue()

        guard.observeBrowserEvent(
            browserPackageName = FIREFOX_PACKAGE,
            windowId = SAFE_WINDOW_ID,
            eventUptimeMillis = 130L,
            eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        )

        assertThat(
            guard.confirmRedirect(
                browserPackageName = FIREFOX_PACKAGE,
                windowId = SAFE_WINDOW_ID,
                eventUptimeMillis = 130L
            )
        ).isTrue()
        assertThat(transition.safeRedirectConfirmed.isCompleted).isTrue()
    }

    @Test
    fun `external Google window also confirms when original tab close was recorded`() {
        val guard = WebsiteBlockTransitionGuard()
        val transition = guard.tryStart(
            browserPackageName = FIREFOX_PACKAGE,
            transitionId = 2L,
            destination = WebsiteRedirectionCoordinator.TerminalDestination.REDIRECT,
            expectedWindowId = BLOCKED_WINDOW_ID,
            inspectionGeneration = 1L,
            blockedCandidate = "https://blocked.example/path",
            blockedRules = setOf("blocked.example"),
            detectionEventUptimeMillis = 100L
        )!!

        assertThat(guard.markCloseClicked(FIREFOX_PACKAGE, transition.id, 105L)).isTrue()
        assertThat(guard.markCloseConfirmed(FIREFOX_PACKAGE, transition.id, 106L)).isTrue()
        assertThat(
            guard.markExternalRedirectRequested(
                browserPackageName = FIREFOX_PACKAGE,
                transitionId = transition.id,
                requestedAtUptimeMillis = 110L
            )
        ).isTrue()
        assertThat(
            guard.rebindExternalRedirectWindow(
                browserPackageName = FIREFOX_PACKAGE,
                transitionId = transition.id,
                windowId = SAFE_WINDOW_ID,
                inspectionGeneration = 2L,
                eventUptimeMillis = 120L
            )
        ).isTrue()

        guard.observeBrowserEvent(
            browserPackageName = FIREFOX_PACKAGE,
            windowId = SAFE_WINDOW_ID,
            eventUptimeMillis = 130L,
            eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        )

        assertThat(
            guard.confirmRedirect(
                browserPackageName = FIREFOX_PACKAGE,
                windowId = SAFE_WINDOW_ID,
                eventUptimeMillis = 130L
            )
        ).isTrue()
        assertThat(transition.safeRedirectConfirmed.isCompleted).isTrue()
    }

    @Test
    fun `same-tab Google confirmation remains valid without external close proof`() {
        val guard = WebsiteBlockTransitionGuard()
        val transition = guard.tryStart(
            browserPackageName = FIREFOX_PACKAGE,
            transitionId = 3L,
            destination = WebsiteRedirectionCoordinator.TerminalDestination.REDIRECT,
            expectedWindowId = BLOCKED_WINDOW_ID,
            inspectionGeneration = 1L,
            blockedCandidate = "https://blocked.example/path",
            blockedRules = setOf("blocked.example"),
            detectionEventUptimeMillis = 100L
        )!!

        assertThat(
            guard.markSanitizationRequested(
                browserPackageName = FIREFOX_PACKAGE,
                transitionId = transition.id,
                requestedAtUptimeMillis = 110L
            )
        ).isTrue()
        guard.observeBrowserEvent(
            browserPackageName = FIREFOX_PACKAGE,
            windowId = BLOCKED_WINDOW_ID,
            eventUptimeMillis = 120L,
            eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        )

        assertThat(
            guard.confirmRedirect(
                browserPackageName = FIREFOX_PACKAGE,
                windowId = BLOCKED_WINDOW_ID,
                eventUptimeMillis = 120L
            )
        ).isTrue()
        assertThat(transition.safeRedirectConfirmed.isCompleted).isTrue()
    }


    @Test
    fun `external Google window cannot confirm before transition is rebound`() {
        val guard = WebsiteBlockTransitionGuard()
        val transition = guard.tryStart(
            browserPackageName = FIREFOX_PACKAGE,
            transitionId = 4L,
            destination = WebsiteRedirectionCoordinator.TerminalDestination.REDIRECT,
            expectedWindowId = BLOCKED_WINDOW_ID,
            inspectionGeneration = 1L,
            blockedCandidate = "https://blocked.example/path",
            blockedRules = setOf("blocked.example"),
            detectionEventUptimeMillis = 100L
        )!!

        assertThat(
            guard.markExternalRedirectRequested(
                browserPackageName = FIREFOX_PACKAGE,
                transitionId = transition.id,
                requestedAtUptimeMillis = 110L
            )
        ).isTrue()
        guard.observeBrowserEvent(
            browserPackageName = FIREFOX_PACKAGE,
            windowId = SAFE_WINDOW_ID,
            eventUptimeMillis = 120L,
            eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        )

        assertThat(
            guard.confirmRedirect(
                browserPackageName = FIREFOX_PACKAGE,
                windowId = SAFE_WINDOW_ID,
                eventUptimeMillis = 120L
            )
        ).isFalse()
        assertThat(transition.safeRedirectConfirmed.isCompleted).isFalse()
    }

    @Test
    fun `stable external Google surface confirms after guarded rebind`() {
        val guard = WebsiteBlockTransitionGuard()
        val transition = guard.tryStart(
            browserPackageName = FIREFOX_PACKAGE,
            transitionId = 5L,
            destination = WebsiteRedirectionCoordinator.TerminalDestination.REDIRECT,
            expectedWindowId = BLOCKED_WINDOW_ID,
            inspectionGeneration = 1L,
            blockedCandidate = "https://blocked.example/path",
            blockedRules = setOf("blocked.example"),
            detectionEventUptimeMillis = 100L
        )!!

        assertThat(
            guard.markExternalRedirectRequested(
                browserPackageName = FIREFOX_PACKAGE,
                transitionId = transition.id,
                requestedAtUptimeMillis = 110L
            )
        ).isTrue()
        assertThat(
            guard.rebindExternalRedirectWindow(
                browserPackageName = FIREFOX_PACKAGE,
                transitionId = transition.id,
                windowId = SAFE_WINDOW_ID,
                inspectionGeneration = 2L,
                eventUptimeMillis = 120L
            )
        ).isTrue()
        assertThat(
            guard.confirmRedirectFromStableCurrentSurface(
                browserPackageName = FIREFOX_PACKAGE,
                windowId = SAFE_WINDOW_ID,
                observedAtUptimeMillis = 130L
            )
        ).isTrue()
        assertThat(transition.safeRedirectConfirmed.isCompleted).isTrue()
    }

    private companion object {
        const val FIREFOX_PACKAGE = "org.mozilla.firefox"
        const val BLOCKED_WINDOW_ID = 7
        const val SAFE_WINDOW_ID = 8
    }
}
