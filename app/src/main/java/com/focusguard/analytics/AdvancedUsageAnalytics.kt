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

    suspend fun getPhoneUsageHistory(days: Int = 7): List<DailyPhoneUsage> = withContext(Dispatchers.IO) {
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

        val stats = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startTime, endTime)
        
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
        
        dailyMap.map { (key, value) ->
            val parts = key.split("|")
            DailyPhoneUsage(parts[1], value, parts[0].toLong())
        }.sortedBy { it.timestamp }.takeLast(days)
    }

    suspend fun getMostUsedApps(startTime: Long, endTime: Long): List<AppUsageStat> = withContext(Dispatchers.IO) {
        if (usageStatsManager == null) return@withContext emptyList()
        val stats = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startTime, endTime)
        val map = mutableMapOf<String, Long>()
        
        stats?.forEach { usage ->
            if (usage.totalTimeInForeground > 60000L && pm.getLaunchIntentForPackage(usage.packageName) != null) {
                map[usage.packageName] = (map[usage.packageName] ?: 0L) + usage.totalTimeInForeground
            }
        }
        map.map { AppUsageStat(it.key, it.value) }.sortedByDescending { it.timeSpentMs }
    }

    suspend fun getAppOpenCloseCounts(startTime: Long, endTime: Long): List<AppEventStat> = withContext(Dispatchers.IO) {
        if (usageStatsManager == null) return@withContext emptyList()
        val events = usageStatsManager.queryEvents(startTime, endTime)
        val event = UsageEvents.Event()
        
        val openCounts = mutableMapOf<String, Int>()
        val closeCounts = mutableMapOf<String, Int>()

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val pkg = event.packageName
            if (pm.getLaunchIntentForPackage(pkg) != null) {
                when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> openCounts[pkg] = (openCounts[pkg] ?: 0) + 1
                    UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> closeCounts[pkg] = (closeCounts[pkg] ?: 0) + 1
                }
            }
        }

        val allPkgs = openCounts.keys + closeCounts.keys
        allPkgs.map { pkg ->
            AppEventStat(pkg, openCounts[pkg] ?: 0, closeCounts[pkg] ?: 0)
        }.sortedByDescending { it.openCount }
    }

    suspend fun getNeverUsedApps(startTime: Long, endTime: Long): List<String> = withContext(Dispatchers.IO) {
        val allLaunchableApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null && it.packageName != context.packageName }
            .map { it.packageName }
            .toSet()

        if (usageStatsManager == null) return@withContext allLaunchableApps.toList()

        val stats = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startTime, endTime)
        val usedApps = stats?.filter { it.totalTimeInForeground > 0 }?.map { it.packageName }?.toSet() ?: emptySet()

        (allLaunchableApps - usedApps).toList()
    }
}
