package com.focusguard.accessibility.website.identification

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BrowserObservationSignalTest {
    private val packageName = "test.browser.signal"
    private val windowId = 73

    @After
    fun tearDown() {
        BrowserObservationSignal.clearForTest(packageName, windowId)
    }

    @Test
    fun `waiter resumes only after a newer observation`() = runTest {
        val baseline = BrowserObservationSignal.currentVersion(packageName, windowId)
        val waiter = async {
            BrowserObservationSignal.awaitAfter(packageName, windowId, baseline, 1_000L)
        }
        runCurrent()
        assertThat(waiter.isCompleted).isFalse()

        BrowserObservationSignal.markObserved(packageName, windowId)

        assertThat(waiter.await()).isTrue()
    }

    @Test
    fun `event arriving before collector starts is still observed`() = runTest {
        val baseline = BrowserObservationSignal.currentVersion(packageName, windowId)
        BrowserObservationSignal.markObserved(packageName, windowId)

        assertThat(
            BrowserObservationSignal.awaitAfter(packageName, windowId, baseline, 1_000L)
        ).isTrue()
    }

    @Test
    fun `missing browser event falls back to bounded timeout`() = runTest {
        val baseline = BrowserObservationSignal.currentVersion(packageName, windowId)

        assertThat(
            BrowserObservationSignal.awaitAfter(packageName, windowId, baseline, 160L)
        ).isFalse()
    }
}
