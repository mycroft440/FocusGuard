package com.focusguard.analytics

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal const val PHONE_USAGE_PERIOD_ANALYSIS_DAYS = 30
internal const val MIN_PHONE_USAGE_PATTERN_DAYS = 3

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

data class PhoneUsageHourAverage(
    val hour: Int,
    val averageTimeMs: Long
)

data class PhoneUsageInsights(
    val dailyHistory: List<DailyPhoneUsage>,
    val periodSummary: PhoneUsagePeriodSummary?,
    val completeDaysAverageMs: Long = 0L,
    val completeDaysAnalyzed: Int = 0,
    val hourlyProfile: List<PhoneUsageHourAverage> = emptyList(),
    val estimatedSleepWindow: EstimatedSleepWindow? = null,
    val sleepNightsAvailable: Int = 0
)

internal data class ScreenInteractiveInterval(
    val startTimeMs: Long,
    val endTimeMs: Long
)

/**
 * Reconstrói os intervalos detalhados em que a tela esteve interativa.
 *
 * Os eventos detalhados alimentam os padrões por hora e o sono estimado, pois
 * o Android os mantém por poucos dias. O histórico diário vem dos dados
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
    private const val HOURS_PER_DAY = 24
    private const val PEAK_USAGE_WINDOW_HOURS = 3

    fun calculate(
        dailyScreenTimeByDate: Map<LocalDate, Long>,
        detailedIntervals: List<ScreenInteractiveInterval>,
        completePeriodDates: List<LocalDate>,
        nowMs: Long,
        historyDays: Int,
        zoneId: ZoneId,
        locale: Locale,
        historicalSleepObservations: List<NightlySleepObservation> = emptyList()
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
        val hourlyTotals = LongArray(HOURS_PER_DAY)

        analyzedDates.forEach { date ->
            repeat(HOURS_PER_DAY) { hour ->
                val startHour = hour
                val endHour = hour + 1
                val periodStartMs = date.atHour(startHour, zoneId)
                val periodEndMs = if (endHour == 24) {
                    date.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
                } else {
                    date.atHour(endHour, zoneId)
                }
                hourlyTotals[hour] += totalOverlap(
                    intervals = detailedIntervals,
                    rangeStartMs = periodStartMs,
                    rangeEndMs = periodEndMs
                )
            }
        }

        val hasReliableHourlyPattern =
            analyzedDates.size >= MIN_PHONE_USAGE_PATTERN_DAYS && hourlyTotals.sum() > 0L
        val hourlyAverages = if (hasReliableHourlyPattern) {
            LongArray(HOURS_PER_DAY) { hour ->
                hourlyTotals[hour] / analyzedDates.size
            }
        } else {
            LongArray(0)
        }
        val hourlyProfile = hourlyAverages.mapIndexed { hour, averageTimeMs ->
            PhoneUsageHourAverage(hour = hour, averageTimeMs = averageTimeMs)
        }

        val periodSummary = if (
            !hasReliableHourlyPattern
        ) {
            null
        } else {
            val daysAnalyzed = analyzedDates.size
            // Uma janela móvel evita que limites fixos (00h–03h, 03h–06h...)
            // escondam um pico real como 14h–17h. Em empates, maxByOrNull
            // preserva o primeiro início e mantém o resultado estável.
            val mostUsedWindowStart = hourlyAverages.indices
                .maxByOrNull { startHour ->
                    rollingUsage(
                        hourlyAverages = hourlyAverages,
                        startHour = startHour,
                        lengthHours = PEAK_USAGE_WINDOW_HOURS
                    )
                }
                ?: 0
            val mostUsedWindowAverageMs = rollingUsage(
                hourlyAverages = hourlyAverages,
                startHour = mostUsedWindowStart,
                lengthHours = PEAK_USAGE_WINDOW_HOURS
            )
            val quietestWindow = findQuietestWindow(hourlyAverages)

            PhoneUsagePeriodSummary(
                mostUsed = PhoneUsagePeriodAverage(
                    startHour = mostUsedWindowStart,
                    endHour = (mostUsedWindowStart + PEAK_USAGE_WINDOW_HOURS) % HOURS_PER_DAY,
                    averageTimeMs = mostUsedWindowAverageMs
                ),
                leastUsed = PhoneUsagePeriodAverage(
                    startHour = quietestWindow.startHour,
                    endHour = (quietestWindow.startHour + quietestWindow.lengthHours) % HOURS_PER_DAY,
                    averageTimeMs = quietestWindow.averageUsageMs
                ),
                daysAnalyzed = daysAnalyzed
            )
        }

        val recentSleepObservations = SleepPatternEstimator.extractObservations(
            intervals = detailedIntervals,
            completeDates = analyzedDates,
            zoneId = zoneId
        )
        val sleepObservations = (
            historicalSleepObservations + recentSleepObservations
        ).filter(SleepPatternEstimator::isPlausibleObservation)
            .associateBy(NightlySleepObservation::nightDate)
            .values
            .sortedBy(NightlySleepObservation::nightDate)
        val estimatedSleepWindow = SleepPatternEstimator.estimate(sleepObservations)

        return PhoneUsageInsights(
            dailyHistory = dailyHistory,
            periodSummary = periodSummary,
            completeDaysAverageMs = completeDaysAverageMs,
            completeDaysAnalyzed = completeHistory.size,
            hourlyProfile = hourlyProfile,
            estimatedSleepWindow = estimatedSleepWindow,
            sleepNightsAvailable = sleepObservations.size
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

    private fun rollingUsage(
        hourlyAverages: LongArray,
        startHour: Int,
        lengthHours: Int
    ): Long = (0 until lengthHours).sumOf { offset ->
        hourlyAverages[(startHour + offset) % HOURS_PER_DAY]
    }

    /**
     * Encontra o maior trecho circular de horas com uso abaixo da média
     * horária pessoal. Isso evita chamar uma única hora zerada de "menor
     * período" e reconhece corretamente faixas que atravessam a meia-noite,
     * como 21h–06h.
     */
    private fun findQuietestWindow(hourlyAverages: LongArray): QuietUsageWindow {
        require(hourlyAverages.size == HOURS_PER_DAY)

        val typicalHourlyUsageMs = hourlyAverages.sum() / HOURS_PER_DAY
        val isQuietHour = BooleanArray(HOURS_PER_DAY) { hour ->
            hourlyAverages[hour] < typicalHourlyUsageMs
        }

        // Em um padrão perfeitamente uniforme não existe uma faixa abaixo da
        // média. Nesse caso, a hora de menor uso é a resposta mais honesta.
        if (isQuietHour.none { it }) {
            val leastUsedHour = hourlyAverages.indices
                .minByOrNull { hourlyAverages[it] }
                ?: 0
            return QuietUsageWindow(
                startHour = leastUsedHour,
                lengthHours = 1,
                averageUsageMs = hourlyAverages[leastUsedHour]
            )
        }

        var best: QuietUsageWindow? = null
        for (startHour in 0 until HOURS_PER_DAY) {
            val previousHour = (startHour - 1 + HOURS_PER_DAY) % HOURS_PER_DAY
            if (!isQuietHour[startHour] || isQuietHour[previousHour]) continue

            var lengthHours = 0
            var totalUsageMs = 0L
            while (
                lengthHours < HOURS_PER_DAY &&
                isQuietHour[(startHour + lengthHours) % HOURS_PER_DAY]
            ) {
                totalUsageMs += hourlyAverages[(startHour + lengthHours) % HOURS_PER_DAY]
                lengthHours++
            }

            val candidate = QuietUsageWindow(
                startHour = startHour,
                lengthHours = lengthHours,
                averageUsageMs = totalUsageMs
            )
            val currentBest = best
            if (
                currentBest == null ||
                candidate.lengthHours > currentBest.lengthHours ||
                (
                    candidate.lengthHours == currentBest.lengthHours &&
                    candidate.averageUsageMs < currentBest.averageUsageMs
                )
            ) {
                best = candidate
            }
        }

        return checkNotNull(best)
    }

    private data class QuietUsageWindow(
        val startHour: Int,
        val lengthHours: Int,
        /** Uso médio total dentro da faixa, por dia analisado. */
        val averageUsageMs: Long
    )
}
