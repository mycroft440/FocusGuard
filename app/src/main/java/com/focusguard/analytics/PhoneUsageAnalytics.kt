package com.focusguard.analytics

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal const val PHONE_USAGE_PERIOD_ANALYSIS_DAYS = 30

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
    val periodSummary: PhoneUsagePeriodSummary?,
    val completeDaysAverageMs: Long = 0L,
    val completeDaysAnalyzed: Int = 0
)

internal data class ScreenInteractiveInterval(
    val startTimeMs: Long,
    val endTimeMs: Long
)

/**
 * Reconstrói os intervalos detalhados em que a tela esteve interativa.
 *
 * Os eventos detalhados são usados somente nos cartões por faixa de horário,
 * pois o Android os mantém por poucos dias. O histórico diário vem dos dados
 * agregados de [android.app.usage.UsageStatsManager.queryEventStats].
 */
internal class ScreenInteractiveSessionAccumulator {
    private val completedIntervals = mutableListOf<ScreenInteractiveInterval>()
    private var interactiveSinceMs: Long? = null

    var firstObservedEventTimeMs: Long? = null
        private set

    val activeIntervalStartTimeMs: Long?
        get() = interactiveSinceMs

    fun onScreenInteractive(timestampMs: Long) {
        observe(timestampMs)
        if (interactiveSinceMs == null) {
            interactiveSinceMs = timestampMs
        }
    }

    fun onScreenNonInteractive(timestampMs: Long) {
        observe(timestampMs)
        val startedAtMs = interactiveSinceMs ?: return
        if (timestampMs > startedAtMs) {
            completedIntervals += ScreenInteractiveInterval(
                startTimeMs = startedAtMs,
                endTimeMs = timestampMs
            )
        }
        interactiveSinceMs = null
    }

    fun finish(endTimeMs: Long): List<ScreenInteractiveInterval> {
        val startedAtMs = interactiveSinceMs
        if (startedAtMs != null && endTimeMs > startedAtMs) {
            completedIntervals += ScreenInteractiveInterval(
                startTimeMs = startedAtMs,
                endTimeMs = endTimeMs
            )
        }
        interactiveSinceMs = null
        return completedIntervals.toList()
    }

    private fun observe(timestampMs: Long) {
        firstObservedEventTimeMs = firstObservedEventTimeMs
            ?.coerceAtMost(timestampMs)
            ?: timestampMs
    }
}

internal fun completeDatesCoveredByDetailedEvents(
    firstObservedEventTimeMs: Long?,
    lastObservedEventTimeMs: Long,
    eventLoopCompleted: Boolean,
    periodStartDate: LocalDate,
    today: LocalDate,
    zoneId: ZoneId
): List<LocalDate> {
    val firstEventDate = firstObservedEventTimeMs
        ?.let { Instant.ofEpochMilli(it).atZone(zoneId).toLocalDate() }
        ?: return emptyList()

    // O primeiro dia pode começar antes do primeiro evento ainda retido e não
    // é confiável. Se a iteração falhou, o dia do último evento também pode
    // estar incompleto. Só os dias estritamente entre esses limites entram.
    var date = maxOf(periodStartDate, firstEventDate.plusDays(1))
    val endExclusive = if (eventLoopCompleted) {
        today
    } else {
        minOf(
            today,
            Instant.ofEpochMilli(lastObservedEventTimeMs)
                .atZone(zoneId)
                .toLocalDate()
        )
    }
    val result = mutableListOf<LocalDate>()
    while (date < endExclusive) {
        result += date
        date = date.plusDays(1)
    }
    return result
}

internal fun resolveDailyScreenTime(
    aggregatedTotalMs: Long?,
    detailedIntervals: List<ScreenInteractiveInterval>,
    rangeStartMs: Long,
    rangeEndMs: Long,
    activeIntervalStartTimeMs: Long?,
    includeActiveTail: Boolean
): Long {
    val maximumPossibleTimeMs = (rangeEndMs - rangeStartMs).coerceAtLeast(0L)
    if (aggregatedTotalMs == null) {
        return PhoneUsageInsightsCalculator.totalOverlap(
            intervals = detailedIntervals,
            rangeStartMs = rangeStartMs,
            rangeEndMs = rangeEndMs
        ).coerceAtMost(maximumPossibleTimeMs)
    }

    // EventStats fecha a duração no evento SCREEN_NON_INTERACTIVE. Para hoje,
    // a sessão de tela que continua ativa precisa ser acrescentada à parte.
    val activeTailMs = if (includeActiveTail) {
        activeIntervalStartTimeMs
            ?.let { rangeEndMs - maxOf(it, rangeStartMs) }
            ?.coerceAtLeast(0L)
            ?: 0L
    } else {
        0L
    }
    return (aggregatedTotalMs + activeTailMs)
        .coerceIn(0L, maximumPossibleTimeMs)
}

internal object PhoneUsageInsightsCalculator {
    private const val PERIOD_HOURS = 3
    private const val PERIODS_PER_DAY = 24 / PERIOD_HOURS

    fun calculate(
        dailyScreenTimeByDate: Map<LocalDate, Long>,
        detailedIntervals: List<ScreenInteractiveInterval>,
        completePeriodDates: List<LocalDate>,
        nowMs: Long,
        historyDays: Int,
        zoneId: ZoneId,
        locale: Locale
    ): PhoneUsageInsights {
        require(historyDays > 0) { "historyDays must be positive" }

        val today = Instant.ofEpochMilli(nowMs).atZone(zoneId).toLocalDate()
        val historyStart = today.minusDays(historyDays - 1L)
        val dayFormatter = DateTimeFormatter.ofPattern("EEE", locale)

        val dailyHistory = (0 until historyDays).map { dayOffset ->
            val date = historyStart.plusDays(dayOffset.toLong())
            val dayStartMs = date.atStartOfDay(zoneId).toInstant().toEpochMilli()
            val naturalDayEndMs = date.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
            val dayEndMs = naturalDayEndMs.coerceAtMost(nowMs)
            val maximumPossibleTimeMs = (dayEndMs - dayStartMs).coerceAtLeast(0L)

            DailyPhoneUsage(
                dateLabel = dayFormatter.format(date).uppercase(locale),
                totalTimeMs = dailyScreenTimeByDate[date]
                    ?.coerceIn(0L, maximumPossibleTimeMs)
                    ?: 0L,
                timestamp = dayStartMs
            )
        }

        // Hoje ainda está em andamento. A média usa apenas dias encerrados
        // para não cair artificialmente pela manhã e subir ao longo do dia.
        val completeHistory = dailyHistory.dropLast(1)
        val completeDaysAverageMs = if (completeHistory.isEmpty()) {
            0L
        } else {
            completeHistory.sumOf(DailyPhoneUsage::totalTimeMs) / completeHistory.size
        }

        val analyzedDates = completePeriodDates
            .asSequence()
            .filter { it < today }
            .distinct()
            .sorted()
            .toList()
        val periodTotals = LongArray(PERIODS_PER_DAY)

        analyzedDates.forEach { date ->
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
                    intervals = detailedIntervals,
                    rangeStartMs = periodStartMs,
                    rangeEndMs = periodEndMs
                )
            }
        }

        val periodSummary = if (analyzedDates.isEmpty() || periodTotals.sum() == 0L) {
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
                    days = analyzedDates.size
                ),
                leastUsed = periodAverage(
                    periodIndex = leastUsedIndex,
                    totalTimeMs = periodTotals[leastUsedIndex],
                    days = analyzedDates.size
                ),
                daysAnalyzed = analyzedDates.size
            )
        }

        return PhoneUsageInsights(
            dailyHistory = dailyHistory,
            periodSummary = periodSummary,
            completeDaysAverageMs = completeDaysAverageMs,
            completeDaysAnalyzed = completeHistory.size
        )
    }

    fun totalOverlap(
        intervals: List<ScreenInteractiveInterval>,
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
}
