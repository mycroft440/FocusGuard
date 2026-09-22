package com.focusguard.accessibility.website.compatibility

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserCollectionRunnerTest {
    @Test
    fun timedOutBinderWorkerIsQuarantinedAndReplacementStillRuns() {
        val runner = BrowserCollectionRunner(
            deadlineMillis = 80L,
            maxRetiredWorkers = 2,
            threadNamePrefix = "BrowserCollectionRunnerTest"
        )
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            val first = runner.run<String> {
                entered.countDown()
                while (release.count > 0L) {
                    try {
                        release.await(10L, TimeUnit.MILLISECONDS)
                    } catch (_: InterruptedException) {
                        // Simulates Binder/native work that ignores interruption.
                    }
                }
                "late"
            }

            assertNull(first)
            assertTrue(entered.await(1L, TimeUnit.SECONDS))
            assertEquals(1, runner.retiredWorkerCountForTest())

            val second = runner.run { "replacement-ok" }
            assertEquals("replacement-ok", second)
        } finally {
            release.countDown()
            runner.closeForTest()
        }
    }

    @Test
    fun stuckWorkerBudgetRemainsBounded() {
        val runner = BrowserCollectionRunner(
            deadlineMillis = 60L,
            maxRetiredWorkers = 1,
            threadNamePrefix = "BrowserCollectionRunnerBoundTest"
        )
        val release = CountDownLatch(1)
        val abort = AtomicReference<BrowserCollectionRunner.AbortReason>()
        try {
            assertNull(runner.run<String> {
                while (release.count > 0L) {
                    try {
                        release.await(10L, TimeUnit.MILLISECONDS)
                    } catch (_: InterruptedException) {
                        // Deliberately ignore cancellation to hold the retired slot.
                    }
                }
                "late"
            })

            val saturated = runner.run(
                onAbort = abort::set
            ) { "must-not-run" }

            assertNull(saturated)
            assertEquals(BrowserCollectionRunner.AbortReason.SATURATED, abort.get())
            assertEquals(1, runner.retiredWorkerCountForTest())
        } finally {
            release.countDown()
            runner.closeForTest()
        }
    }
}
