package com.focusguard.analytics

import android.content.Context
import com.focusguard.utils.SecurePrefsManager
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt

internal const val MIN_SLEEP_PATTERN_NIGHTS = 3

enum class SleepEstimateConfidence {
    LOW,
    MEDIUM,
    HIGH
}

data class EstimatedSleepWindow(
    val bedtimeMinuteOfDay: Int,
    val wakeMinuteOfDay: Int,
    val averageDurationMs: Long,
    val confidence: SleepEstimateConfidence,
    val confidenceScore: Int,
    val nightsAnalyzed: Int
)

internal data class NightlySleepObservation(
    val nightDate: LocalDate,
    val bedtimeMinuteOfDay: Int,
    val wakeMinuteOfDay: Int,
    val durationMs: Long,
    val interruptionDurationMs: Long,
    val interruptionCount: Int
)

/**
 * Estima a janela habitual de sono somente a partir de intervalos de tela.
 *
 * A estratégia é inspirada em iSenseSleep e Tappigraphy: procura a maior
 * lacuna de interação que começa à noite e termina pela manhã. Interações
 * muito curtas no meio da madrugada são tratadas como interrupções, em vez de
 * dividir automaticamente uma noite em duas. A mediana entre noites reduz o
 * efeito de fins de semana e dias atípicos.
 *
 * O resultado é um indício comportamental, não uma medição clínica de sono.
 */
internal object SleepPatternEstimator {
    private const val MINUTES_PER_DAY = 24 * 60
    private const val NIGHT_ANALYSIS_START_MINUTE = 18 * 60
    private const val NIGHT_ANALYSIS_END_MINUTE = 12 * 60
    private const val EARLIEST_BEDTIME_MINUTE = 20 * 60
    private const val LATEST_BEDTIME_MINUTE_EXTENDED = MINUTES_PER_DAY + 4 * 60
    private const val EARLIEST_WAKE_MINUTE = 4 * 60
    private const val LATEST_WAKE_MINUTE = 12 * 60
    private const val MIN_SLEEP_DURATION_MS = 4 * 60 * 60_000L
    private const val MAX_SLEEP_DURATION_MS = 14 * 60 * 60_000L
    private const val MAX_SINGLE_INTERRUPTION_MS = 5 * 60_000L
    private const val MAX_TOTAL_INTERRUPTION_MS = 15 * 60_000L
    private const val INTERRUPTION_SELECTION_PENALTY_MS = 3 * 60 * 60_000L
    private const val TARGET_HISTORY_NIGHTS = 7f
    private const val MAX_ESTIMATE_NIGHTS = 14

    fun extractObservations(
        intervals: List<ScreenInteractiveInterval>,
        completeDates: List<LocalDate>,
        zoneId: ZoneId
    ): List<NightlySleepObservation> {
        if (intervals.size < 2 || completeDates.size < 2) return emptyList()

        val completeDateSet = completeDates.toSet()
        val normalizedIntervals = mergeOverlappingIntervals(intervals)

        return completeDates.asSequence()
            .distinct()
            .sorted()
            .filter { nightDate -> nightDate.plusDays(1) in completeDateSet }
            .mapNotNull { nightDate ->
                findNightObservation(
                    nightDate = nightDate,
                    intervals = normalizedIntervals,
                    zoneId = zoneId
                )
            }
            .toList()
    }

    fun estimate(
        observations: List<NightlySleepObservation>
    ): EstimatedSleepWindow? {
        val uniqueObservations = observations
            .filter(::isPlausibleObservation)
            .associateBy(NightlySleepObservation::nightDate)
            .values
            .sortedBy(NightlySleepObservation::nightDate)
            .takeLast(MAX_ESTIMATE_NIGHTS)

        if (uniqueObservations.size < MIN_SLEEP_PATTERN_NIGHTS) return null

        val bedtimeMinutes = uniqueObservations.map { observation ->
            observation.bedtimeMinuteOfDay.toExtendedBedtimeMinute()
        }
        val wakeMinutes = uniqueObservations.map(NightlySleepObservation::wakeMinuteOfDay)
        val durations = uniqueObservations.map(NightlySleepObservation::durationMs)
        val interruptionDurations = uniqueObservations.map(
            NightlySleepObservation::interruptionDurationMs
        )

        val medianBedtime = medianInt(bedtimeMinutes)
        val medianWake = medianInt(wakeMinutes)
        val medianDurationMs = medianLong(durations)
        val bedtimeDeviation = medianAbsoluteDeviation(bedtimeMinutes, medianBedtime)
        val wakeDeviation = medianAbsoluteDeviation(wakeMinutes, medianWake)
        val medianInterruptionMs = medianLong(interruptionDurations)

        val sampleScore = (uniqueObservations.size / TARGET_HISTORY_NIGHTS)
            .coerceIn(0f, 1f)
        val averageDeviationMinutes = (bedtimeDeviation + wakeDeviation) / 2f
        val consistencyScore = (1f - averageDeviationMinutes / 180f)
            .coerceIn(0f, 1f)
        val interruptionScore = (
            1f - medianInterruptionMs.toFloat() / MAX_TOTAL_INTERRUPTION_MS.toFloat()
        ).coerceIn(0f, 1f)
        val durationScore = durationPlausibilityScore(medianDurationMs)
        val confidenceScore = (
            sampleScore * 0.45f +
                consistencyScore * 0.35f +
                interruptionScore * 0.10f +
                durationScore * 0.10f
        ).times(100f).roundToInt().coerceIn(0, 100)

        val confidence = when {
            uniqueObservations.size >= 5 && confidenceScore >= 75 ->
                SleepEstimateConfidence.HIGH

            confidenceScore >= 50 -> SleepEstimateConfidence.MEDIUM
            else -> SleepEstimateConfidence.LOW
        }

        return EstimatedSleepWindow(
            bedtimeMinuteOfDay = medianBedtime.floorMod(MINUTES_PER_DAY),
            wakeMinuteOfDay = medianWake.floorMod(MINUTES_PER_DAY),
            averageDurationMs = medianDurationMs,
            confidence = confidence,
            confidenceScore = confidenceScore,
            nightsAnalyzed = uniqueObservations.size
        )
    }

    private fun findNightObservation(
        nightDate: LocalDate,
        intervals: List<ScreenInteractiveInterval>,
        zoneId: ZoneId
    ): NightlySleepObservation? {
        val rangeStartMs = nightDate
            .atTime(NIGHT_ANALYSIS_START_MINUTE / 60, 0)
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()
        val rangeEndMs = nightDate.plusDays(1)
            .atTime(NIGHT_ANALYSIS_END_MINUTE / 60, 0)
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()
        val nightIntervals = intervals.filter { interval ->
            interval.endTimeMs > rangeStartMs && interval.startTimeMs < rangeEndMs
        }
        if (nightIntervals.size < 2) return null

        var best: SleepCandidate? = null
        for (leftIndex in 0 until nightIntervals.lastIndex) {
            val candidateStartMs = nightIntervals[leftIndex].endTimeMs
            val bedtimeMinute = minuteOfDay(candidateStartMs, zoneId)
            val extendedBedtime = bedtimeMinute.toExtendedBedtimeMinute()
            if (
                extendedBedtime !in
                EARLIEST_BEDTIME_MINUTE..LATEST_BEDTIME_MINUTE_EXTENDED
            ) {
                continue
            }

            var interruptionDurationMs = 0L
            var interruptionCount = 0
            for (rightIndex in (leftIndex + 1)..nightIntervals.lastIndex) {
                if (rightIndex > leftIndex + 1) {
                    val interruption = nightIntervals[rightIndex - 1]
                    val durationMs = interruption.endTimeMs - interruption.startTimeMs
                    if (durationMs > MAX_SINGLE_INTERRUPTION_MS) break
                    interruptionDurationMs += durationMs
                    interruptionCount++
                    if (interruptionDurationMs > MAX_TOTAL_INTERRUPTION_MS) break
                }

                val candidateEndMs = nightIntervals[rightIndex].startTimeMs
                val durationMs = candidateEndMs - candidateStartMs
                if (durationMs > MAX_SLEEP_DURATION_MS) break
                if (durationMs < MIN_SLEEP_DURATION_MS) continue

                val wakeMinute = minuteOfDay(candidateEndMs, zoneId)
                if (wakeMinute !in EARLIEST_WAKE_MINUTE..LATEST_WAKE_MINUTE) continue

                val candidate = SleepCandidate(
                    startTimeMs = candidateStartMs,
                    endTimeMs = candidateEndMs,
                    bedtimeMinuteOfDay = bedtimeMinute,
                    wakeMinuteOfDay = wakeMinute,
                    interruptionDurationMs = interruptionDurationMs,
                    interruptionCount = interruptionCount
                )
                val currentBest = best
                if (
                    currentBest == null ||
                    candidate.selectionScoreMs > currentBest.selectionScoreMs ||
                    (
                        candidate.selectionScoreMs == currentBest.selectionScoreMs &&
                            candidate.interruptionDurationMs < currentBest.interruptionDurationMs
                    )
                ) {
                    best = candidate
                }
            }
        }

        return best?.let { candidate ->
            NightlySleepObservation(
                nightDate = nightDate,
                bedtimeMinuteOfDay = candidate.bedtimeMinuteOfDay,
                wakeMinuteOfDay = candidate.wakeMinuteOfDay,
                durationMs = candidate.endTimeMs - candidate.startTimeMs,
                interruptionDurationMs = candidate.interruptionDurationMs,
                interruptionCount = candidate.interruptionCount
            )
        }
    }

    private fun mergeOverlappingIntervals(
        intervals: List<ScreenInteractiveInterval>
    ): List<ScreenInteractiveInterval> {
        val sorted = intervals
            .asSequence()
            .filter { it.endTimeMs > it.startTimeMs }
            .sortedBy(ScreenInteractiveInterval::startTimeMs)
            .toList()
        if (sorted.isEmpty()) return emptyList()

        val merged = mutableListOf(sorted.first())
        sorted.drop(1).forEach { interval ->
            val previous = merged.last()
            if (interval.startTimeMs <= previous.endTimeMs) {
                merged[merged.lastIndex] = ScreenInteractiveInterval(
                    startTimeMs = previous.startTimeMs,
                    endTimeMs = maxOf(previous.endTimeMs, interval.endTimeMs)
                )
            } else {
                merged += interval
            }
        }
        return merged
    }

    private fun minuteOfDay(timestampMs: Long, zoneId: ZoneId): Int {
        val localTime = Instant.ofEpochMilli(timestampMs).atZone(zoneId).toLocalTime()
        return localTime.hour * 60 + localTime.minute
    }

    private fun Int.toExtendedBedtimeMinute(): Int =
        if (this < NIGHT_ANALYSIS_END_MINUTE) this + MINUTES_PER_DAY else this

    private fun durationPlausibilityScore(durationMs: Long): Float {
        val durationHours = durationMs / 3_600_000f
        return when {
            durationHours in 6f..10f -> 1f
            durationHours < 6f -> ((durationHours - 4f) / 2f).coerceIn(0f, 1f)
            else -> ((14f - durationHours) / 4f).coerceIn(0f, 1f)
        }
    }

    fun isPlausibleObservation(observation: NightlySleepObservation): Boolean =
        observation.bedtimeMinuteOfDay in 0 until MINUTES_PER_DAY &&
            observation.wakeMinuteOfDay in 0 until MINUTES_PER_DAY &&
            observation.durationMs in MIN_SLEEP_DURATION_MS..MAX_SLEEP_DURATION_MS &&
            observation.interruptionDurationMs in 0L..MAX_TOTAL_INTERRUPTION_MS &&
            observation.interruptionCount >= 0

    private fun medianInt(values: List<Int>): Int {
        require(values.isNotEmpty())
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[middle]
        } else {
            ((sorted[middle - 1].toLong() + sorted[middle].toLong()) / 2L).toInt()
        }
    }

    private fun medianLong(values: List<Long>): Long {
        require(values.isNotEmpty())
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[middle]
        } else {
            sorted[middle - 1] / 2L + sorted[middle] / 2L +
                (sorted[middle - 1] % 2L + sorted[middle] % 2L) / 2L
        }
    }

    private fun medianAbsoluteDeviation(values: List<Int>, median: Int): Int =
        medianInt(values.map { value -> abs(value - median) })

    private fun Int.floorMod(modulus: Int): Int = ((this % modulus) + modulus) % modulus

    private data class SleepCandidate(
        val startTimeMs: Long,
        val endTimeMs: Long,
        val bedtimeMinuteOfDay: Int,
        val wakeMinuteOfDay: Int,
        val interruptionDurationMs: Long,
        val interruptionCount: Int
    ) {
        val effectiveInactivityMs: Long
            get() = endTimeMs - startTimeMs - interruptionDurationMs

        val selectionScoreMs: Long
            get() = effectiveInactivityMs -
                interruptionCount * SleepPatternEstimator.INTERRUPTION_SELECTION_PENALTY_MS
    }
}

/** Mantém resumos derivados e criptografados para aprender além da retenção do Android. */
internal class SleepPatternHistoryStore(context: Context) {
    private val preferences = SecurePrefsManager(context).prefs

    fun mergeAndLoad(
        recentObservations: List<NightlySleepObservation>,
        today: LocalDate
    ): List<NightlySleepObservation> {
        val cutoffDate = today.minusDays(MAX_HISTORY_DAYS)
        val merged = preferences
            .getStringSet(KEY_OBSERVATIONS, emptySet())
            .orEmpty()
            .mapNotNull(::decode)
            .associateByTo(mutableMapOf(), NightlySleepObservation::nightDate)

        recentObservations.forEach { observation ->
            merged[observation.nightDate] = observation
        }

        val retained = merged.values
            .filter { observation -> observation.nightDate >= cutoffDate }
            .sortedBy(NightlySleepObservation::nightDate)
        preferences.edit()
            .putStringSet(KEY_OBSERVATIONS, retained.mapTo(mutableSetOf(), ::encode))
            .apply()
        return retained
    }

    private fun encode(observation: NightlySleepObservation): String = listOf(
        observation.nightDate.toString(),
        observation.bedtimeMinuteOfDay,
        observation.wakeMinuteOfDay,
        observation.durationMs,
        observation.interruptionDurationMs,
        observation.interruptionCount
    ).joinToString(SEPARATOR)

    private fun decode(value: String): NightlySleepObservation? = runCatching {
        val parts = value.split(SEPARATOR)
        if (parts.size != 6) return@runCatching null
        val bedtimeMinute = parts[1].toInt()
        val wakeMinute = parts[2].toInt()
        val durationMs = parts[3].toLong()
        val interruptionDurationMs = parts[4].toLong()
        val interruptionCount = parts[5].toInt()
        require(bedtimeMinute in 0 until 24 * 60)
        require(wakeMinute in 0 until 24 * 60)
        require(durationMs > 0L)
        require(interruptionDurationMs >= 0L)
        require(interruptionCount >= 0)
        NightlySleepObservation(
            nightDate = LocalDate.parse(parts[0]),
            bedtimeMinuteOfDay = bedtimeMinute,
            wakeMinuteOfDay = wakeMinute,
            durationMs = durationMs,
            interruptionDurationMs = interruptionDurationMs,
            interruptionCount = interruptionCount
        ).takeIf(SleepPatternEstimator::isPlausibleObservation)
    }.getOrNull()

    private companion object {
        const val KEY_OBSERVATIONS = "analytics_nightly_sleep_observations_v1"
        const val SEPARATOR = "|"
        const val MAX_HISTORY_DAYS = 60L
    }
}
