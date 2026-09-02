package com.focusguard.usage

import android.app.usage.UsageStatsManager
import android.content.Context
import com.focusguard.database.AppDatabase
import com.focusguard.utils.AppUsageLimitActivationUsage
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

            // HARD_BLOCK_NO_PASSWORD é um bloqueio temporal imediato. Enquanto o
            // prazo absoluto estiver ativo, qualquer tentativa de abrir o app deve
            // mostrar impacto ancorado no createdAt real desta configuração.
            if (mode == "TIME") {
                val lockUntil = limit.lockUntilTimestamp ?: return@withContext false
                val blocked = lockUntil > System.currentTimeMillis()
                if (blocked) UsageInterventionStore.syncFromLimit(context, limit)
                return@withContext blocked
            }

            // Nos limitadores de uso comuns, a intervenção só passa a ser mostrada
            // quando o consumo posterior à ativação realmente atingiu o limite.
            // Uso anterior no mesmo dia fica fora da nova franquia.
            val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                ?: return@withContext false
            val now = System.currentTimeMillis()
            val startOfDay = Calendar.getInstance().apply {
                timeInMillis = now
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val dayUsageMillis = manager.queryAndAggregateUsageStats(
                startOfDay,
                now
            )[packageName]?.totalTimeInForeground ?: 0L
            val usedMillis = AppUsageLimitActivationUsage.effectiveUsageMillis(
                context = context,
                usageStatsManager = manager,
                limit = limit,
                currentDayUsageMillis = dayUsageMillis,
                dayStartMillis = startOfDay,
                nowMillis = now
            )

            val blocked = UsageLimitForegroundPolicy.usedMinutes(usedMillis) >=
                limit.dailyLimitMinutes
            if (blocked) UsageInterventionStore.syncFromLimit(context, limit)
            blocked
        }
}
