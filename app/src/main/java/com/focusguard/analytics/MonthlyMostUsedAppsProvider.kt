package com.focusguard.analytics

import android.app.usage.UsageStatsManager
import android.content.Context
import com.focusguard.utils.FocusGuardLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Loads a long-window usage ranking from Android's aggregated usage history.
 *
 * Monthly rankings deliberately prefer aggregate history over lifecycle events:
 * several Android builds retain aggregate buckets longer than detailed events.
 */
object MonthlyMostUsedAppsProvider {
    suspend fun load(
        context: Context,
        startTime: Long,
        endTime: Long
    ): List<AppUsageStat> = withContext(Dispatchers.IO) {
        require(endTime > startTime) { "endTime must be after startTime" }

        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return@withContext emptyList()
        val packageManager = context.packageManager

        try {
            manager.queryAndAggregateUsageStats(startTime, endTime)
                .values
                .asSequence()
                .filter { usage ->
                    usage.totalTimeInForeground > 0L &&
                        runCatching {
                            packageManager.getLaunchIntentForPackage(usage.packageName) != null
                        }.getOrDefault(false)
                }
                .map { usage ->
                    AppUsageStat(
                        packageName = usage.packageName,
                        timeSpentMs = usage.totalTimeInForeground
                            .coerceAtMost(endTime - startTime)
                    )
                }
                .sortedByDescending(AppUsageStat::timeSpentMs)
                .toList()
        } catch (error: Throwable) {
            FocusGuardLogger.logError(
                "MonthlyMostUsedApps",
                "Falha ao carregar ranking mensal agregado",
                error
            )
            emptyList()
        }
    }

    /**
     * Uso dos dias encerrados do mês: o total do mês até agora menos o uso exato de hoje.
     *
     * Consultar o agregado só até a meia-noite de hoje não bastava: o balde aberto do
     * Android começa antes da meia-noite e já carrega o uso de hoje, então a "média dos
     * dias encerrados" subia ao longo do dia. O balde aberto cresce exatamente com o uso
     * de hoje, e por isso descontá-lo deixa só os dias que já passaram.
     */
    fun completedDaysUsage(
        monthToNow: List<AppUsageStat>,
        today: List<AppUsageStat>
    ): List<AppUsageStat> {
        val todayByPackage = today.associate { it.packageName to it.timeSpentMs }
        return monthToNow.asSequence()
            .map { usage ->
                usage.copy(
                    timeSpentMs = (
                        usage.timeSpentMs - (todayByPackage[usage.packageName] ?: 0L)
                    ).coerceAtLeast(0L)
                )
            }
            .filter { it.timeSpentMs > 0L }
            .sortedByDescending(AppUsageStat::timeSpentMs)
            .toList()
    }
}
