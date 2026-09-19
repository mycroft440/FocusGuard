package com.focusguard.accessibility.website.redirection

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
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

    @Test
    fun `execution retries same tab once and releases only after confirmation`() = runBlocking {
        val session = WebsiteRedirectionCoordinator.Session(strict = false)
        session.begin()
        val adapter = FakeAdapter(
            sameTabResults = ArrayDeque(listOf(false, true)),
            redirectConfirmed = true
        )

        val outcome = WebsiteRedirectionCoordinator.execute(session, adapter)

        assertThat(outcome).isEqualTo(WebsiteRedirectionCoordinator.Outcome.REDIRECT_CONFIRMED)
        assertThat(adapter.sameTabAttempts).containsExactly(1, 2).inOrder()
        assertThat(adapter.restoreCalls).isEqualTo(1)
        assertThat(adapter.retryNumbers).containsExactly(2)
        assertThat(adapter.releaseCalls).isEqualTo(1)
        assertThat(adapter.failClosedCalls).isEqualTo(0)
    }

    @Test
    fun `execution becomes fail closed after bounded same tab attempts`() = runBlocking {
        val session = WebsiteRedirectionCoordinator.Session(strict = false)
        session.begin()
        val adapter = FakeAdapter(
            sameTabResults = ArrayDeque(listOf(false, false)),
            redirectConfirmed = false
        )

        val outcome = WebsiteRedirectionCoordinator.execute(session, adapter)

        assertThat(outcome).isEqualTo(WebsiteRedirectionCoordinator.Outcome.FAIL_CLOSED)
        assertThat(adapter.sameTabAttempts).containsExactly(1, 2).inOrder()
        assertThat(adapter.restoreCalls).isEqualTo(1)
        assertThat(adapter.releaseCalls).isEqualTo(0)
        assertThat(adapter.failClosedCalls).isEqualTo(1)
    }

    private class FakeAdapter(
        private val sameTabResults: ArrayDeque<Boolean>,
        private val redirectConfirmed: Boolean
    ) : WebsiteRedirectionCoordinator.Adapter {
        var ownsProtection = true
        val sameTabAttempts = mutableListOf<Int>()
        val retryNumbers = mutableListOf<Int>()
        var restoreCalls = 0
        var releaseCalls = 0
        var failClosedCalls = 0

        override suspend fun awaitPresentationFrame(): Boolean = true

        override fun ownsProtection(): Boolean = ownsProtection

        override suspend fun requestSameTabRedirect(attemptNumber: Int): Boolean {
            sameTabAttempts += attemptNumber
            return sameTabResults.removeFirstOrNull() ?: false
        }

        override suspend fun restoreBlockedSurfaceForRetry(): Boolean {
            restoreCalls += 1
            return true
        }

        override suspend fun beforeRetry(nextAttemptNumber: Int) {
            retryNumbers += nextAttemptNumber
        }

        override suspend fun requestExternalFallback(): Boolean = false

        override suspend fun awaitRedirectConfirmation(): Boolean = redirectConfirmed

        override suspend fun completeStrictDestination(): Boolean = true

        override suspend fun releasePresentation() {
            releaseCalls += 1
        }

        override fun failClosed() {
            failClosedCalls += 1
        }
    }
}
