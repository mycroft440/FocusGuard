package com.focusguard.accessibility.website.redirection

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Test

class WebsiteRedirectionCoordinatorTest {
    @Test
    fun `execution prepares submits confirms and releases`() = runBlocking {
        val adapter = FakeAdapter()
        val outcome = WebsiteRedirectionCoordinator.execute(normalSession(), adapter)

        assertThat(outcome).isEqualTo(WebsiteRedirectionCoordinator.Outcome.REDIRECT_CONFIRMED)
        assertThat(adapter.prepareAttempts).containsExactly(1)
        assertThat(adapter.submitAttempts).containsExactly(1)
        assertThat(adapter.confirmCalls).isEqualTo(1)
        assertThat(adapter.releaseCalls).isEqualTo(1)
    }

    @Test
    fun `confirmation timeout retries whole same tab transaction once`() = runBlocking {
        val adapter = FakeAdapter(confirmResults = ArrayDeque(listOf(false, true)))
        val outcome = WebsiteRedirectionCoordinator.execute(normalSession(), adapter)

        assertThat(outcome).isEqualTo(WebsiteRedirectionCoordinator.Outcome.REDIRECT_CONFIRMED)
        assertThat(adapter.prepareAttempts).containsExactly(1, 2).inOrder()
        assertThat(adapter.submitAttempts).containsExactly(1, 2).inOrder()
        assertThat(adapter.confirmCalls).isEqualTo(2)
        assertThat(adapter.restoreCalls).isEqualTo(1)
        assertThat(adapter.failClosedCalls).isEqualTo(0)
    }

    @Test
    fun `prepare failure retries without submit`() = runBlocking {
        val adapter = FakeAdapter(prepareResults = ArrayDeque(listOf(false, true)))
        val outcome = WebsiteRedirectionCoordinator.execute(normalSession(), adapter)

        assertThat(outcome).isEqualTo(WebsiteRedirectionCoordinator.Outcome.REDIRECT_CONFIRMED)
        assertThat(adapter.prepareAttempts).containsExactly(1, 2).inOrder()
        assertThat(adapter.submitAttempts).containsExactly(2)
        assertThat(adapter.restoreCalls).isEqualTo(1)
    }

    @Test
    fun `submit failure retries from restored blocked surface`() = runBlocking {
        val adapter = FakeAdapter(submitResults = ArrayDeque(listOf(false, true)))
        val outcome = WebsiteRedirectionCoordinator.execute(normalSession(), adapter)

        assertThat(outcome).isEqualTo(WebsiteRedirectionCoordinator.Outcome.REDIRECT_CONFIRMED)
        assertThat(adapter.submitAttempts).containsExactly(1, 2).inOrder()
        assertThat(adapter.restoreCalls).isEqualTo(1)
    }

    @Test
    fun `restore failure is terminal fail closed`() = runBlocking {
        val adapter = FakeAdapter(
            prepareResults = ArrayDeque(listOf(false)),
            restoreResult = false
        )
        val outcome = WebsiteRedirectionCoordinator.execute(normalSession(), adapter)

        assertThat(outcome).isEqualTo(WebsiteRedirectionCoordinator.Outcome.FAIL_CLOSED)
        assertThat(adapter.releaseCalls).isEqualTo(0)
        assertThat(adapter.failClosedCalls).isEqualTo(1)
    }

    @Test
    fun `loss of curtain ownership aborts without penalizing with fail closed`() = runBlocking {
        val adapter = FakeAdapter(loseOwnershipAfterPrepare = true)
        val outcome = WebsiteRedirectionCoordinator.execute(normalSession(), adapter)

        assertThat(outcome).isEqualTo(WebsiteRedirectionCoordinator.Outcome.ABORTED)
        assertThat(adapter.failClosedCalls).isEqualTo(0)
        assertThat(adapter.releaseCalls).isEqualTo(0)
    }

    @Test
    fun `strict destination failure remains fail closed`() = runBlocking {
        val adapter = FakeAdapter(strictDestinationResult = false)
        val outcome = WebsiteRedirectionCoordinator.execute(strictSession(), adapter)

        assertThat(outcome).isEqualTo(WebsiteRedirectionCoordinator.Outcome.FAIL_CLOSED)
        assertThat(adapter.failClosedCalls).isEqualTo(1)
        assertThat(adapter.releaseCalls).isEqualTo(0)
    }

    @Test
    fun `runtime exception while protected becomes fail closed`() = runBlocking {
        val adapter = FakeAdapter(throwOnSubmit = true)
        val outcome = WebsiteRedirectionCoordinator.execute(normalSession(), adapter)

        assertThat(outcome).isEqualTo(WebsiteRedirectionCoordinator.Outcome.FAIL_CLOSED)
        assertThat(adapter.failClosedCalls).isEqualTo(1)
    }

    @Test(expected = CancellationException::class)
    fun `cancellation is never converted into fail closed`() {
        runBlocking {
            WebsiteRedirectionCoordinator.execute(
                normalSession(),
                FakeAdapter(throwCancellationOnSubmit = true)
            )
        }
    }

    private fun normalSession() = WebsiteRedirectionCoordinator.Session(strict = false).also { it.begin() }
    private fun strictSession() = WebsiteRedirectionCoordinator.Session(strict = true).also { it.begin() }

    private class FakeAdapter(
        private val prepareResults: ArrayDeque<Boolean> = ArrayDeque(listOf(true, true)),
        private val submitResults: ArrayDeque<Boolean> = ArrayDeque(listOf(true, true)),
        private val confirmResults: ArrayDeque<Boolean> = ArrayDeque(listOf(true, true)),
        private val restoreResult: Boolean = true,
        private val strictDestinationResult: Boolean = true,
        private val loseOwnershipAfterPrepare: Boolean = false,
        private val throwOnSubmit: Boolean = false,
        private val throwCancellationOnSubmit: Boolean = false
    ) : WebsiteRedirectionCoordinator.Adapter {
        val prepareAttempts = mutableListOf<Int>()
        val submitAttempts = mutableListOf<Int>()
        var confirmCalls = 0
        var restoreCalls = 0
        var releaseCalls = 0
        var failClosedCalls = 0
        private var owned = true

        override suspend fun awaitPresentationFrame(): Boolean = true
        override fun ownsProtection(): Boolean = owned

        override suspend fun prepareSameTabRedirect(attemptNumber: Int): Boolean {
            prepareAttempts += attemptNumber
            val result = prepareResults.removeFirstOrNull() ?: false
            if (loseOwnershipAfterPrepare) owned = false
            return result
        }

        override suspend fun submitSameTabRedirect(attemptNumber: Int): Boolean {
            if (throwCancellationOnSubmit) throw CancellationException("cancel")
            if (throwOnSubmit) throw IllegalStateException("boom")
            submitAttempts += attemptNumber
            return submitResults.removeFirstOrNull() ?: false
        }

        override suspend fun restoreBlockedSurfaceForRetry(): Boolean {
            restoreCalls += 1
            return restoreResult
        }

        override suspend fun beforeRetry(nextAttemptNumber: Int) = Unit
        override suspend fun awaitRedirectConfirmation(): Boolean {
            confirmCalls += 1
            return confirmResults.removeFirstOrNull() ?: false
        }

        override suspend fun completeStrictDestination(): Boolean = strictDestinationResult
        override suspend fun releasePresentation() { releaseCalls += 1 }
        override fun failClosed() { failClosedCalls += 1 }
    }
}
