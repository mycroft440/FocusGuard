package com.focusguard.domain.model

/** Persisted, platform-independent state for an active Focus Mode session. */
data class FocusModeSession(
    val startedAtMillis: Long,
    val endTimeMillis: Long,
    val durationMillis: Long,
    val allowedPackages: Set<String>,
    val blockedPackages: Set<String>,
    val nonSuspendablePackages: Set<String> = emptySet(),
    val grayscaleEnabled: Boolean = false
) {
    fun isActive(nowMillis: Long = System.currentTimeMillis()): Boolean =
        endTimeMillis > nowMillis && durationMillis > 0L

    fun remainingMillis(nowMillis: Long = System.currentTimeMillis()): Long =
        (endTimeMillis - nowMillis).coerceAtLeast(0L)
}
