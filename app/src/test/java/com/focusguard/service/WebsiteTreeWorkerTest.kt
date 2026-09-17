package com.focusguard.service

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class WebsiteTreeWorkerTest {
    @Test
    fun runExecutesOnWorkerAndResumesCaller() {
        val createdWorkerThread = AtomicReference<Thread>()
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "focusguard-website-tree-test").also(createdWorkerThread::set)
        }
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            runBlocking {
                val callerThread = Thread.currentThread()
                val worker = WebsiteTreeWorker(dispatcher)

                val executingThread = worker.run { Thread.currentThread() }

                assertSame(createdWorkerThread.get(), executingThread)
                assertNotSame(callerThread, executingThread)
                assertSame(callerThread, Thread.currentThread())
            }
        } finally {
            dispatcher.close()
            executor.shutdownNow()
        }
    }
}
