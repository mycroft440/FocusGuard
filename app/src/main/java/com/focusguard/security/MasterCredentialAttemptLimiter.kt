package com.focusguard.security

import android.content.Context
import kotlin.math.min

/**
 * Local progressive backoff for the master-password TIME revocation path.
 *
 * No credential material is stored here. Only failure count and the next allowed timestamp are
 * persisted. A successful password resets the limiter immediately.
 */
class MasterCredentialAttemptLimiter(context: Context) {

    data class Gate(val allowed: Boolean, val retryAfterMillis: Long)

    private val preferences = context.applicationContext.getSharedPreferences(
        PREFS,
        Context.MODE_PRIVATE
    )

    fun gate(nowMillis: Long = System.currentTimeMillis()): Gate {
        val blockedUntil = preferences.getLong(KEY_BLOCKED_UNTIL, 0L)
        val remaining = (blockedUntil - nowMillis).coerceAtLeast(0L)
        return Gate(allowed = remaining == 0L, retryAfterMillis = remaining)
    }

    fun recordFailure(nowMillis: Long = System.currentTimeMillis()): Long {
        val failures = preferences.getInt(KEY_FAILURES, 0) + 1
        val delay = delayForFailureCount(failures)
        val blockedUntil = if (delay > 0L) nowMillis + delay else 0L
        preferences.edit()
            .putInt(KEY_FAILURES, failures)
            .putLong(KEY_BLOCKED_UNTIL, blockedUntil)
            .commit()
        return delay
    }

    fun recordSuccess() {
        preferences.edit().clear().commit()
    }

    fun clear() = recordSuccess()

    companion object {
        private const val PREFS = "focusguard_master_credential_attempts"
        private const val KEY_FAILURES = "failures"
        private const val KEY_BLOCKED_UNTIL = "blocked_until"
        internal const val MAX_DELAY_MILLIS = 15L * 60L * 1_000L

        /** First two mistakes are free; repeated guessing becomes progressively expensive. */
        internal fun delayForFailureCount(failures: Int): Long = when {
            failures <= 2 -> 0L
            failures == 3 -> 5_000L
            failures == 4 -> 15_000L
            failures == 5 -> 30_000L
            else -> {
                val exponent = (failures - 6).coerceIn(0, 20)
                min(MAX_DELAY_MILLIS, 60_000L * (1L shl exponent))
            }
        }
    }
}
