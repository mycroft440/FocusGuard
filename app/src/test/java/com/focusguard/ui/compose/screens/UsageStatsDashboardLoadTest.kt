package com.focusguard.ui.compose.screens

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test

class UsageStatsDashboardLoadTest {

    @Test
    fun `composition cancellation is never exposed as a load failure`() = runTest {
        var cancellation: CancellationException? = null
        try {
            runCancellableInsightsLoad<Unit> {
                throw CancellationException("The coroutine scope left the composition")
            }
        } catch (expected: CancellationException) {
            cancellation = expected
        }

        assertThat(cancellation).isNotNull()
        assertThat(cancellation?.message).contains("left the composition")
    }

    @Test
    fun `ordinary load error is returned to the fallback state`() = runTest {
        val result = runCancellableInsightsLoad<Unit> {
            error("analytics unavailable")
        }

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).isEqualTo("analytics unavailable")
    }
}
