package com.focusguard.utils

import android.app.usage.UsageStatsManager
import android.content.Context
import com.focusguard.database.AppUsageLimit

/**
 * Converts Android's day-wide UsageStats counter into the usage that happened
 * after a specific app limit was activated.
 *
 * UsageStats gives us a cheap aggregate from local midnight to now. Querying a
 * second arbitrary range for every app on every 1-second enforcement pulse would
 * make the limiter noticeably heavier, so the pre-activation baseline is lazily
 * captured once per {package, activation, day}. It stays in memory for the hot
 * path and is mirrored to SharedPreferences so process recreation keeps the exact
 * same allowance. From the following local midnight onward the activation
 * predates the day and no subtraction is required: the limit naturally becomes a
 * normal daily allowance.
 */
object AppUsageLimitActivationUsage {
    private const val PREFS_NAME = "app_usage_limit_activation_usage"
    private const val SUFFIX_ACTIVATED_AT = ".activated_at"
    private const val SUFFIX_DAY_START = ".day_start"
    private const val SUFFIX_BASELINE_MS = ".baseline_ms"

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

        // A limit created before this local day gets the ordinary midnight reset.
        if (activatedAt <= dayStartMillis) return currentUsage

        // A wall-clock correction must never make a newly-created limit inherit
        // usage from before its apparent activation time.
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

        // Only a cache miss needs this binder/AppOps check. Never persist a fake
        // zero baseline while Usage Access is absent; after permission restoration
        // the real pre-activation usage can still be reconstructed correctly.
        if (!PermissionUtils.isUsageAccessEnabled(context)) return null

        val baselineEnd = activatedAtMillis.coerceAtMost(nowMillis)
        val baseline = try {
            usageStatsManager
                .queryAndAggregateUsageStats(dayStartMillis, baselineEnd)
                .get(packageName)
                ?.totalTimeInForeground
                ?.coerceAtLeast(0L)
                ?: 0L
        } catch (_: RuntimeException) {
            // Fail open for this pulse and retry later instead of persisting an
            // incorrect baseline that could make old usage count against the limit.
            return null
        }

        memoryBaselines[cacheKey] = baseline
        prefs.edit()
            .putLong(keyPrefix + SUFFIX_ACTIVATED_AT, activatedAtMillis)
            .putLong(keyPrefix + SUFFIX_DAY_START, dayStartMillis)
            .putLong(keyPrefix + SUFFIX_BASELINE_MS, baseline)
            .apply()
        return baseline
    }
}
