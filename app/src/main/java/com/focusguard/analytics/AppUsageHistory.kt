package com.focusguard.analytics

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.PowerManager
import com.focusguard.utils.FocusGuardLogger
import com.focusguard.utils.ForegroundIntervalCollector
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToLong

/** Trechos de primeiro plano de cada app lidos dos eventos de ciclo de vida. */
internal data class ForegroundEventScan(
    val intervalsByPackage: Map<String, List<LongRange>>,
    /** Primeiro evento ainda guardado pelo Android; antes dele não há como saber. */
    val firstEventTimeMs: Long?,
    val lastEventTimeMs: Long,
    /** False quando a leitura parou no meio (alguns fabricantes interrompem). */
    val completed: Boolean
)

/**
 * Lê o primeiro plano real com o mesmo coletor dos limites diários, para o painel,
 * a tela de impacto e os limites contarem o mesmo tempo para o mesmo app.
 */
internal object ForegroundEventScanner {
    fun scan(
        manager: UsageStatsManager,
        startMs: Long,
        endMs: Long,
        isDeviceInteractive: Boolean
    ): ForegroundEventScan? {
        if (endMs <= startMs) return null
        val events = try {
            manager.queryEvents(startMs, endMs)
        } catch (error: Throwable) {
            FocusGuardLogger.logError("ForegroundScan", "Falha em queryEvents", error)
            null
        } ?: return null

        val collector = ForegroundIntervalCollector()
        val event = UsageEvents.Event()
        var firstEventTimeMs: Long? = null
        var lastEventTimeMs = startMs
        var completed = true
        try {
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                firstEventTimeMs = firstEventTimeMs?.coerceAtMost(event.timeStamp)
                    ?: event.timeStamp
                lastEventTimeMs = maxOf(lastEventTimeMs, event.timeStamp)
                collector.onUsageEvent(
                    eventType = event.eventType,
                    packageName = event.packageName,
                    activityClassName = event.className,
                    atMillis = event.timeStamp
                )
            }
        } catch (error: Throwable) {
            completed = false
            FocusGuardLogger.logError("ForegroundScan", "Leitura de eventos interrompida", error)
        }

        val untilMs = if (completed) endMs else lastEventTimeMs
        return ForegroundEventScan(
            intervalsByPackage = collector.packagesSeen().associateWith { packageName ->
                collector.intervalsFor(
                    packageName = packageName,
                    untilMillis = untilMs,
                    deviceInteractive = completed && isDeviceInteractive
                )
            },
            firstEventTimeMs = firstEventTimeMs,
            lastEventTimeMs = lastEventTimeMs,
            completed = completed
        )
    }
}

/** Soma, por app, só a parte de cada trecho que cai em [rangeStartMs, rangeEndMs). */
internal fun foregroundTotalsInRange(
    intervalsByPackage: Map<String, List<LongRange>>,
    rangeStartMs: Long,
    rangeEndMs: Long
): Map<String, Long> {
    if (rangeEndMs <= rangeStartMs) return emptyMap()
    val totals = linkedMapOf<String, Long>()
    intervalsByPackage.forEach { (packageName, intervals) ->
        val totalMs = intervals.sumOf { interval ->
            // LongRange de `until`: o fim exclusivo é last + 1.
            (minOf(interval.last + 1, rangeEndMs) - maxOf(interval.first, rangeStartMs))
                .coerceAtLeast(0L)
        }
        if (totalMs > 0L) totals[packageName] = totalMs
    }
    return totals
}

/** Um balde do histórico agregado do Android para um app (UsageStats). */
internal data class AggregatedAppBucket(
    val packageName: String,
    val beginTimeMs: Long,
    val endTimeMs: Long,
    val foregroundMs: Long
)

/**
 * Estimativa por app para um intervalo sem eventos detalhados: cada balde fechado
 * entra só com a fração que cai no intervalo. Um balde que chega até perto de agora
 * ainda está aberto e crescendo e fica de fora, para a estimativa de um período
 * passado não mudar.
 */
internal fun estimateAppUsageFromClosedBuckets(
    buckets: List<AggregatedAppBucket>,
    rangeStartMs: Long,
    rangeEndMs: Long,
    nowMs: Long
): Map<String, Long>? {
    if (rangeEndMs <= rangeStartMs) return null
    var overlapsRange = false
    val estimates = linkedMapOf<String, Double>()
    buckets.forEach { bucket ->
        if (bucket.endTimeMs >= nowMs - OPEN_BUCKET_TOLERANCE_MS) return@forEach
        val bucketDurationMs = bucket.endTimeMs - bucket.beginTimeMs
        if (bucketDurationMs <= 0L) return@forEach
        val overlapMs = minOf(bucket.endTimeMs, rangeEndMs) -
            maxOf(bucket.beginTimeMs, rangeStartMs)
        if (overlapMs <= 0L) return@forEach
        overlapsRange = true
        if (bucket.foregroundMs <= 0L) return@forEach
        estimates[bucket.packageName] = (estimates[bucket.packageName] ?: 0.0) +
            bucket.foregroundMs.coerceAtMost(bucketDurationMs) *
            (overlapMs.toDouble() / bucketDurationMs.toDouble())
    }
    if (!overlapsRange) return null
    return estimates.mapValues { (_, value) ->
        value.roundToLong().coerceIn(0L, rangeEndMs - rangeStartMs)
    }.filterValues { it > 0L }
}

/** O balde aberto do Android termina no último evento gravado, quase sempre há instantes. */
internal const val OPEN_BUCKET_TOLERANCE_MS = 10L * 60_000L

internal fun queryAggregatedAppBuckets(
    manager: UsageStatsManager,
    startMs: Long,
    endMs: Long
): List<AggregatedAppBucket> {
    if (endMs <= startMs) return emptyList()
    return try {
        manager.queryUsageStats(UsageStatsManager.INTERVAL_BEST, startMs, endMs)
            .orEmpty()
            .map { stats ->
                AggregatedAppBucket(
                    packageName = stats.packageName,
                    beginTimeMs = stats.firstTimeStamp,
                    endTimeMs = stats.lastTimeStamp,
                    foregroundMs = stats.totalTimeInForeground
                )
            }
    } catch (error: Throwable) {
        FocusGuardLogger.logError("AppUsageHistory", "Falha em queryUsageStats", error)
        emptyList()
    }
}

/** Uso por app em cada dia pedido e hoje até agora. */
internal data class DailyAppUsage(
    /** Dias encerrados; um dia sem nenhuma fonte de dados fica fora do mapa. */
    val byDate: Map<LocalDate, Map<String, Long>>,
    val today: Map<String, Long>
)

/**
 * Decide o uso por app de cada dia, na mesma ordem de confiança do uso do telefone:
 * 1. dia encerrado já gravado: definitivo, nunca recalculado;
 * 2. dia coberto pelos eventos: soma exata entre a meia-noite e a meia-noite seguinte;
 * 3. sem eventos: estimativa pelos baldes fechados do histórico agregado.
 * Dias medidos (2) e estimativas de dias que já não podem mudar (3, até anteontem)
 * voltam em [AppUsageResolution.toRecord] para serem gravados.
 */
internal data class AppUsageResolution(
    val usage: DailyAppUsage,
    val toRecord: Map<LocalDate, Map<String, Long>>
)

internal fun resolveDailyAppUsage(
    dates: Collection<LocalDate>,
    today: LocalDate,
    nowMs: Long,
    zoneId: ZoneId,
    stored: Map<LocalDate, Map<String, Long>>,
    scan: ForegroundEventScan?,
    estimateBuckets: () -> List<AggregatedAppBucket>,
    isEligible: (String) -> Boolean
): AppUsageResolution {
    val pastDates = dates.filter { it < today }.distinct().sorted()
    val missing = pastDates.filter { it !in stored }
    val covered = if (scan == null) {
        emptySet()
    } else {
        completeDatesCoveredByDetailedEvents(
            firstObservedEventTimeMs = scan.firstEventTimeMs,
            lastObservedEventTimeMs = scan.lastEventTimeMs,
            eventLoopCompleted = scan.completed,
            periodStartDate = missing.firstOrNull() ?: today,
            today = today,
            zoneId = zoneId
        ).toSet()
    }
    val buckets by lazy(estimateBuckets)

    fun dayStart(date: LocalDate) = date.atStartOfDay(zoneId).toInstant().toEpochMilli()
    fun eligibleOnly(totals: Map<String, Long>) = totals.filterKeys(isEligible)

    val byDate = linkedMapOf<LocalDate, Map<String, Long>>()
    val toRecord = linkedMapOf<LocalDate, Map<String, Long>>()
    pastDates.forEach { date ->
        val storedDay = stored[date]
        if (storedDay != null) {
            byDate[date] = eligibleOnly(storedDay)
            return@forEach
        }
        val startMs = dayStart(date)
        val endMs = dayStart(date.plusDays(1))
        if (scan != null && date in covered) {
            val exact = eligibleOnly(
                foregroundTotalsInRange(scan.intervalsByPackage, startMs, endMs)
            )
            byDate[date] = exact
            toRecord[date] = exact
            return@forEach
        }
        val estimate = estimateAppUsageFromClosedBuckets(buckets, startMs, endMs, nowMs)
            ?.let(::eligibleOnly)
            ?: return@forEach
        byDate[date] = estimate
        if (date <= today.minusDays(2)) toRecord[date] = estimate
    }

    val todayUsage = if (scan != null && scan.completed) {
        eligibleOnly(foregroundTotalsInRange(scan.intervalsByPackage, dayStart(today), nowMs))
    } else {
        emptyMap()
    }
    return AppUsageResolution(
        usage = DailyAppUsage(byDate = byDate, today = todayUsage),
        toRecord = toRecord
    )
}

/** Regras do registro diário de uso por app (dias encerrados, primeiro valor vence). */
internal object DailyAppUsageHistory {
    const val RETENTION_DAYS = 62L
    private const val DATE_SEPARATOR = "|"
    private const val ENTRY_SEPARATOR = ";"
    private const val VALUE_SEPARATOR = "="
    private const val MAX_DAY_MS = 25L * 60L * 60L * 1_000L

    fun merge(
        stored: Map<LocalDate, Map<String, Long>>,
        newDays: Map<LocalDate, Map<String, Long>>,
        today: LocalDate
    ): Map<LocalDate, Map<String, Long>> {
        val keep = today.minusDays(RETENTION_DAYS)..today.minusDays(1)
        val merged = sortedMapOf<LocalDate, Map<String, Long>>()
        stored.forEach { (date, usage) -> if (date in keep) merged[date] = usage }
        newDays.forEach { (date, usage) ->
            if (date in keep && date !in merged) {
                merged[date] = usage.mapValues { (_, ms) -> ms.coerceIn(0L, MAX_DAY_MS) }
                    .filterValues { it > 0L }
            }
        }
        return merged
    }

    fun encode(date: LocalDate, usage: Map<String, Long>): String =
        date.toString() + DATE_SEPARATOR + usage.entries.joinToString(ENTRY_SEPARATOR) {
            it.key + VALUE_SEPARATOR + it.value
        }

    fun decode(value: String): Pair<LocalDate, Map<String, Long>>? = runCatching {
        val separator = value.indexOf(DATE_SEPARATOR)
        if (separator <= 0) return@runCatching null
        val date = LocalDate.parse(value.substring(0, separator))
        val entries = value.substring(separator + 1)
        val usage = if (entries.isEmpty()) {
            emptyMap()
        } else {
            entries.split(ENTRY_SEPARATOR).associate { entry ->
                val parts = entry.split(VALUE_SEPARATOR)
                require(parts.size == 2 && parts[0].isNotBlank())
                val ms = parts[1].toLong()
                require(ms in 0L..MAX_DAY_MS)
                parts[0] to ms
            }
        }
        date to usage
    }.getOrNull()
}

/**
 * Guarda os dias em um arquivo próprio: o registro por app é grande e, no arquivo
 * criptografado das credenciais, deixaria mais lenta a abertura da tela de senha.
 */
internal class DailyAppUsageHistoryStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): Map<LocalDate, Map<String, Long>> = preferences
        .getStringSet(KEY_DAILY_APP_USAGE, emptySet())
        .orEmpty()
        .mapNotNull(DailyAppUsageHistory::decode)
        .toMap()

    fun record(
        stored: Map<LocalDate, Map<String, Long>>,
        newDays: Map<LocalDate, Map<String, Long>>,
        today: LocalDate
    ) {
        val merged = DailyAppUsageHistory.merge(stored, newDays, today)
        if (merged == stored) return
        preferences.edit()
            .putStringSet(
                KEY_DAILY_APP_USAGE,
                merged.mapTo(mutableSetOf()) { (date, usage) ->
                    DailyAppUsageHistory.encode(date, usage)
                }
            )
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "focusguard_app_usage_history"
        const val KEY_DAILY_APP_USAGE = "analytics_daily_app_usage_v1"
    }
}

/** Parte Android: lê o registro, os eventos e o agregado, e grava os dias novos. */
internal object AppUsageHistoryLoader {
    fun load(
        context: Context,
        manager: UsageStatsManager,
        dates: Collection<LocalDate>,
        nowMs: Long,
        zoneId: ZoneId,
        isEligible: (String) -> Boolean
    ): DailyAppUsage {
        val today = Instant.ofEpochMilli(nowMs).atZone(zoneId).toLocalDate()
        val store = runCatching { DailyAppUsageHistoryStore(context) }
            .onFailure { error ->
                FocusGuardLogger.logError("AppUsageHistory", "Falha ao abrir o registro", error)
            }
            .getOrNull()
        val stored = store?.let { runCatching { it.load() }.getOrNull() }.orEmpty()
        val pastDates = dates.filter { it < today }
        val firstMissing = pastDates.filter { it !in stored }.minOrNull()
        // Um dia antes do primeiro dia pedido: o app que já estava aberto à
        // meia-noite precisa do evento de entrada da véspera.
        val scanStartMs = (firstMissing ?: today).minusDays(1)
            .atStartOfDay(zoneId)
            .toInstant()
            .toEpochMilli()
        val interactive = (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)
            ?.isInteractive == true
        val scan = ForegroundEventScanner.scan(manager, scanStartMs, nowMs, interactive)

        val resolution = resolveDailyAppUsage(
            dates = pastDates,
            today = today,
            nowMs = nowMs,
            zoneId = zoneId,
            stored = stored,
            scan = scan,
            estimateBuckets = {
                queryAggregatedAppBuckets(
                    manager = manager,
                    startMs = (firstMissing ?: today).atStartOfDay(zoneId).toInstant()
                        .toEpochMilli(),
                    endMs = today.atStartOfDay(zoneId).toInstant().toEpochMilli()
                )
            },
            isEligible = isEligible
        )
        if (store != null && resolution.toRecord.isNotEmpty()) {
            runCatching { store.record(stored, resolution.toRecord, today) }
                .onFailure { error ->
                    FocusGuardLogger.logError("AppUsageHistory", "Falha ao gravar dias", error)
                }
        }
        return resolution.usage
    }
}
