package com.focusguard.utils

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager

/** Resolves the currently resumed package from exact UsageEvents. */
object AppUsageForegroundResolver {
    private const val EVENT_ACTIVITY_RESUMED = 1
    private const val EVENT_ACTIVITY_PAUSED = 2
    private const val EVENT_ACTIVITY_STOPPED = 23

    fun currentForegroundPackage(
        usageStatsManager: UsageStatsManager,
        startMillis: Long,
        endMillis: Long
    ): String? {
        if (endMillis <= startMillis) return null
        return runCatching {
            val events = usageStatsManager.queryEvents(startMillis, endMillis)
            val event = UsageEvents.Event()
            var foregroundPackage: String? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val packageName = event.packageName?.takeIf(String::isNotBlank) ?: continue
                when (event.eventType) {
                    EVENT_ACTIVITY_RESUMED -> foregroundPackage = packageName
                    EVENT_ACTIVITY_PAUSED,
                    EVENT_ACTIVITY_STOPPED -> {
                        if (foregroundPackage == packageName) {
                            foregroundPackage = null
                        }
                    }
                }
            }
            foregroundPackage
        }.getOrNull()
    }
}
