package com.focusguard.analytics

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

data class AppUsageStat(
    val packageName: String,
    val timeSpentMs: Long
)

data class AppEventStat(
    val packageName: String,
    val openCount: Int,
    val closeCount: Int
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
        // [F3] Cache para reduzir I/O e overhead de CPU no Dashboard
        private var cachedPhoneUsage: List<DailyPhoneUsage>? = null
        private var lastPhoneUsageTime: Long = 0
        
        private var cachedMostUsed: Pair<String, List<AppUsageStat>>? = null // Key: "start-end"
        private var lastMostUsedTime: Long = 0

        private var cachedEvents: Pair<String, List<AppEventStat>>? = null
        private var lastEventsTime: Long = 0
        
        private var cachedNeverUsed: Pair<String, List<String>>? = null
        private var lastNeverUsedTime: Long = 0

        private const val CACHE_TTL = 5 * 60 * 1000L // 5 minutos
    }

    suspend fun getPhoneUsageHistory(days: Int = 7): List<DailyPhoneUsage> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (cachedPhoneUsage != null && (now - lastPhoneUsageTime) < CACHE_TTL && cachedPhoneUsage!!.size >= days) {
            return@withContext cachedPhoneUsage!!.takeLast(days)
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
        } catch (e: Exception) {
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

        cachedPhoneUsage = result
        lastPhoneUsageTime = now
        result
    }

    suspend fun getMostUsedApps(startTime: Long, endTime: Long): List<AppUsageStat> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val key = "$startTime-$endTime"
        if (cachedMostUsed?.first == key && (now - lastMostUsedTime) < CACHE_TTL) {
            return@withContext cachedMostUsed!!.second
        }

        if (usageStatsManager == null) return@withContext emptyList()
        val stats = try {
            usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startTime, endTime)
        } catch (e: Exception) {
            return@withContext emptyList()
        }
        val map = mutableMapOf<String, Long>()
        
        stats?.forEach { usage ->
            val hasLaunchIntent = try {
                pm.getLaunchIntentForPackage(usage.packageName) != null
            } catch (e: Exception) {
                false
            }

            if (usage.totalTimeInForeground > 60000L && hasLaunchIntent) {
                map[usage.packageName] = (map[usage.packageName] ?: 0L) + usage.totalTimeInForeground
            }
        }
        val result = map.map { AppUsageStat(it.key, it.value) }.sortedByDescending { it.timeSpentMs }
        cachedMostUsed = key to result
        lastMostUsedTime = now
        result
    }

    suspend fun getAppOpenCloseCounts(startTime: Long, endTime: Long): List<AppEventStat> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val key = "$startTime-$endTime"
        if (cachedEvents?.first == key && (now - lastEventsTime) < CACHE_TTL) {
            return@withContext cachedEvents!!.second
        }

        if (usageStatsManager == null) return@withContext emptyList()
        val events = try {
            usageStatsManager.queryEvents(startTime, endTime)
        } catch (e: Exception) {
            return@withContext emptyList()
        } ?: return@withContext emptyList()

        val event = UsageEvents.Event()
        
        val openCounts = mutableMapOf<String, Int>()
        val closeCounts = mutableMapOf<String, Int>()

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val pkg = event.packageName ?: continue
            val hasLaunchIntent = try {
                pm.getLaunchIntentForPackage(pkg) != null
            } catch (e: Exception) {
                false
            }

            if (hasLaunchIntent) {
                when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> openCounts[pkg] = (openCounts[pkg] ?: 0) + 1
                    UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> closeCounts[pkg] = (closeCounts[pkg] ?: 0) + 1
                }
            }
        }

        val allPkgs = (openCounts.keys + closeCounts.keys).toSet()
        val result = allPkgs.map { pkg ->
            AppEventStat(pkg, openCounts[pkg] ?: 0, closeCounts[pkg] ?: 0)
        }.sortedByDescending { it.openCount }
        
        cachedEvents = key to result
        lastEventsTime = now
        result
    }

    suspend fun getNeverUsedApps(startTime: Long, endTime: Long): List<String> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val key = "$startTime-$endTime"
        if (cachedNeverUsed?.first == key && (now - lastNeverUsedTime) < CACHE_TTL) {
            return@withContext cachedNeverUsed!!.second
        }
        val allLaunchableApps = try {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter {
                    val hasLaunchIntent = try {
                        pm.getLaunchIntentForPackage(it.packageName) != null
                    } catch (e: Exception) {
                        false
                    }
                    hasLaunchIntent && it.packageName != context.packageName
                }
                .map { it.packageName }
                .toSet()
        } catch (e: Exception) {
            return@withContext emptyList()
        }

        if (usageStatsManager == null) return@withContext allLaunchableApps.toList()

        val stats = try {
            usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startTime, endTime)
        } catch (e: Exception) {
            return@withContext allLaunchableApps.toList()
        }
        val usedApps = stats?.filter { it.totalTimeInForeground > 0 }?.map { it.packageName }?.toSet() ?: emptySet()

        val result = (allLaunchableApps - usedApps).toList()
        cachedNeverUsed = key to result
        lastNeverUsedTime = now
        result
    }
}


