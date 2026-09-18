package com.focusguard.utils

import android.app.Application
import android.content.Context
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class UsageAccessStateMonitorTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication<Application>().applicationContext
        UsageAccessStateMonitor.stop()
        mockkObject(PermissionUtils)
    }

    @After
    fun tearDown() {
        UsageAccessStateMonitor.stop()
        unmockkObject(PermissionUtils)
    }

    @Test
    fun `stop waits for in-flight check and monitor can restart cleanly`() {
        val enteredChecks = List(2) { CountDownLatch(1) }
        val releaseChecks = List(2) { CountDownLatch(1) }
        val invocation = AtomicInteger(0)
        val stopExecutor = Executors.newSingleThreadExecutor()

        every { PermissionUtils.isUsageAccessEnabled(any()) } answers {
            val index = invocation.getAndIncrement()
            if (index in enteredChecks.indices) {
                enteredChecks[index].countDown()
                releaseChecks[index].await(5, TimeUnit.SECONDS)
            }
            true
        }

        try {
            repeat(2) { generation ->
                UsageAccessStateMonitor.start(context)
                assertThat(
                    enteredChecks[generation].await(5, TimeUnit.SECONDS)
                ).isTrue()

                val stopReturned = CountDownLatch(1)
                stopExecutor.execute {
                    UsageAccessStateMonitor.stop()
                    stopReturned.countDown()
                }

                // The old implementation returned immediately here while its
                // Dispatchers.IO coroutine was still inside the permission check.
                assertThat(stopReturned.await(150, TimeUnit.MILLISECONDS)).isFalse()

                releaseChecks[generation].countDown()
                assertThat(stopReturned.await(5, TimeUnit.SECONDS)).isTrue()
            }

            assertThat(invocation.get()).isEqualTo(2)
        } finally {
            releaseChecks.forEach(CountDownLatch::countDown)
            UsageAccessStateMonitor.stop()
            stopExecutor.shutdownNow()
        }
    }
}
