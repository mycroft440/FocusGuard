package com.focusguard.usage

import android.app.usage.UsageStatsManager
import com.focusguard.analytics.AggregatedAppBucket
import com.focusguard.analytics.ForegroundEventScanner
import com.focusguard.analytics.estimateAppUsageFromClosedBuckets
import com.focusguard.analytics.foregroundTotalsInRange
import com.focusguard.analytics.queryAggregatedAppBuckets

/**
 * Uso do app antes e depois do bloqueio, em dois períodos de mesmo tamanho.
 *
 * Antes, os dois números vinham do histórico agregado do Android. Os baldes dele
 * não respeitam o instante do bloqueio e o balde aberto entra nas duas consultas,
 * então "antes" e "depois" contavam o mesmo uso e um bloqueio recente aparecia sem
 * redução nenhuma. Agora os dois saem dos eventos de primeiro plano, cortados no
 * instante exato do bloqueio.
 */
internal object UsageImpactMeasurement {
    /** Menor período que ainda permite uma comparação honesta. */
    const val MIN_WINDOW_MILLIS = 60_000L
    private const val LOOKBACK_MILLIS = 24L * 60L * 60L * 1_000L

    data class Result(
        val beforeMillis: Long,
        val afterMillis: Long,
        val windowMillis: Long
    )

    /**
     * Exato pelos eventos. Se o Android já apagou os eventos do começo do período
     * "antes", os dois períodos encolhem juntos até onde há eventos, para continuar
     * comparando tempos iguais. Null quando sobra menos de [MIN_WINDOW_MILLIS].
     */
    fun fromIntervals(
        intervals: List<LongRange>,
        firstEventTimeMs: Long?,
        lastEventTimeMs: Long,
        scanCompleted: Boolean,
        activationMs: Long,
        windowMs: Long
    ): Result? {
        val coveredFromMs = firstEventTimeMs ?: return null
        var coveredWindowMs = minOf(windowMs, activationMs - coveredFromMs)
        if (!scanCompleted) {
            coveredWindowMs = minOf(coveredWindowMs, lastEventTimeMs - activationMs)
        }
        if (coveredWindowMs < MIN_WINDOW_MILLIS) return null
        val byPackage = mapOf(PACKAGE_KEY to intervals)
        return Result(
            beforeMillis = foregroundTotalsInRange(
                byPackage,
                activationMs - coveredWindowMs,
                activationMs
            )[PACKAGE_KEY] ?: 0L,
            afterMillis = foregroundTotalsInRange(
                byPackage,
                activationMs,
                activationMs + coveredWindowMs
            )[PACKAGE_KEY] ?: 0L,
            windowMillis = coveredWindowMs
        )
    }

    /** Sem eventos: estimativa pelos baldes fechados, cada um repartido pela fração. */
    fun fromBuckets(
        buckets: List<AggregatedAppBucket>,
        packageName: String,
        activationMs: Long,
        windowMs: Long,
        nowMs: Long
    ): Result {
        val before = estimateAppUsageFromClosedBuckets(
            buckets.filter { it.packageName == packageName },
            activationMs - windowMs,
            activationMs,
            nowMs
        )?.get(packageName) ?: 0L
        val after = estimateAppUsageFromClosedBuckets(
            buckets.filter { it.packageName == packageName },
            activationMs,
            activationMs + windowMs,
            nowMs
        )?.get(packageName) ?: 0L
        return Result(beforeMillis = before, afterMillis = after, windowMillis = windowMs)
    }

    fun measure(
        manager: UsageStatsManager,
        packageName: String,
        activationMs: Long,
        windowMs: Long,
        nowMs: Long,
        isDeviceInteractive: Boolean,
        ownPackage: String
    ): Result {
        val beforeStartMs = activationMs - windowMs
        val afterEndMs = (activationMs + windowMs).coerceAtMost(nowMs)
        val scan = ForegroundEventScanner.scan(
            manager = manager,
            startMs = (beforeStartMs - LOOKBACK_MILLIS).coerceAtLeast(0L),
            endMs = afterEndMs,
            // Com o fim no passado, um app aberto nesse instante seguia aberto até ele.
            isDeviceInteractive = afterEndMs < nowMs || isDeviceInteractive,
            ownPackage = ownPackage
        )
        scan?.let {
            fromIntervals(
                intervals = it.intervalsByPackage[packageName].orEmpty(),
                firstEventTimeMs = it.firstEventTimeMs,
                lastEventTimeMs = it.lastEventTimeMs,
                scanCompleted = it.completed,
                activationMs = activationMs,
                windowMs = windowMs
            )
        }?.let { return it }

        return fromBuckets(
            buckets = queryAggregatedAppBuckets(manager, beforeStartMs, afterEndMs),
            packageName = packageName,
            activationMs = activationMs,
            windowMs = windowMs,
            nowMs = nowMs
        )
    }

    private const val PACKAGE_KEY = "target"
}
