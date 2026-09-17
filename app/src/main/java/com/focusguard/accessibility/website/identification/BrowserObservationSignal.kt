package com.focusguard.accessibility.website.identification

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Lightweight event signal for bounded website recovery.
 *
 * Accessibility objects never cross this boundary. The service publishes only that a new browser
 * observation happened for one package/window. Recovery captures a version before an action and
 * waits for a strictly newer version, falling back to its short timeout if the browser emits no
 * event. A replayed monotonic counter closes the race where an event arrives between performAction
 * returning and the coroutine starting to collect.
 */
internal object BrowserObservationSignal {
    private data class Key(val packageName: String, val windowId: Int)

    private class Entry {
        val version = AtomicLong(0L)
        val events = MutableSharedFlow<Long>(
            replay = 1,
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
    }

    private val entries = ConcurrentHashMap<Key, Entry>()

    fun markObserved(packageName: String, windowId: Int) {
        if (packageName.isBlank() || windowId < 0) return
        val entry = entries.computeIfAbsent(Key(packageName, windowId)) { Entry() }
        val next = entry.version.incrementAndGet()
        entry.events.tryEmit(next)
    }

    fun currentVersion(packageName: String, windowId: Int): Long {
        if (packageName.isBlank() || windowId < 0) return 0L
        return entries[Key(packageName, windowId)]?.version?.get() ?: 0L
    }

    suspend fun awaitAfter(
        packageName: String,
        windowId: Int,
        baselineVersion: Long,
        timeoutMillis: Long
    ): Boolean {
        if (packageName.isBlank() || windowId < 0 || timeoutMillis <= 0L) return false
        val entry = entries.computeIfAbsent(Key(packageName, windowId)) { Entry() }
        if (entry.version.get() > baselineVersion) return true
        return withTimeoutOrNull(timeoutMillis) {
            entry.events.first { observedVersion -> observedVersion > baselineVersion }
            true
        } ?: false
    }

    internal fun clearForTest(packageName: String, windowId: Int) {
        entries.remove(Key(packageName, windowId))
    }
}
