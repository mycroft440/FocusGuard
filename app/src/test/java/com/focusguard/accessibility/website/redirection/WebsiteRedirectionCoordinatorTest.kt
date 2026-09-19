package com.focusguard.accessibility.website.redirection

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WebsiteRedirectionCoordinatorTest {
    @Test
    fun `normal website redirect owns presentation then hides it after confirmation`() {
        val session = WebsiteRedirectionCoordinator.Session(strict = false)
        assertThat(session.terminalDestination)
            .isEqualTo(WebsiteRedirectionCoordinator.TerminalDestination.REDIRECT)
        assertThat(session.begin()).containsExactly(
            WebsiteRedirectionCoordinator.Action.SHOW_BLOCK_PRESENTATION,
            WebsiteRedirectionCoordinator.Action.NEUTRALIZE_BLOCKED_TAB
        ).inOrder()
        assertThat(session.afterRedirectConfirmed())
            .isEqualTo(WebsiteRedirectionCoordinator.Action.HIDE_BLOCK_PRESENTATION)
    }

    @Test
    fun `strict website redirect sanitizes browser before Pomodoro`() {
        val session = WebsiteRedirectionCoordinator.Session(strict = true)
        assertThat(session.terminalDestination)
            .isEqualTo(WebsiteRedirectionCoordinator.TerminalDestination.POMODORO)
        session.begin()
        assertThat(session.afterRedirectConfirmed())
            .isEqualTo(WebsiteRedirectionCoordinator.Action.OPEN_POMODORO)
        assertThat(session.onPomodoroConfirmed())
            .isEqualTo(WebsiteRedirectionCoordinator.Action.HIDE_BLOCK_PRESENTATION)
    }
}
