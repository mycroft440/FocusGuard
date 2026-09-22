package com.focusguard.accessibility.website.compatibility

import java.util.ArrayDeque
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

/**
 * Runs PackageManager collection with a hard result deadline without letting one
 * Binder call poison browser discovery for the lifetime of the process.
 *
 * A timed-out executor is retired and interrupted. Because Binder work is not
 * guaranteed to honor interruption, at most [maxRetiredWorkers] timed-out workers
 * may remain quarantined. A replacement worker is created while that bounded budget
 * is available; once the budget is exhausted, calls fail inconclusively until one
 * retired worker actually terminates. This keeps both forward progress after an
 * isolated stall and a hard bound on leaked/stuck worker threads.
 */
internal class BrowserCollectionRunner(
    private val deadlineMillis: Long,
    private val maxRetiredWorkers: Int = 2,
    private val threadNamePrefix: String = "FocusGuard-BrowserDetector"
) {
    enum class AbortReason {
        TIMEOUT,
        REJECTED,
        INTERRUPTED,
        EXECUTION_FAILED,
        SATURATED
    }

    private val lock = Any()
    private val threadCounter = AtomicLong(0L)
    private val retiredExecutors = ArrayDeque<ThreadPoolExecutor>()
    private var activeExecutor: ThreadPoolExecutor? = newExecutor()

    init {
        require(deadlineMillis > 0L)
        require(maxRetiredWorkers >= 1)
    }

    fun <T> run(
        onAbort: (AbortReason) -> Unit = {},
        task: () -> T
    ): T? {
        val executor = synchronized(lock) {
            acquireExecutorLocked()
        } ?: run {
            onAbort(AbortReason.SATURATED)
            return null
        }

        val future = try {
            executor.submit<T> { task() }
        } catch (_: RejectedExecutionException) {
            onAbort(AbortReason.REJECTED)
            return null
        }

        return try {
            future.get(deadlineMillis, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            // Invalidate the caller's lease before interrupting the worker so a
            // Binder call that returns concurrently cannot publish a late result.
            onAbort(AbortReason.TIMEOUT)
            future.cancel(true)
            retireTimedOutExecutor(executor)
            null
        } catch (_: InterruptedException) {
            onAbort(AbortReason.INTERRUPTED)
            future.cancel(true)
            Thread.currentThread().interrupt()
            null
        } catch (_: ExecutionException) {
            onAbort(AbortReason.EXECUTION_FAILED)
            null
        }
    }

    private fun acquireExecutorLocked(): ThreadPoolExecutor? {
        pruneRetiredLocked()
        val existing = activeExecutor
        if (existing != null && !existing.isShutdown) return existing
        if (retiredExecutors.size >= maxRetiredWorkers) return null
        return newExecutor().also { activeExecutor = it }
    }

    private fun retireTimedOutExecutor(executor: ThreadPoolExecutor) = synchronized(lock) {
        if (activeExecutor !== executor) return@synchronized
        activeExecutor = null
        executor.shutdownNow()
        retiredExecutors.addLast(executor)
        pruneRetiredLocked()
        if (retiredExecutors.size < maxRetiredWorkers) {
            activeExecutor = newExecutor()
        }
    }

    private fun pruneRetiredLocked() {
        val iterator = retiredExecutors.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().isTerminated) iterator.remove()
        }
    }

    private fun newExecutor(): ThreadPoolExecutor = ThreadPoolExecutor(
        1,
        1,
        30L,
        TimeUnit.SECONDS,
        SynchronousQueue(),
        { runnable ->
            Thread(
                runnable,
                "$threadNamePrefix-${threadCounter.incrementAndGet()}"
            ).apply { isDaemon = true }
        },
        ThreadPoolExecutor.AbortPolicy()
    ).apply {
        allowCoreThreadTimeOut(true)
    }

    internal fun closeForTest() = synchronized(lock) {
        activeExecutor?.shutdownNow()
        activeExecutor = null
        retiredExecutors.forEach(ThreadPoolExecutor::shutdownNow)
        retiredExecutors.clear()
    }

    internal fun retiredWorkerCountForTest(): Int = synchronized(lock) {
        pruneRetiredLocked()
        retiredExecutors.size
    }
}
