package com.focusguard.service

import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class WebsiteTreeWorkerTest {
    @Test
    fun runExecutesOnWorkerAndResumesCaller() {
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "focusguard-website-tree-test")
        }
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            runBlocking {
                val callerThread = Thread.currentThread().name
                val worker = WebsiteTreeWorker(dispatcher)

                val workerThread = worker.run { Thread.currentThread().name }

                assertEquals("focusguard-website-tree-test", workerThread)
                assertNotEquals(callerThread, workerThread)
                assertEquals(callerThread, Thread.currentThread().name)
            }
        } finally {
            dispatcher.close()
            executor.shutdownNow()
        }
    }
}
