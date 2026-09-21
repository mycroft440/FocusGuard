package com.focusguard.accessibility.website.redirection

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

class WebsiteRedirectionEligibilityTest {
    @Test
    fun `blocked destination fails closed before touching browser`() = runBlocking {
        val adapter = EligibilityAdapter(allowed = false)
        val outcome = WebsiteRedirectionCoordinator.execute(session(), adapter)

        assertThat(outcome).isEqualTo(WebsiteRedirectionCoordinator.Outcome.FAIL_CLOSED)
        assertThat(adapter.prepareCalls).isEqualTo(0)
        assertThat(adapter.submitCalls).isEqualTo(0)
        assertThat(adapter.failClosedCalls).isEqualTo(1)
    }

    @Test
    fun `destination invalidated after prepare never submits navigation`() = runBlocking {
        val adapter = EligibilityAdapter(
            allowed = true,
            invalidateAfterPrepare = true
        )
        val outcome = WebsiteRedirectionCoordinator.execute(session(), adapter)

        assertThat(outcome).isEqualTo(WebsiteRedirectionCoordinator.Outcome.FAIL_CLOSED)
        assertThat(adapter.prepareCalls).isEqualTo(1)
        assertThat(adapter.submitCalls).isEqualTo(0)
        assertThat(adapter.failClosedCalls).isEqualTo(1)
    }

    private fun session() = WebsiteRedirectionCoordinator.Session(strict = false).also { it.begin() }

    private class EligibilityAdapter(
        allowed: Boolean,
        private val invalidateAfterPrepare: Boolean = false
    ) : WebsiteRedirectionCoordinator.Adapter {
        private var redirectAllowed = allowed
        var prepareCalls = 0
        var submitCalls = 0
        var failClosedCalls = 0

        override suspend fun awaitPresentationFrame(): Boolean = true
        override fun ownsProtection(): Boolean = true
        override fun mayAttemptRedirect(): Boolean = redirectAllowed

        override suspend fun prepareSameTabRedirect(attemptNumber: Int): Boolean {
            prepareCalls += 1
            if (invalidateAfterPrepare) redirectAllowed = false
            return true
        }

        override suspend fun submitSameTabRedirect(attemptNumber: Int): Boolean {
            submitCalls += 1
            return true
        }

        override suspend fun restoreBlockedSurfaceForRetry(): Boolean = false
        override suspend fun beforeRetry(nextAttemptNumber: Int) = Unit
        override suspend fun awaitRedirectConfirmation(): Boolean = false
        override suspend fun completeStrictDestination(): Boolean = false
        override suspend fun releasePresentation() = Unit
        override fun failClosed() { failClosedCalls += 1 }
    }
}
