package com.focusguard.security

/** Keeps PackageManager/label enumeration away from the 5-second Room refresh. */
object LauncherIndexRefreshPolicy {
    internal const val PERIODIC_REFRESH_MILLIS = 15 * 60_000L

    fun shouldRequest(
        force: Boolean,
        lastRequestElapsed: Long,
        nowElapsed: Long
    ): Boolean = force ||
        lastRequestElapsed <= 0L ||
        nowElapsed - lastRequestElapsed >= PERIODIC_REFRESH_MILLIS

    /**
     * A global PackageManager failure must never replace a working label index.
     * A genuinely empty result is accepted only for the first successful
     * snapshot; later empty candidates preserve the last useful fast path.
     */
    fun shouldPublishCandidate(
        querySucceeded: Boolean,
        candidateSize: Int,
        hasSuccessfulSnapshot: Boolean
    ): Boolean = querySucceeded && (candidateSize > 0 || !hasSuccessfulSnapshot)
}
