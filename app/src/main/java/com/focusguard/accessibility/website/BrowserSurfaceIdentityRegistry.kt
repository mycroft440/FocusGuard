package com.focusguard.accessibility.website

import java.util.concurrent.ConcurrentHashMap

/**
 * Process-local identity of the browser surface currently associated with one
 * Accessibility window. It contains primitives only and is used to prevent a
 * transaction created for one document/tab from authorizing actions on a newer
 * surface when Android reuses the same window id.
 */
internal object BrowserSurfaceIdentityRegistry {
    data class Identity(
        val generation: Long,
        val surfaceEpoch: Long
    )

    private data class Key(
        val packageName: String,
        val windowId: Int
    )

    private val identities = ConcurrentHashMap<Key, Identity>()

    fun publish(
        packageName: String,
        windowId: Int,
        generation: Long,
        surfaceEpoch: Long
    ) {
        if (packageName.isBlank() || windowId < 0 || generation <= 0L || surfaceEpoch <= 0L) return
        identities[Key(packageName, windowId)] = Identity(generation, surfaceEpoch)
    }

    fun current(packageName: String, windowId: Int): Identity? {
        if (packageName.isBlank() || windowId < 0) return null
        return identities[Key(packageName, windowId)]
    }

    fun forget(packageName: String, windowId: Int) {
        if (packageName.isBlank() || windowId < 0) return
        identities.remove(Key(packageName, windowId))
    }

    internal fun clearForTest() {
        identities.clear()
    }
}
