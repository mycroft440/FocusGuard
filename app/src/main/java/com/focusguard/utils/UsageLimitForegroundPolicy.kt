package com.focusguard.utils

/** Shared rules that keep daily limits restricted to active foreground use. */
object UsageLimitForegroundPolicy {

    fun usedMinutes(totalTimeInForegroundMillis: Long): Long =
        totalTimeInForegroundMillis.coerceAtLeast(0L) / 60_000L

    /**
     * Adds only the open foreground segment that UsageStats has not yet committed.
     *
     * [lastUsageEventMillis] is the latest event reflected by the aggregate. Using
     * it as the anchor avoids counting the same interval twice when Android updates
     * the aggregate while the Activity remains resumed.
     */
    fun includeOpenForegroundInterval(
        aggregatedForegroundMillis: Long,
        lastUsageEventMillis: Long,
        nowMillis: Long,
        isCurrentForeground: Boolean,
        isDeviceInteractive: Boolean
    ): Long {
        val aggregated = aggregatedForegroundMillis.coerceAtLeast(0L)
        if (!isCurrentForeground || !isDeviceInteractive) return aggregated
        if (lastUsageEventMillis <= 0L || lastUsageEventMillis >= nowMillis) return aggregated
        return aggregated + (nowMillis - lastUsageEventMillis)
    }

    fun shouldCountWebsiteUsage(
        trackedPackageName: String?,
        foregroundPackageName: String?,
        isDeviceInteractive: Boolean
    ): Boolean =
        isDeviceInteractive &&
            !trackedPackageName.isNullOrBlank() &&
            trackedPackageName == foregroundPackageName

    /**
     * Cheap guard for the one-second app-limit pulse.
     *
     * This must run before Room/UsageStats work. FocusGuard itself, the launcher,
     * a blank foreground package, a screen-off device, or an app with no active
     * limit cannot possibly need app-limit measurement on that pulse.
     */
    fun shouldMeasureCurrentApp(
        foregroundPackageName: String?,
        activeLimitPackages: Set<String>,
        focusGuardPackageName: String,
        launcherPackageName: String?,
        isDeviceInteractive: Boolean
    ): Boolean =
        isDeviceInteractive &&
            !foregroundPackageName.isNullOrBlank() &&
            foregroundPackageName != focusGuardPackageName &&
            foregroundPackageName != launcherPackageName &&
            foregroundPackageName in activeLimitPackages

    fun shouldEnforceCurrentApp(
        foregroundPackageName: String?,
        exceededPackages: Set<String>,
        focusGuardPackageName: String,
        launcherPackageName: String?,
        isDeviceInteractive: Boolean
    ): Boolean =
        shouldMeasureCurrentApp(
            foregroundPackageName = foregroundPackageName,
            activeLimitPackages = exceededPackages,
            focusGuardPackageName = focusGuardPackageName,
            launcherPackageName = launcherPackageName,
            isDeviceInteractive = isDeviceInteractive
        )
}
