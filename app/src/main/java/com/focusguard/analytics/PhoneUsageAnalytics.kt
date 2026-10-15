package com.focusguard.analytics

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToLong

internal const val PHONE_USAGE_PERIOD_ANALYSIS_DAYS = 30
internal const val MIN_PHONE_USAGE_PATTERN_DAYS = 3

data class DailyPhoneUsage(
    val dateLabel: String,
    val totalTimeMs: Long,
    val timestamp: Long,
    /** False quando o Android não guardou nada sobre o dia (antes de haver histórico). */
    val hasData: Boolean = true
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
 * Eles dão o total exato de cada dia, os padrões por hora e o sono estimado. O
 * Android os mantém por poucos dias; por isso os dias encerrados ficam gravados.
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

/**
 * Um balde diário do histórico agregado do Android (EventStats de SCREEN_INTERACTIVE).
 *
 * O balde não começa à meia-noite local: ele dura 24 h a partir de quando o
 * Android o abriu, e o mais recente continua crescendo até ser fechado.
 */
internal data class AggregatedScreenBucket(
    val beginTimeMs: Long,
    val endTimeMs: Long,
    val interactiveTimeMs: Long
)

/**
 * Estimativa de tela ativa em [rangeStartMs, rangeEndMs) para um dia que não tem
 * eventos detalhados retidos nem valor já gravado.
 *
 * Somar os baldes que cruzam o dia (como antes) contava o mesmo uso em dois dias e
 * incluía o balde aberto, que segue crescendo: o total de ontem mudava a cada hora
 * de uso de hoje. Aqui o balde aberto (o mais recente) fica de fora e cada balde
 * fechado entra só com a fração dele que cai no dia. Baldes fechados não mudam, então
 * a estimativa de um dia passado também não muda.
 *
 * Null quando nenhum balde fechado cruza o intervalo (sem dados para o dia).
 */
internal fun estimateScreenTimeFromClosedBuckets(
    buckets: List<AggregatedScreenBucket>,
    rangeStartMs: Long,
    rangeEndMs: Long
): Long? {
    if (rangeEndMs <= rangeStartMs) return null
    val openBucketBeginMs = buckets.maxOfOrNull(AggregatedScreenBucket::beginTimeMs)
        ?: return null

    var overlapsRange = false
    var estimatedMs = 0.0
    buckets.forEach { bucket ->
        if (bucket.beginTimeMs == openBucketBeginMs) return@forEach
        val bucketDurationMs = bucket.endTimeMs - bucket.beginTimeMs
        if (bucketDurationMs <= 0L) return@forEach
        val overlapMs = minOf(bucket.endTimeMs, rangeEndMs) -
            maxOf(bucket.beginTimeMs, rangeStartMs)
        if (overlapMs <= 0L) return@forEach

        overlapsRange = true
        estimatedMs += bucket.interactiveTimeMs.coerceIn(0L, bucketDurationMs) *
            (overlapMs.toDouble() / bucketDurationMs.toDouble())
    }
    if (!overlapsRange) return null
    return estimatedMs.roundToLong().coerceIn(0L, rangeEndMs - rangeStartMs)
}

/** Resultado da resolução diária: o total de cada dia e o que deve ser gravado. */
internal data class DailyScreenTimeResolution(
    /** Dias sem nenhuma fonte de dados ficam fora do mapa. */
    val totalsByDate: Map<LocalDate, Long>,
    /** Dias encerrados que passam a ser definitivos agora e ainda não estão gravados. */
    val newlyMeasuredCompleteDays: Map<LocalDate, Long>
)

/**
 * Decide o total de tela ativa de cada dia do histórico.
 *
 * Ordem de confiança:
 * 1. Dia encerrado já gravado: o valor registrado é definitivo e nunca é recalculado.
 * 2. Dia coberto pelos eventos detalhados: soma exata dos intervalos de tela entre a
 *    meia-noite e a meia-noite seguinte (hoje, até agora, com a sessão em andamento).
 *    Um dia encerrado medido assim é devolvido para ser gravado.
 * 3. Sem eventos: estimativa estável pelos baldes fechados do histórico agregado,
 *    gravada a partir de anteontem.
 */
internal fun resolveDailyScreenTimes(
    dates: List<LocalDate>,
    today: LocalDate,
    nowMs: Long,
    zoneId: ZoneId,
    storedCompleteDays: Map<LocalDate, Long>,
    completeDatesCoveredByEvents: Collection<LocalDate>,
    detailedEventsAvailable: Boolean,
    detailedIntervals: List<ScreenInteractiveInterval>,
    aggregatedBuckets: List<AggregatedScreenBucket>
): DailyScreenTimeResolution {
    val coveredDates = completeDatesCoveredByEvents.toSet()
    val totals = linkedMapOf<LocalDate, Long>()
    val newlyMeasured = linkedMapOf<LocalDate, Long>()

    dates.distinct().sorted().forEach { date ->
        if (date > today) return@forEach
        val dayStartMs = date.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val dayEndMs = date.plusDays(1)
            .atStartOfDay(zoneId)
            .toInstant()
            .toEpochMilli()
            .coerceAtMost(nowMs)
        val maximumPossibleMs = (dayEndMs - dayStartMs).coerceAtLeast(0L)
        val exactMs = {
            PhoneUsageInsightsCalculator.totalOverlap(
                intervals = detailedIntervals,
                rangeStartMs = dayStartMs,
                rangeEndMs = dayEndMs
            ).coerceIn(0L, maximumPossibleMs)
        }

        val totalMs = when {
            date < today && date in storedCompleteDays ->
                storedCompleteDays.getValue(date).coerceIn(0L, maximumPossibleMs)

            date < today && date in coveredDates ->
                exactMs().also { newlyMeasured[date] = it }

            // Hoje vem sempre dos eventos quando eles puderam ser lidos: os
            // intervalos já incluem a sessão de tela ainda aberta.
            date == today && detailedEventsAvailable -> exactMs()

            else -> estimateScreenTimeFromClosedBuckets(
                buckets = aggregatedBuckets,
                rangeStartMs = dayStartMs,
                rangeEndMs = dayEndMs
            )?.also { estimateMs ->
                // Até anteontem os baldes do dia já fecharam: a estimativa também é
                // gravada, porque o Android ainda vai apagar esses baldes e o dia
                // passaria a "sem dados" (ou a outra estimativa) mais tarde.
                if (date <= today.minusDays(2)) newlyMeasured[date] = estimateMs
            }
        }
        if (totalMs != null) totals[date] = totalMs
    }

    return DailyScreenTimeResolution(
        totalsByDate = totals,
        newlyMeasuredCompleteDays = newlyMeasured
    )
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

            val totalTimeMs = dailyScreenTimeByDate[date]
            DailyPhoneUsage(
                dateLabel = dayFormatter.format(date).uppercase(locale),
                totalTimeMs = totalTimeMs
                    ?.coerceIn(0L, maximumPossibleTimeMs)
                    ?: 0L,
                timestamp = dayStartMs,
                hasData = totalTimeMs != null
            )
        }

        // Hoje ainda está em andamento. A média usa apenas dias encerrados
        // para não cair artificialmente pela manhã e subir ao longo do dia, e só
        // dias com dados: um dia sem histórico não é um dia sem uso.
        val completeHistory = dailyHistory.dropLast(1).filter(DailyPhoneUsage::hasData)
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
