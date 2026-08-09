package com.focusguard.analytics

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import com.focusguard.utils.FocusGuardLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

data class AppUsageStat(
    val packageName: String,
    val timeSpentMs: Long
)

data class AppAccessStat(
    val packageName: String,
    val accessCount: Int
)

data class DailyPhoneUsage(
    val dateLabel: String,
    val totalTimeMs: Long,
    val timestamp: Long
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
        private var cachedPhoneUsage: List<DailyPhoneUsage>? = null
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

    suspend fun getPhoneUsageHistory(days: Int = 7): List<DailyPhoneUsage> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        // Cache read — synchronized para race condition com writes concorrentes
        synchronized(cacheLock) {
            if (cachedPhoneUsage != null && (now - lastPhoneUsageTime) < CACHE_TTL && cachedPhoneUsage!!.size >= days) {
                return@withContext cachedPhoneUsage!!.takeLast(days)
            }
        }
        
        if (usageStatsManager == null) return@withContext emptyList()
        
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, -(days - 1))
        }
        val startTime = cal.timeInMillis
        val endTime = System.currentTimeMillis()

        val stats = try {
            usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startTime, endTime)
        } catch (e: Throwable) {
            FocusGuardLogger.logError("AdvancedUsageAnalytics", "Falha em queryUsageStats (phoneUsage)", e)
            return@withContext emptyList()
        }

        val dailyMap = mutableMapOf<String, Long>()
        val dateFormat = java.text.SimpleDateFormat("E", java.util.Locale.getDefault())

        stats?.forEach { usage ->
            if (usage.totalTimeInForeground > 0) {
                if (usage.packageName != "com.android.launcher" && usage.packageName != "com.android.systemui") {
                     val dateCal = Calendar.getInstance().apply { timeInMillis = usage.firstTimeStamp }
                     dateCal.set(Calendar.HOUR_OF_DAY, 0)
                     dateCal.set(Calendar.MINUTE, 0)
                     dateCal.set(Calendar.SECOND, 0)
                     dateCal.set(Calendar.MILLISECOND, 0)
                     
                     val label = dateFormat.format(dateCal.time).uppercase()
                     val timestampKey = dateCal.timeInMillis.toString()
                     val compositeKey = "$timestampKey|$label"
                     
                     dailyMap[compositeKey] = (dailyMap[compositeKey] ?: 0L) + usage.totalTimeInForeground
                }
            }
        }
        
        val result = dailyMap.mapNotNull { (key, value) ->
            val parts = key.split("|")
            val timestamp = parts.getOrNull(0)?.toLongOrNull()
            val label = parts.getOrNull(1)
            if (timestamp == null || label == null) null else DailyPhoneUsage(label, value, timestamp)
        }.sortedBy { it.timestamp }.takeLast(days)

        synchronized(cacheLock) {
            cachedPhoneUsage = result
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

