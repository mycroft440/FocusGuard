package com.focusguard.analytics

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.focusguard.utils.FocusGuardLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

data class AppUsageStat(
    val packageName: String,
    val timeSpentMs: Long
)

data class AppAccessStat(
    val packageName: String,
    val accessCount: Int
)

class AdvancedUsageAnalytics(private val context: Context) {
    private val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
    private val pm = context.packageManager

    companion object {
        // Cache somente das listas de aplicativos. O uso de tela não é
        // armazenado aqui: ele precisa avançar enquanto a tela permanece ativa
        // e deve ser atualizado toda vez que o Dashboard volta ao primeiro plano.
        @Volatile
        private var cachedMostUsed: Pair<String, List<AppUsageStat>>? = null // Key: "start-end"
        @Volatile
        private var lastMostUsedTime: Long = 0

        @Volatile
        private var cachedMostOpened: Pair<String, List<AppAccessStat>>? = null
        @Volatile
        private var lastMostOpenedTime: Long = 0

        @Volatile
        private var cachedNeverUsed: Pair<String, List<String>>? = null
        @Volatile
        private var lastNeverUsedTime: Long = 0

        private const val CACHE_TTL = 5 * 60 * 1000L // 5 minutos
        private val cacheLock = Any()
    }

    suspend fun getPhoneUsageInsights(
        historyDays: Int = 7,
        periodAverageDays: Int = PHONE_USAGE_PERIOD_ANALYSIS_DAYS
    ): PhoneUsageInsights = withContext(Dispatchers.IO) {
        require(historyDays > 0) { "historyDays must be positive" }
        require(periodAverageDays > 0) { "periodAverageDays must be positive" }

        val now = System.currentTimeMillis()
        val zoneId = ZoneId.systemDefault()
        val locale = Locale.getDefault()
        val today = Instant.ofEpochMilli(now).atZone(zoneId).toLocalDate()

        val emptyInsights = {
            PhoneUsageInsightsCalculator.calculate(
                dailyScreenTimeByDate = emptyMap(),
                detailedIntervals = emptyList(),
                completePeriodDates = emptyList(),
                nowMs = now,
                historyDays = historyDays,
                zoneId = zoneId,
                locale = locale
            )
        }
        val manager = usageStatsManager ?: return@withContext emptyInsights()

        // O gráfico usa o histórico agregado do Android, que não sofre com a
        // retenção curta de queryEvents. Os eventos detalhados abaixo servem
        // para distribuir o uso por hora e gerar os resumos noturnos locais.
        val historyStartDate = today.minusDays(historyDays - 1L)
        val periodStartDate = today.minusDays(periodAverageDays.toLong())
        val earliestRequiredDate = minOf(historyStartDate, periodStartDate)
        val queryStart = earliestRequiredDate
            .minusDays(1)
            .atStartOfDay(zoneId)
            .toInstant()
            .toEpochMilli()

        val events = try {
            manager.queryEvents(queryStart, now)
        } catch (error: Throwable) {
            FocusGuardLogger.logError(
                "AdvancedUsageAnalytics",
                "Falha em queryEvents (phoneUsage)",
                error
            )
            null
        }

        val accumulator = ScreenInteractiveSessionAccumulator()
        val event = UsageEvents.Event()
        var eventLoopCompleted = events != null
        var lastEventTimestamp = queryStart

        if (events != null) {
            try {
                while (events.hasNextEvent()) {
                    events.getNextEvent(event)
                    lastEventTimestamp = maxOf(lastEventTimestamp, event.timeStamp)
                    when (event.eventType) {
                        UsageEvents.Event.SCREEN_INTERACTIVE ->
                            accumulator.onScreenInteractive(event.timeStamp)

                        UsageEvents.Event.SCREEN_NON_INTERACTIVE ->
                            accumulator.onScreenNonInteractive(event.timeStamp)
                    }
                }
            } catch (error: Throwable) {
                // OEMs podem interromper a iteração. Os intervalos já lidos
                // continuam úteis, mas o dia da interrupção não entra na média.
                eventLoopCompleted = false
                FocusGuardLogger.logError(
                    "AdvancedUsageAnalytics",
                    "Falha no loop de eventos (phoneUsage)",
                    error
                )
            }
        }

        val activeIntervalStartTimeMs = accumulator.activeIntervalStartTimeMs
        val detailedIntervals = accumulator.finish(
            if (eventLoopCompleted) now else lastEventTimestamp
        )
        val completePeriodDates = completeDatesCoveredByDetailedEvents(
            firstObservedEventTimeMs = accumulator.firstObservedEventTimeMs,
            lastObservedEventTimeMs = lastEventTimestamp,
            eventLoopCompleted = eventLoopCompleted,
            periodStartDate = periodStartDate,
            today = today,
            zoneId = zoneId
        )
        val recentSleepObservations = SleepPatternEstimator.extractObservations(
            intervals = detailedIntervals,
            completeDates = completePeriodDates,
            zoneId = zoneId
        )
        val sleepHistory = runCatching {
            SleepPatternHistoryStore(context).mergeAndLoad(
                recentObservations = recentSleepObservations,
                today = today
            )
        }.getOrElse { error ->
            FocusGuardLogger.logError(
                "AdvancedUsageAnalytics",
                "Falha ao atualizar o histórico local de sono estimado",
                error
            )
            recentSleepObservations
        }
        val historyDates = (0 until historyDays).map { dayOffset ->
            historyStartDate.plusDays(dayOffset.toLong())
        }
        val aggregatedDailyTotals = queryAggregatedDailyScreenTime(
            manager = manager,
            dates = historyDates,
            nowMs = now,
            zoneId = zoneId
        )
        val dailyTotals = historyDates.associateWith { date ->
            val dayStartMs = date.atStartOfDay(zoneId).toInstant().toEpochMilli()
            val dayEndMs = date.plusDays(1)
                .atStartOfDay(zoneId)
                .toInstant()
                .toEpochMilli()
                .coerceAtMost(now)
            val aggregatedTotalMs = aggregatedDailyTotals[date]

            resolveDailyScreenTime(
                aggregatedTotalMs = aggregatedTotalMs,
                detailedIntervals = detailedIntervals,
                rangeStartMs = dayStartMs,
                rangeEndMs = dayEndMs,
                activeIntervalStartTimeMs = activeIntervalStartTimeMs,
                includeActiveTail = date == today
            )
        }

        PhoneUsageInsightsCalculator.calculate(
            dailyScreenTimeByDate = dailyTotals,
            detailedIntervals = detailedIntervals,
            completePeriodDates = completePeriodDates,
            nowMs = now,
            historyDays = historyDays,
            zoneId = zoneId,
            locale = locale,
            historicalSleepObservations = sleepHistory
        )
    }

    private fun queryAggregatedDailyScreenTime(
        manager: UsageStatsManager,
        dates: List<LocalDate>,
        nowMs: Long,
        zoneId: ZoneId
    ): Map<LocalDate, Long> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return emptyMap()

        return buildMap {
            dates.forEach { date ->
                val startMs = date.atStartOfDay(zoneId).toInstant().toEpochMilli()
                val endMs = date.plusDays(1)
                    .atStartOfDay(zoneId)
                    .toInstant()
                    .toEpochMilli()
                    .coerceAtMost(nowMs)
                if (endMs <= startMs) {
                    put(date, 0L)
                    return@forEach
                }

                try {
                    val totalTimeMs = manager.queryEventStats(
                        UsageStatsManager.INTERVAL_DAILY,
                        startMs,
                        endMs
                    ).orEmpty()
                        .asSequence()
                        .filter { it.eventType == UsageEvents.Event.SCREEN_INTERACTIVE }
                        .sumOf { it.totalTime }
                        .coerceIn(0L, endMs - startMs)
                    put(date, totalTimeMs)
                } catch (error: Throwable) {
                    // A ausência desta chave ativa o fallback por eventos
                    // detalhados somente para o dia afetado.
                    FocusGuardLogger.logError(
                        "AdvancedUsageAnalytics",
                        "Falha em queryEventStats (screenInteractive)",
                        error
                    )
                }
            }
        }
    }

    suspend fun getMostUsedApps(startTime: Long, endTime: Long): List<AppUsageStat> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val key = "$startTime-$endTime"
        synchronized(cacheLock) {
            if (cachedMostUsed?.first == key && (now - lastMostUsedTime) < CACHE_TTL) {
                return@withContext cachedMostUsed!!.second
            }
        }

        if (usageStatsManager == null) return@withContext emptyList()
        val stats = try {
            usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startTime, endTime)
        } catch (e: Throwable) {
            FocusGuardLogger.logError("AdvancedUsageAnalytics", "Falha em queryUsageStats (mostUsed)", e)
            return@withContext emptyList()
        }
        val map = mutableMapOf<String, Long>()
        
        stats?.forEach { usage ->
            val hasLaunchIntent = try {
                pm.getLaunchIntentForPackage(usage.packageName) != null
            } catch (e: Throwable) {
                false
            }

            if (usage.totalTimeInForeground > 60000L && hasLaunchIntent) {
                map[usage.packageName] = (map[usage.packageName] ?: 0L) + usage.totalTimeInForeground
            }
        }
        val result = map.map { AppUsageStat(it.key, it.value) }.sortedByDescending { it.timeSpentMs }
        synchronized(cacheLock) {
            cachedMostUsed = key to result
            lastMostUsedTime = now
        }
        result
    }

    /**
     * Retorna acessos completos no intervalo: o app precisa entrar em primeiro
     * plano e depois ser deixado. Eventos de Activity não são somados
     * diretamente porque um único acesso pode abrir várias Activities.
     */
    suspend fun getMostOpenedApps(startTime: Long, endTime: Long): List<AppAccessStat> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val key = "$startTime-$endTime"
        synchronized(cacheLock) {
            if (cachedMostOpened?.first == key && (now - lastMostOpenedTime) < CACHE_TTL) {
                return@withContext cachedMostOpened!!.second
            }
        }

        if (usageStatsManager == null) return@withContext emptyList()
        val events = try {
            usageStatsManager.queryEvents(startTime, endTime)
        } catch (e: Throwable) {
            FocusGuardLogger.logError("AdvancedUsageAnalytics", "Falha em queryEvents", e)
            return@withContext emptyList()
        } ?: return@withContext emptyList()

        val event = UsageEvents.Event()
        val launchablePackages = mutableMapOf<String, Boolean>()
        val accessAccumulator = AppAccessAccumulator { packageName ->
            launchablePackages.getOrPut(packageName) {
                try {
                    pm.getLaunchIntentForPackage(packageName) != null
                } catch (error: Throwable) {
                    false
                }
            }
        }

        try {
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED ->
                        accessAccumulator.onActivityResumed(
                            packageName = event.packageName,
                            activityClassName = event.className
                        )

                    UsageEvents.Event.ACTIVITY_PAUSED ->
                        accessAccumulator.onActivityPaused(
                            packageName = event.packageName,
                            activityClassName = event.className
                        )

                    UsageEvents.Event.SCREEN_NON_INTERACTIVE,
                    UsageEvents.Event.KEYGUARD_SHOWN ->
                        accessAccumulator.onDeviceBecameInactive()
                }
            }
        } catch (error: Throwable) {
            // Em algumas ROMs OEM, hasNextEvent/getNextEvent podem lançar
            // exceções estranhas. Retorna o que já foi coletado.
            FocusGuardLogger.logError("AdvancedUsageAnalytics", "Falha no loop de eventos", error)
        }

        val result = accessAccumulator.finish()
            .map { (packageName, accessCount) ->
                AppAccessStat(packageName, accessCount)
            }
            .sortedWith(
                compareByDescending<AppAccessStat> { it.accessCount }
                    .thenBy { it.packageName }
            )

        synchronized(cacheLock) {
            cachedMostOpened = key to result
            lastMostOpenedTime = now
        }
        result
    }

    suspend fun getNeverUsedApps(startTime: Long, endTime: Long): List<String> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val key = "$startTime-$endTime"
        synchronized(cacheLock) {
            if (cachedNeverUsed?.first == key && (now - lastNeverUsedTime) < CACHE_TTL) {
                return@withContext cachedNeverUsed!!.second
            }
        }
        val allLaunchableApps = try {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .asSequence()
                .filter {
                    val hasLaunchIntent = try {
                        pm.getLaunchIntentForPackage(it.packageName) != null
                    } catch (e: Throwable) {
                        false
                    }
                    hasLaunchIntent && it.packageName != context.packageName
                }
                .map { it.packageName }
                .toSet()
        } catch (e: Throwable) {
            return@withContext emptyList()
        }

        if (usageStatsManager == null) return@withContext allLaunchableApps.toList()

        val stats = try {
            usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startTime, endTime)
        } catch (e: Throwable) {
            return@withContext allLaunchableApps.toList()
        }
        val usedApps = stats?.filter { it.totalTimeInForeground > 0 }?.map { it.packageName }?.toSet() ?: emptySet()

        // Limita a 50 resultados — em devices com centenas de apps instalados,
        // a lista "nunca usados" pode ser enorme e causar OOM ou UI lenta.
        val result = (allLaunchableApps - usedApps).take(50)
        synchronized(cacheLock) {
            cachedNeverUsed = key to result
            lastNeverUsedTime = now
        }
        result
    }
}
