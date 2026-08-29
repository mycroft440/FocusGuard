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

            // HARD_BLOCK_NO_PASSWORD é um bloqueio temporal imediato. Enquanto o
            // prazo absoluto estiver ativo, qualquer tentativa de abrir o app deve
            // poder mostrar a comparação de impacto, independentemente dos minutos
            // de uso acumulados no dia.
            if (mode == "TIME") {
                val lockUntil = limit.lockUntilTimestamp ?: return@withContext false
                return@withContext lockUntil > System.currentTimeMillis()
            }

            // Nos limitadores de uso comuns, o bloqueio só aconteceu de fato quando
            // o consumo diário atingiu o limite configurado.
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
