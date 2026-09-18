package com.focusguard.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Keeps the HardBlock management UI authenticated only for the current process
 * foreground visit. Returning from the background starts a new generation and
 * therefore requires authentication again when a PASSWORD block is active.
 *
 * The session deliberately distinguishes a passive unlock (there was no active
 * PASSWORD gate) from an unlock that actually verified password/pattern/biometric.
 * Sensitive actions may reuse only the latter.
 *
 * This state is UI-only: Accessibility/enforcement code never reads it, so it
 * cannot add work or latency to the blocking hot path.
 */
object AppEntryAuthSession {
    private val _foregroundGeneration = MutableStateFlow(0L)
    val foregroundGeneration: StateFlow<Long> = _foregroundGeneration.asStateFlow()

    @Volatile
    private var authenticatedGeneration = Long.MIN_VALUE

    @Volatile
    private var credentialAuthenticatedGeneration = Long.MIN_VALUE

    @Synchronized
    fun beginForeground() {
        val current = _foregroundGeneration.value
        val next = if (current == Long.MAX_VALUE) 1L else current + 1L
        authenticatedGeneration = Long.MIN_VALUE
        credentialAuthenticatedGeneration = Long.MIN_VALUE
        _foregroundGeneration.value = next
    }

    fun isAuthenticated(generation: Long): Boolean =
        generation > 0L &&
            _foregroundGeneration.value == generation &&
            authenticatedGeneration == generation

    fun isCredentialAuthenticated(generation: Long): Boolean =
        isAuthenticated(generation) && credentialAuthenticatedGeneration == generation

    @Synchronized
    fun markAuthenticated(generation: Long) {
        if (generation > 0L && _foregroundGeneration.value == generation) {
            authenticatedGeneration = generation
        }
    }

    @Synchronized
    fun markCredentialAuthenticated(generation: Long) {
        if (generation > 0L && _foregroundGeneration.value == generation) {
            authenticatedGeneration = generation
            credentialAuthenticatedGeneration = generation
        }
    }
}
