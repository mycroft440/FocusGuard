package com.focusguard.utils

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import com.focusguard.database.AppUsageLimit

/**
 * Converts Android's day-wide UsageStats counter into usage after a limit activation.
 *
 * New limits persist the day aggregate at the instant they are created. If an old
 * row or an interrupted write has no baseline, the fallback reconstructs only the
 * pre-activation interval from UsageEvents instead of asking UsageStats for an
 * arbitrary historical aggregate whose interval can be expanded by Android.
 */
object AppUsageLimitActivationUsage {
    private const val PREFS_NAME = "app_usage_limit_activation_usage"
    private const val SUFFIX_ACTIVATED_AT = ".activated_at"
    private const val SUFFIX_DAY_START = ".day_start"
    private const val SUFFIX_BASELINE_MS = ".baseline_ms"
    private const val EVENT_ACTIVITY_RESUMED = 1
    private const val EVENT_ACTIVITY_PAUSED = 2
    private const val EVENT_ACTIVITY_STOPPED = 23
    private const val EVENT_LOOKBACK_MILLIS = 24L * 60L * 60L * 1_000L

    internal data class ForegroundTransition(
        val atMillis: Long,
        val enteredForeground: Boolean,
        val instanceId: Int? = null
    )

    private data class BaselineKey(
        val packageName: String,
        val activatedAtMillis: Long,
        val dayStartMillis: Long
    )

    private val memoryBaselines = mutableMapOf<BaselineKey, Long>()

    fun effectiveUsageMillis(
        context: Context,
        usageStatsManager: UsageStatsManager,
        limit: AppUsageLimit,
        currentDayUsageMillis: Long,
        dayStartMillis: Long,
        nowMillis: Long
    ): Long {
        val currentUsage = currentDayUsageMillis.coerceAtLeast(0L)
        val activatedAt = limit.createdAt
        if (activatedAt <= dayStartMillis) return currentUsage
        if (activatedAt > nowMillis) return 0L

        val baseline = readOrCreateBaseline(
            context = context,
            usageStatsManager = usageStatsManager,
            packageName = limit.packageName,
            activatedAtMillis = activatedAt,
            dayStartMillis = dayStartMillis,
            nowMillis = nowMillis
        ) ?: return 0L

        return usageSinceActivationMillis(
            currentDayUsageMillis = currentUsage,
            activationBaselineMillis = baseline,
            activatedAtMillis = activatedAt,
            dayStartMillis = dayStartMillis
        )
    }

    fun captureActivationBaseline(
        context: Context,
        usageStatsManager: UsageStatsManager,
        packageName: String,
        activatedAtMillis: Long,
        dayStartMillis: Long
    ): Boolean {
        if (packageName.isBlank() || activatedAtMillis <= dayStartMillis) return true
        if (!PermissionUtils.isUsageAccessEnabled(context)) return false

        val baseline = try {
            usageStatsManager
                .queryAndAggregateUsageStats(dayStartMillis, activatedAtMillis)
                .get(packageName)
                ?.totalTimeInForeground
                ?.coerceAtLeast(0L)
                ?: 0L
        } catch (_: RuntimeException) {
            return false
        }
        persistBaseline(
            context = context,
            packageName = packageName,
            activatedAtMillis = activatedAtMillis,
            dayStartMillis = dayStartMillis,
            baselineMillis = baseline
        )
        return true
    }

    /** Pure calculation kept public for deterministic unit coverage. */
    fun usageSinceActivationMillis(
        currentDayUsageMillis: Long,
        activationBaselineMillis: Long,
        activatedAtMillis: Long,
        dayStartMillis: Long
    ): Long {
        val currentUsage = currentDayUsageMillis.coerceAtLeast(0L)
        if (activatedAtMillis <= dayStartMillis) return currentUsage
        return (currentUsage - activationBaselineMillis.coerceAtLeast(0L))
            .coerceAtLeast(0L)
    }

    internal fun foregroundUsageMillis(
        transitions: List<ForegroundTransition>,
        startMillis: Long,
        endMillis: Long
    ): Long {
        if (endMillis <= startMillis) return 0L
        var legacyActive = false
        val activeInstances = mutableSetOf<Int>()
        var segmentStart: Long? = null
        var total = 0L

        transitions.sortedBy(ForegroundTransition::atMillis).forEach { transition ->
            val before = legacyActive || activeInstances.isNotEmpty()
            val instanceId = transition.instanceId
            if (instanceId == null) {
                legacyActive = transition.enteredForeground
            } else if (transition.enteredForeground) {
                activeInstances += instanceId
            } else {
                activeInstances -= instanceId
            }
            val after = legacyActive || activeInstances.isNotEmpty()
            when {
                !before && after -> {
                    segmentStart = transition.atMillis.coerceAtLeast(startMillis)
                }
                before && !after -> {
                    val from = segmentStart ?: startMillis
                    val until = transition.atMillis.coerceAtMost(endMillis)
                    if (until > from) total += until - from
                    segmentStart = null
                }
            }
        }

        if ((legacyActive || activeInstances.isNotEmpty()) && segmentStart != null) {
            val from = segmentStart!!.coerceAtLeast(startMillis)
            if (endMillis > from) total += endMillis - from
        }
        return total.coerceAtLeast(0L)
    }

    @Synchronized
    private fun readOrCreateBaseline(
        context: Context,
        usageStatsManager: UsageStatsManager,
        packageName: String,
        activatedAtMillis: Long,
        dayStartMillis: Long,
        nowMillis: Long
    ): Long? {
        if (packageName.isBlank()) return null
        val cacheKey = BaselineKey(packageName, activatedAtMillis, dayStartMillis)
        memoryBaselines[cacheKey]?.let { return it }

        val prefs = context.applicationContext.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )
        val keyPrefix = packageName
        val storedActivation = prefs.getLong(keyPrefix + SUFFIX_ACTIVATED_AT, Long.MIN_VALUE)
        val storedDayStart = prefs.getLong(keyPrefix + SUFFIX_DAY_START, Long.MIN_VALUE)
        if (storedActivation == activatedAtMillis && storedDayStart == dayStartMillis) {
            val persisted = prefs.getLong(keyPrefix + SUFFIX_BASELINE_MS, 0L)
                .coerceAtLeast(0L)
            memoryBaselines[cacheKey] = persisted
            return persisted
        }

        if (!PermissionUtils.isUsageAccessEnabled(context)) return null
        val baselineEnd = activatedAtMillis.coerceAtMost(nowMillis)
        val baseline = try {
            queryExactForegroundUsage(
                usageStatsManager = usageStatsManager,
                packageName = packageName,
                dayStartMillis = dayStartMillis,
                endMillis = baselineEnd
            )
        } catch (_: RuntimeException) {
            return null
        }

        persistBaseline(
            context = context,
            packageName = packageName,
            activatedAtMillis = activatedAtMillis,
            dayStartMillis = dayStartMillis,
            baselineMillis = baseline
        )
        return baseline
    }

    private fun queryExactForegroundUsage(
        usageStatsManager: UsageStatsManager,
        packageName: String,
        dayStartMillis: Long,
        endMillis: Long
    ): Long {
        if (endMillis <= dayStartMillis) return 0L
        val events = usageStatsManager.queryEvents(
            (dayStartMillis - EVENT_LOOKBACK_MILLIS).coerceAtLeast(0L),
            endMillis
        )
        val event = UsageEvents.Event()
        val transitions = mutableListOf<ForegroundTransition>()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.packageName != packageName) continue
            val entered = when (event.eventType) {
                EVENT_ACTIVITY_RESUMED -> true
                EVENT_ACTIVITY_PAUSED,
                EVENT_ACTIVITY_STOPPED -> false
                else -> continue
            }
            val instanceId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                event.className?.takeIf { it.isNotBlank() }?.hashCode()
            } else {
                null
            }
            transitions += ForegroundTransition(
                atMillis = event.timeStamp,
                enteredForeground = entered,
                instanceId = instanceId
            )
        }
        return foregroundUsageMillis(
            transitions = transitions,
            startMillis = dayStartMillis,
            endMillis = endMillis
        )
    }

    @Synchronized
    private fun persistBaseline(
        context: Context,
        packageName: String,
        activatedAtMillis: Long,
        dayStartMillis: Long,
        baselineMillis: Long
    ) {
        val cacheKey = BaselineKey(packageName, activatedAtMillis, dayStartMillis)
        val baseline = baselineMillis.coerceAtLeast(0L)
        memoryBaselines.keys.removeAll { it.packageName == packageName && it != cacheKey }
        memoryBaselines[cacheKey] = baseline
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(packageName + SUFFIX_ACTIVATED_AT, activatedAtMillis)
            .putLong(packageName + SUFFIX_DAY_START, dayStartMillis)
            .putLong(packageName + SUFFIX_BASELINE_MS, baseline)
            .commit()
    }
}
