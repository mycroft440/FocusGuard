package com.focusguard.usage

import android.app.usage.UsageStatsManager
import android.content.Context
import com.focusguard.database.AppDatabase
import com.focusguard.utils.UsageLimitForegroundPolicy
import java.util.Calendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Decide se um bloqueio de app deve abrir a comparação antes/depois.
 * Bloqueios por senha e Pomodoro não passam por este roteador.
 */
object UsageImpactRouter {
    suspend fun shouldShowForBlockedApp(context: Context, packageName: String): Boolean =
        withContext(Dispatchers.IO) {
            val limit = AppDatabase.getDatabase(context)
                .appUsageLimitDao()
                .getAllStatic()
                .firstOrNull { it.packageName == packageName }
                ?: return@withContext false

            if (!limit.isEnabled || !limit.preventOpeningAfterLimit) return@withContext false
            val mode = limit.lockMode.trim().uppercase()
            if (mode == "PASSWORD" || mode == "WARNING") return@withContext false
            if (mode == "TIME" &&
                limit.lockUntilTimestamp != null &&
                limit.lockUntilTimestamp <= System.currentTimeMillis()
            ) return@withContext false

            val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                ?: return@withContext false
            val startOfDay = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val usedMillis = manager.queryAndAggregateUsageStats(
                startOfDay,
                System.currentTimeMillis()
            )[packageName]?.totalTimeInForeground ?: 0L

            UsageLimitForegroundPolicy.usedMinutes(usedMillis) >= limit.dailyLimitMinutes
        }
}
