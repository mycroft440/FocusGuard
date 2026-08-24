package com.focusguard.security

/**
 * Process-local safe-surface handshake.
 *
 * A generation must never travel through an externally sendable broadcast: on
 * Android 8-12 a dynamically registered receiver would let another app spoof a
 * predictable generation and hide the accessibility curtain early.
 */
object CurtainDestinationReadyCoordinator {
    fun interface Listener {
        fun onDestinationReady(generation: Long)
    }

    @Volatile private var listener: Listener? = null

    fun register(value: Listener) {
        listener = value
    }

    fun unregister(value: Listener) {
        if (listener === value) listener = null
    }

    fun notifyReady(generation: Long) {
        if (generation <= 0L) return
        listener?.onDestinationReady(generation)
    }
}
