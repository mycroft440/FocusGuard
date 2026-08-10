package com.focusguard.analytics

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class DailyPhoneUsage(
    val dateLabel: String,
    val totalTimeMs: Long,
    val timestamp: Long
)

data class PhoneUsagePeriodAverage(
    val startHour: Int,
    val endHour: Int,
    val averageTimeMs: Long
)

data class PhoneUsagePeriodSummary(
    val mostUsed: PhoneUsagePeriodAverage,
    val leastUsed: PhoneUsagePeriodAverage,
    val daysAnalyzed: Int
)

data class PhoneUsageInsights(
    val dailyHistory: List<DailyPhoneUsage>,
    val periodSummary: PhoneUsagePeriodSummary?
)

internal data class ForegroundUsageInterval(
    val packageName: String,
    val startTimeMs: Long,
    val endTimeMs: Long
)

/**
 * Reconstrói o tempo real em primeiro plano a partir dos eventos de Activity.
 *
 * Uma pausa fica pendente até sabermos se houve apenas uma troca de Activity
 * dentro do mesmo aplicativo. Ao trocar de pacote, apagar/bloquear a tela ou
 * terminar a consulta, a sessão é encerrada no instante correto.
 */
internal class PhoneUsageSessionAccumulator(
    private val isEligibleApp: (String) -> Boolean
) {
    private data class ForegroundSession(
        val packageName: String,
        var activityClassName: String?,
        val startedAtMs: Long,
        val eligible: Boolean,
        var pendingExitAtMs: Long? = null
    )

    private val completedIntervals = mutableListOf<ForegroundUsageInterval>()
    private var foregroundSession: ForegroundSession? = null

    fun onActivityResumed(
        packageName: String?,
        activityClassName: String?,
        timestampMs: Long
    ) {
        val resumedPackage = packageName?.takeIf(String::isNotBlank) ?: return
        val current = foregroundSession

        if (current == null) {
            foregroundSession = newSession(resumedPackage, activityClassName, timestampMs)
            return
        }

        if (current.packageName == resumedPackage) {
            if (timestampMs < current.startedAtMs) return

            // A pausa anterior pertence à transição entre Activities do mesmo
            // app. Mantemos uma única sessão contínua de uso.
            current.activityClassName = activityClassName
            current.pendingExitAtMs = null
            return
        }

        val exitAt = current.pendingExitAtMs
            ?.coerceAtMost(timestampMs)
            ?: timestampMs
        completeCurrentSession(exitAt)
        foregroundSession = newSession(resumedPackage, activityClassName, timestampMs)
    }

    fun onActivityPaused(
        packageName: String?,
        activityClassName: String?,
        timestampMs: Long
    ) {
        val current = foregroundSession ?: return
        if (packageName != current.packageName || timestampMs < current.startedAtMs) return

        // Uma pausa atrasada da Activity anterior não pode encerrar a Activity
        // que já está visível.
        val belongsToCurrentActivity = current.activityClassName == null ||
            activityClassName == null ||
            current.activityClassName == activityClassName
        if (belongsToCurrentActivity) {
            current.pendingExitAtMs = current.pendingExitAtMs
                ?.coerceAtMost(timestampMs)
                ?: timestampMs
        }
    }

    fun onDeviceBecameInactive(timestampMs: Long) {
        val current = foregroundSession ?: return
        val exitAt = current.pendingExitAtMs
            ?.coerceAtMost(timestampMs)
            ?: timestampMs
        completeCurrentSession(exitAt)
    }

    /**
     * Para tempo de uso, ao contrário do número de acessos, o app que continua
     * aberto deve ser contabilizado até o fim da consulta.
     */
    fun finish(endTimeMs: Long): List<ForegroundUsageInterval> {
        val current = foregroundSession
        if (current != null) {
            val exitAt = current.pendingExitAtMs
                ?.coerceAtMost(endTimeMs)
                ?: endTimeMs
            completeCurrentSession(exitAt)
        }
        return completedIntervals.toList()
    }

    private fun newSession(
        packageName: String,
        activityClassName: String?,
        timestampMs: Long
    ) = ForegroundSession(
        packageName = packageName,
        activityClassName = activityClassName,
        startedAtMs = timestampMs,
        eligible = isEligibleApp(packageName)
    )

    private fun completeCurrentSession(endTimeMs: Long) {
        val completed = foregroundSession ?: return
        if (completed.eligible && endTimeMs > completed.startedAtMs) {
            completedIntervals += ForegroundUsageInterval(
                packageName = completed.packageName,
                startTimeMs = completed.startedAtMs,
                endTimeMs = endTimeMs
            )
        }
        foregroundSession = null
    }
}

internal object PhoneUsageInsightsCalculator {
    private const val PERIOD_HOURS = 3
    private const val PERIODS_PER_DAY = 24 / PERIOD_HOURS

    fun calculate(
        intervals: List<ForegroundUsageInterval>,
        nowMs: Long,
        historyDays: Int,
        periodAverageDays: Int,
        zoneId: ZoneId,
        locale: Locale
    ): PhoneUsageInsights {
        require(historyDays > 0) { "historyDays must be positive" }
        require(periodAverageDays > 0) { "periodAverageDays must be positive" }

        val today = Instant.ofEpochMilli(nowMs).atZone(zoneId).toLocalDate()
        val historyStart = today.minusDays(historyDays - 1L)
        val dayFormatter = DateTimeFormatter.ofPattern("EEE", locale)

        val dailyHistory = (0 until historyDays).map { dayOffset ->
            val date = historyStart.plusDays(dayOffset.toLong())
            val dayStartMs = date.atStartOfDay(zoneId).toInstant().toEpochMilli()
            val naturalDayEndMs = date.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
            val dayEndMs = naturalDayEndMs.coerceAtMost(nowMs)

            DailyPhoneUsage(
                dateLabel = dayFormatter.format(date).uppercase(locale),
                totalTimeMs = totalOverlap(intervals, dayStartMs, dayEndMs),
                timestamp = dayStartMs
            )
        }

        val periodTotals = LongArray(PERIODS_PER_DAY)
        val firstCompleteDay = today.minusDays(periodAverageDays.toLong())

        repeat(periodAverageDays) { dayOffset ->
            val date = firstCompleteDay.plusDays(dayOffset.toLong())
            repeat(PERIODS_PER_DAY) { periodIndex ->
                val startHour = periodIndex * PERIOD_HOURS
                val endHour = startHour + PERIOD_HOURS
                val periodStartMs = date.atHour(startHour, zoneId)
                val periodEndMs = if (endHour == 24) {
                    date.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
                } else {
                    date.atHour(endHour, zoneId)
                }
                periodTotals[periodIndex] += totalOverlap(
                    intervals,
                    periodStartMs,
                    periodEndMs
                )
            }
        }

        val periodSummary = if (periodTotals.sum() == 0L) {
            null
        } else {
            // maxByOrNull/minByOrNull preservam o primeiro índice nos empates,
            // tornando o resultado estável e escolhendo o período mais cedo.
            val mostUsedIndex = periodTotals.indices.maxByOrNull { periodTotals[it] } ?: 0
            val leastUsedIndex = periodTotals.indices.minByOrNull { periodTotals[it] } ?: 0

            PhoneUsagePeriodSummary(
                mostUsed = periodAverage(
                    periodIndex = mostUsedIndex,
                    totalTimeMs = periodTotals[mostUsedIndex],
                    days = periodAverageDays
                ),
                leastUsed = periodAverage(
                    periodIndex = leastUsedIndex,
                    totalTimeMs = periodTotals[leastUsedIndex],
                    days = periodAverageDays
                ),
                daysAnalyzed = periodAverageDays
            )
        }

        return PhoneUsageInsights(
            dailyHistory = dailyHistory,
            periodSummary = periodSummary
        )
    }

    private fun LocalDate.atHour(hour: Int, zoneId: ZoneId): Long =
        atTime(hour, 0).atZone(zoneId).toInstant().toEpochMilli()

    private fun periodAverage(
        periodIndex: Int,
        totalTimeMs: Long,
        days: Int
    ) = PhoneUsagePeriodAverage(
        startHour = periodIndex * PERIOD_HOURS,
        endHour = (periodIndex + 1) * PERIOD_HOURS,
        averageTimeMs = totalTimeMs / days
    )

    private fun totalOverlap(
        intervals: List<ForegroundUsageInterval>,
        rangeStartMs: Long,
        rangeEndMs: Long
    ): Long {
        if (rangeEndMs <= rangeStartMs) return 0L

        return intervals.sumOf { interval ->
            val overlapStart = maxOf(interval.startTimeMs, rangeStartMs)
            val overlapEnd = minOf(interval.endTimeMs, rangeEndMs)
            (overlapEnd - overlapStart).coerceAtLeast(0L)
        }
    }
}
