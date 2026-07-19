package com.focusguard.utils

/** Shared rules that keep daily limits restricted to active foreground use. */
object UsageLimitForegroundPolicy {

    fun usedMinutes(totalTimeInForegroundMillis: Long): Long =
        totalTimeInForegroundMillis.coerceAtLeast(0L) / 60_000L

    fun shouldCountWebsiteUsage(
        trackedPackageName: String?,
        foregroundPackageName: String?,
        isDeviceInteractive: Boolean
    ): Boolean =
        isDeviceInteractive &&
            !trackedPackageName.isNullOrBlank() &&
            trackedPackageName == foregroundPackageName
}
