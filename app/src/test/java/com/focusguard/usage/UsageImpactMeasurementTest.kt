package com.focusguard.usage

import com.focusguard.analytics.AggregatedAppBucket
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UsageImpactMeasurementTest {
    private val hour = 60L * 60_000L
    private val activation = 100L * hour

    @Test
    fun `before and after are cut at the exact block instant`() {
        val result = UsageImpactMeasurement.fromIntervals(
            // 1 h antes do bloqueio e 1 h atravessando o bloqueio (30 min de cada lado).
            intervals = listOf(
                (activation - 3 * hour) until (activation - 2 * hour),
                (activation - hour / 2) until (activation + hour / 2)
            ),
            firstEventTimeMs = activation - 10 * hour,
            lastEventTimeMs = activation + 4 * hour,
            scanCompleted = true,
            activationMs = activation,
            windowMs = 4 * hour
        )

        assertThat(result).isEqualTo(
            UsageImpactMeasurement.Result(
                beforeMillis = hour + hour / 2,
                afterMillis = hour / 2,
                windowMillis = 4 * hour
            )
        )
    }

    @Test
    fun `both periods shrink to where events still exist`() {
        val result = UsageImpactMeasurement.fromIntervals(
            intervals = emptyList(),
            firstEventTimeMs = activation - 2 * hour,
            lastEventTimeMs = activation + 4 * hour,
            scanCompleted = true,
            activationMs = activation,
            windowMs = 4 * hour
        )

        assertThat(result?.windowMillis).isEqualTo(2 * hour)
    }

    @Test
    fun `no events before the block means no exact comparison`() {
        val result = UsageImpactMeasurement.fromIntervals(
            intervals = emptyList(),
            firstEventTimeMs = activation + hour,
            lastEventTimeMs = activation + 4 * hour,
            scanCompleted = true,
            activationMs = activation,
            windowMs = 4 * hour
        )

        assertThat(result).isNull()
    }

    @Test
    fun `bucket fallback splits a bucket that crosses the block`() {
        val result = UsageImpactMeasurement.fromBuckets(
            buckets = listOf(
                AggregatedAppBucket("app", activation - 2 * hour, activation + 2 * hour, 4 * hour),
                AggregatedAppBucket("other", activation - 2 * hour, activation + 2 * hour, hour)
            ),
            packageName = "app",
            activationMs = activation,
            windowMs = 2 * hour,
            nowMs = activation + 30 * hour
        )

        assertThat(result.beforeMillis).isEqualTo(2 * hour)
        assertThat(result.afterMillis).isEqualTo(2 * hour)
    }
}
