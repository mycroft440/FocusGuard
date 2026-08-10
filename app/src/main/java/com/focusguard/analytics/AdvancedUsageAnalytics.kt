package com.focusguard.analytics

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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

class AdvancedUsageAnalytics(private val context: Context) {
    private val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
    private val pm = context.packageManager

    companion object {
        // [F3] Cache para reduzir I/O e overhead de CPU no Dashboard.
        // ANTIGO: vars mutáveis estáticas sem sincronização — race condition
        // se múltiplas threads acessassem concorrentemente.
        // NOVO: @Volatile + sincronização no acesso (read-then-write atômico
        // via @Synchronized methods). O custo de sincronização é desprezível
        // vs. o custo de queryUsageStats (10-50ms).
        @Volatile
        private var cachedPhoneUsage: Pair<String, PhoneUsageInsights>? = null
        @Volatile
        private var lastPhoneUsageTime: Long = 0

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
        val now = System.currentTimeMillis()
        val zoneId = ZoneId.systemDefault()
        val locale = Locale.getDefault()
        val today = Instant.ofEpochMilli(now).atZone(zoneId).toLocalDate()
        val cacheKey = listOf(
            historyDays,
            periodAverageDays,
            zoneId.id,
            locale.toLanguageTag(),
            today
        ).joinToString("|")

        synchronized(cacheLock) {
            if (
                cachedPhoneUsage?.first == cacheKey &&
                (now - lastPhoneUsageTime) < CACHE_TTL
            ) {
                return@withContext cachedPhoneUsage!!.second
            }
        }

        val emptyInsights = {
            PhoneUsageInsightsCalculator.calculate(
                intervals = emptyList(),
                nowMs = now,
                historyDays = historyDays,
                periodAverageDays = periodAverageDays,
                zoneId = zoneId,
                locale = locale
            )
        }
        val manager = usageStatsManager ?: return@withContext emptyInsights()

        // O gráfico usa hoje + 6 dias; os períodos de maior e menor uso usam os
        // 30 dias completos anteriores. Consultamos ainda um dia de aquecimento
        // para reconhecer uma sessão aberta na virada do primeiro dia.
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
            return@withContext emptyInsights()
        } ?: return@withContext emptyInsights()

        val homePackage = try {
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
            }
            pm.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo
                ?.packageName
        } catch (error: Throwable) {
            null
        }
        val eligibilityCache = mutableMapOf<String, Boolean>()
        val accumulator = PhoneUsageSessionAccumulator { packageName ->
            eligibilityCache.getOrPut(packageName) {
                when (packageName) {
                    homePackage,
                    "com.android.launcher",
                    "com.android.systemui" -> false

                    else -> try {
                        pm.getLaunchIntentForPackage(packageName) != null
                    } catch (error: Throwable) {
                        false
                    }
                }
            }
        }
        val event = UsageEvents.Event()
        var eventLoopCompleted = true
        var lastEventTimestamp = queryStart

        try {
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                lastEventTimestamp = maxOf(lastEventTimestamp, event.timeStamp)
                when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED ->
                        accumulator.onActivityResumed(
                            packageName = event.packageName,
                            activityClassName = event.className,
                            timestampMs = event.timeStamp
                        )

                    UsageEvents.Event.ACTIVITY_PAUSED ->
                        accumulator.onActivityPaused(
                            packageName = event.packageName,
                            activityClassName = event.className,
                            timestampMs = event.timeStamp
                        )

                    UsageEvents.Event.SCREEN_NON_INTERACTIVE,
                    UsageEvents.Event.KEYGUARD_SHOWN ->
                        accumulator.onDeviceBecameInactive(event.timeStamp)
                }
            }
        } catch (error: Throwable) {
            // OEMs podem interromper a iteração; os eventos já lidos ainda
            // formam sessões válidas e são preservados.
            eventLoopCompleted = false
            FocusGuardLogger.logError(
                "AdvancedUsageAnalytics",
                "Falha no loop de eventos (phoneUsage)",
                error
            )
        }

        val result = PhoneUsageInsightsCalculator.calculate(
            intervals = accumulator.finish(
                if (eventLoopCompleted) now else lastEventTimestamp
            ),
            nowMs = now,
            historyDays = historyDays,
            periodAverageDays = periodAverageDays,
            zoneId = zoneId,
            locale = locale
        )

        synchronized(cacheLock) {
            cachedPhoneUsage = cacheKey to result
            lastPhoneUsageTime = now
        }
        result
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
