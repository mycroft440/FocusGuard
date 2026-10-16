package com.focusguard.analytics

import android.content.Context
import com.focusguard.utils.SecurePrefsManager
import java.time.LocalDate

/**
 * Regras do registro de tela ativa por dia encerrado.
 *
 * Um dia encerrado é gravado uma única vez (o valor medido pelos eventos, ou a
 * estimativa estável quando já não há eventos) e a partir daí é o valor mostrado
 * para sempre: o Android apaga os eventos depois de poucos dias e o histórico
 * agregado dele não respeita a meia-noite, então recalcular depois só poderia
 * piorar e mudar um dia que já passou.
 */
internal object DailyScreenTimeHistory {
    const val RETENTION_DAYS = 60L
    private const val SEPARATOR = "|"
    private const val MAX_DAY_MS = 25L * 60L * 60L * 1_000L

    /**
     * O que já estava gravado vence; só entram dias encerrados (antes de [today])
     * ainda ausentes. Dias fora da retenção saem.
     */
    fun merge(
        stored: Map<LocalDate, Long>,
        newlyMeasured: Map<LocalDate, Long>,
        today: LocalDate
    ): Map<LocalDate, Long> {
        val cutoffDate = today.minusDays(RETENTION_DAYS)
        val merged = sortedMapOf<LocalDate, Long>()
        stored.forEach { (date, totalMs) ->
            if (date in cutoffDate..today.minusDays(1)) merged[date] = totalMs
        }
        newlyMeasured.forEach { (date, totalMs) ->
            if (date in cutoffDate..today.minusDays(1) && date !in merged) {
                merged[date] = totalMs.coerceIn(0L, MAX_DAY_MS)
            }
        }
        return merged
    }

    fun encode(date: LocalDate, totalMs: Long): String = "$date$SEPARATOR$totalMs"

    fun decode(value: String): Pair<LocalDate, Long>? = runCatching {
        val parts = value.split(SEPARATOR)
        if (parts.size != 2) return@runCatching null
        val totalMs = parts[1].toLong()
        if (totalMs !in 0L..MAX_DAY_MS) return@runCatching null
        LocalDate.parse(parts[0]) to totalMs
    }.getOrNull()
}

/** Guarda os totais diários já medidos, criptografados como o histórico de sono. */
internal class DailyScreenTimeHistoryStore(context: Context) {
    private val preferences = SecurePrefsManager(context).prefs

    fun load(): Map<LocalDate, Long> {
        // v1 contava também a tela ligada na tela de bloqueio. Os dias dela não
        // podem se misturar com os de agora: são refeitos pelos eventos.
        if (preferences.contains(KEY_DAILY_TOTALS_V1)) {
            preferences.edit().remove(KEY_DAILY_TOTALS_V1).apply()
        }
        return loadCurrent()
    }

    private fun loadCurrent(): Map<LocalDate, Long> = preferences
        .getStringSet(KEY_DAILY_TOTALS, emptySet())
        .orEmpty()
        .mapNotNull(DailyScreenTimeHistory::decode)
        .toMap()

    /** Grava os dias novos e devolve o registro completo, já com eles. */
    fun mergeAndLoad(
        stored: Map<LocalDate, Long>,
        newlyMeasured: Map<LocalDate, Long>,
        today: LocalDate
    ): Map<LocalDate, Long> {
        val merged = DailyScreenTimeHistory.merge(stored, newlyMeasured, today)
        if (merged != stored) {
            preferences.edit()
                .putStringSet(
                    KEY_DAILY_TOTALS,
                    merged.mapTo(mutableSetOf()) { (date, totalMs) ->
                        DailyScreenTimeHistory.encode(date, totalMs)
                    }
                )
                .apply()
        }
        return merged
    }

    private companion object {
        const val KEY_DAILY_TOTALS_V1 = "analytics_daily_screen_time_v1"
        const val KEY_DAILY_TOTALS = "analytics_daily_screen_time_v2"
    }
}
