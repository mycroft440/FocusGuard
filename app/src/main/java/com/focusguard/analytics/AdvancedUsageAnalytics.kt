package com.focusguard.analytics

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import com.focusguard.utils.FocusGuardLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
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

data class AppUsageRankings(
    val month: List<AppUsageStat> = emptyList(),
    val monthCompletedDays: List<AppUsageStat> = emptyList(),
    /** Dias encerrados do mês com dados: o divisor da média por dia. */
    val monthDaysWithData: Int = 0,
    val today: List<AppUsageStat> = emptyList(),
    val usedLastSevenDays: Set<String> = emptySet()
)

class AdvancedUsageAnalytics(private val context: Context) {
    private val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
    private val pm = context.packageManager

    companion object {
        // Cache somente da lista de acessos. O uso de tela e os rankings não são
        // armazenados aqui: precisam avançar enquanto a tela permanece ativa e
        // são refeitos toda vez que o Dashboard volta ao primeiro plano.
        @Volatile
        private var cachedMostOpened: Pair<String, List<AppAccessStat>>? = null
        @Volatile
        private var lastMostOpenedTime: Long = 0

        private const val CACHE_TTL = 5 * 60 * 1000L // 5 minutos
        private const val NEVER_USED_DAYS = 7L
        private const val MAX_NEVER_USED_APPS = 50
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

        // Os eventos detalhados dão o total exato de cada dia (meia-noite a
        // meia-noite), a distribuição por hora e os resumos noturnos. Cada dia
        // encerrado medido por eles fica gravado e não muda mais; o histórico
        // agregado só estima os dias anteriores ao registro.
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
        val dailyHistoryStore = runCatching { DailyScreenTimeHistoryStore(context) }
            .onFailure { error ->
                FocusGuardLogger.logError(
                    "AdvancedUsageAnalytics",
                    "Falha ao abrir o registro de uso diário",
                    error
                )
            }
            .getOrNull()
        val storedCompleteDays = dailyHistoryStore
            ?.let { store -> runCatching { store.load() }.getOrNull() }
            .orEmpty()
        val resolution = resolveDailyScreenTimes(
            dates = historyDates,
            today = today,
            nowMs = now,
            zoneId = zoneId,
            storedCompleteDays = storedCompleteDays,
            completeDatesCoveredByEvents = completePeriodDates,
            // Os eventos de tela ligada/desligada só existem a partir do Android 9.
            // Antes disso, nenhum intervalo não quer dizer zero de uso: é sem dados.
            detailedEventsAvailable = eventLoopCompleted &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.P,
            detailedIntervals = detailedIntervals,
            aggregatedBuckets = queryAggregatedScreenBuckets(
                manager = manager,
                startMs = historyStartDate.atStartOfDay(zoneId).toInstant().toEpochMilli(),
                endMs = now
            )
        )
        if (dailyHistoryStore != null && resolution.newlyMeasuredCompleteDays.isNotEmpty()) {
            runCatching {
                dailyHistoryStore.mergeAndLoad(
                    stored = storedCompleteDays,
                    newlyMeasured = resolution.newlyMeasuredCompleteDays,
                    today = today
                )
            }.onFailure { error ->
                FocusGuardLogger.logError(
                    "AdvancedUsageAnalytics",
                    "Falha ao gravar o uso dos dias encerrados",
                    error
                )
            }
        }
        val dailyTotals = resolution.totalsByDate

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

    /**
     * Baldes do histórico agregado de tela ativa, usados só para dias sem eventos
     * detalhados nem valor gravado. Cada balde vem com o próprio início e fim, para
     * nunca ser somado inteiro num dia que ele só cruza.
     */
    private fun queryAggregatedScreenBuckets(
        manager: UsageStatsManager,
        startMs: Long,
        endMs: Long
    ): List<AggregatedScreenBucket> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || endMs <= startMs) {
            return emptyList()
        }
        return try {
            manager.queryEventStats(UsageStatsManager.INTERVAL_DAILY, startMs, endMs)
                .orEmpty()
                .asSequence()
                .filter { it.eventType == UsageEvents.Event.SCREEN_INTERACTIVE }
                .map { stats ->
                    AggregatedScreenBucket(
                        beginTimeMs = stats.firstTimeStamp,
                        endTimeMs = stats.lastTimeStamp,
                        interactiveTimeMs = stats.totalTime
                    )
                }
                .toList()
        } catch (error: Throwable) {
            FocusGuardLogger.logError(
                "AdvancedUsageAnalytics",
                "Falha em queryEventStats (screenInteractive)",
                error
            )
            emptyList()
        }
    }

    /**
     * Rankings de apps do painel, todos do mesmo histórico diário exato: o mês até
     * agora, só os dias encerrados do mês (base da média por dia), hoje e os apps
     * usados nos últimos 7 dias (hoje e os 6 anteriores).
     */
    suspend fun getAppUsageRankings(): AppUsageRankings = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val zoneId = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(now).atZone(zoneId).toLocalDate()
        val manager = usageStatsManager ?: return@withContext AppUsageRankings()

        val monthPastDates = (1 until today.dayOfMonth).map { day -> today.withDayOfMonth(day) }
        val lastWeekPastDates = (1 until NEVER_USED_DAYS).map { today.minusDays(it) }
        val launchablePackages = mutableMapOf<String, Boolean>()
        val history = AppUsageHistoryLoader.load(
            context = context,
            manager = manager,
            dates = (monthPastDates + lastWeekPastDates).distinct(),
            nowMs = now,
            zoneId = zoneId,
            isEligible = { packageName ->
                launchablePackages.getOrPut(packageName) {
                    runCatching { pm.getLaunchIntentForPackage(packageName) != null }
                        .getOrDefault(false)
                }
            }
        )

        val completedDays = sumUsage(monthPastDates.mapNotNull { history.byDate[it] })
        AppUsageRankings(
            month = sumUsage(listOf(completedDays, history.today)).toRanking(),
            monthCompletedDays = completedDays.toRanking(),
            monthDaysWithData = monthPastDates.count { it in history.byDate },
            today = history.today.toRanking(),
            usedLastSevenDays = buildSet {
                addAll(history.today.keys)
                lastWeekPastDates.forEach { date -> history.byDate[date]?.keys?.let(::addAll) }
            }
        )
    }

    private fun sumUsage(days: List<Map<String, Long>>): Map<String, Long> {
        val totals = linkedMapOf<String, Long>()
        days.forEach { day ->
            day.forEach { (packageName, ms) -> totals[packageName] = (totals[packageName] ?: 0L) + ms }
        }
        return totals
    }

    private fun Map<String, Long>.toRanking(): List<AppUsageStat> = entries
        .filter { it.value > 0L }
        .map { AppUsageStat(it.key, it.value) }
        .sortedWith(
            compareByDescending<AppUsageStat> { it.timeSpentMs }.thenBy { it.packageName }
        )

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
                // As telas de bloqueio do HardBlock não são um acesso ao app.
                val surface = BlockSurfaceUsage.isSurface(
                    event.packageName,
                    event.className,
                    context.packageName
                )
                when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED ->
                        if (surface) {
                            accessAccumulator.onBlockSurfaceShown(event.timeStamp)
                        } else {
                            accessAccumulator.onActivityResumed(
                                packageName = event.packageName,
                                activityClassName = event.className,
                                timestampMs = event.timeStamp
                            )
                        }

                    UsageEvents.Event.ACTIVITY_PAUSED ->
                        if (!surface) {
                            accessAccumulator.onActivityPaused(
                                packageName = event.packageName,
                                activityClassName = event.className
                            )
                        }

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

    /** Apps que podem ser abertos e não aparecem em [usedPackages], em ordem de nome. */
    suspend fun getNeverUsedApps(usedPackages: Set<String>): List<String> =
        withContext(Dispatchers.IO) {
            val launchable = try {
                pm.queryIntentActivities(
                    android.content.Intent(android.content.Intent.ACTION_MAIN)
                        .addCategory(android.content.Intent.CATEGORY_LAUNCHER),
                    0
                ).mapNotNull { it.activityInfo }
                    .filter { it.packageName != context.packageName }
                    .associateBy({ it.packageName }, { it.loadLabel(pm).toString() })
            } catch (error: Throwable) {
                FocusGuardLogger.logError(
                    "AdvancedUsageAnalytics",
                    "Falha ao listar apps (nunca usados)",
                    error
                )
                return@withContext emptyList()
            }
            launchable
                .filterKeys { it !in usedPackages }
                .entries
                .sortedWith(compareBy({ it.value.lowercase() }, { it.key }))
                .take(MAX_NEVER_USED_APPS)
                .map { it.key }
        }
}
