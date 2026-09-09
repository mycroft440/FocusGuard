package com.focusguard.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Keeps the HardBlock management UI authenticated only for the current process
 * foreground visit. Returning from the background starts a new generation and
 * therefore requires authentication again when a PASSWORD block is active.
 *
 * This state is UI-only: Accessibility/enforcement code never reads it, so it
 * cannot add work or latency to the blocking hot path.
 */
object AppEntryAuthSession {
    private val _foregroundGeneration = MutableStateFlow(0L)
    val foregroundGeneration: StateFlow<Long> = _foregroundGeneration.asStateFlow()

    @Volatile
    private var authenticatedGeneration = Long.MIN_VALUE

    @Synchronized
    fun beginForeground() {
        val current = _foregroundGeneration.value
        val next = if (current == Long.MAX_VALUE) 1L else current + 1L
        authenticatedGeneration = Long.MIN_VALUE
        _foregroundGeneration.value = next
    }

    fun isAuthenticated(generation: Long): Boolean =
        generation > 0L &&
            _foregroundGeneration.value == generation &&
            authenticatedGeneration == generation

    @Synchronized
    fun markAuthenticated(generation: Long) {
        if (generation > 0L && _foregroundGeneration.value == generation) {
            authenticatedGeneration = generation
        }
    }
}
