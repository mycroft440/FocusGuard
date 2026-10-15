package com.focusguard.analytics

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class AppUsageHistoryTest {
    private val zoneId = ZoneId.of("UTC")

    @Test
    fun `usage crossing midnight is split between the two days`() {
        val totals = foregroundTotalsInRange(
            intervalsByPackage = mapOf("a" to listOf(range("2026-01-07T23:30", "2026-01-08T00:30"))),
            rangeStartMs = ts("2026-01-08T00:00"),
            rangeEndMs = ts("2026-01-09T00:00")
        )

        assertThat(totals).containsExactly("a", 30.minutes)
    }

    @Test
    fun `covered past days are exact and returned to be recorded`() {
        val resolution = resolve(
            now = "2026-01-08T12:00",
            dates = listOf(date("2026-01-07")),
            scan = scan(
                first = "2026-01-06T08:00",
                "a" to listOf(range("2026-01-07T10:00", "2026-01-07T11:00")),
                "a" to listOf(range("2026-01-08T09:00", "2026-01-08T09:20"))
            )
        )

        assertThat(resolution.usage.byDate[date("2026-01-07")]).containsExactly("a", 60.minutes)
        assertThat(resolution.usage.today).containsExactly("a", 20.minutes)
        assertThat(resolution.toRecord.keys).containsExactly(date("2026-01-07"))
    }

    @Test
    fun `a recorded day wins over new events`() {
        val resolution = resolve(
            now = "2026-01-08T12:00",
            dates = listOf(date("2026-01-07")),
            stored = mapOf(date("2026-01-07") to mapOf("a" to 5L * 60L * 60_000L)),
            scan = scan(
                first = "2026-01-06T08:00",
                "a" to listOf(range("2026-01-07T10:00", "2026-01-07T11:00"))
            )
        )

        assertThat(resolution.usage.byDate[date("2026-01-07")])
            .containsExactly("a", 5L * 60L * 60_000L)
        assertThat(resolution.toRecord).isEmpty()
    }

    @Test
    fun `days before the retained events use closed buckets and old ones are recorded`() {
        val resolution = resolve(
            now = "2026-01-08T12:00",
            dates = listOf(date("2026-01-05"), date("2026-01-06")),
            // Eventos só a partir do dia 7: os dias 5 e 6 não estão cobertos.
            scan = scan(first = "2026-01-07T08:00"),
            buckets = listOf(
                AggregatedAppBucket("a", ts("2026-01-05T12:00"), ts("2026-01-06T12:00"), 4.hours)
            )
        )

        assertThat(resolution.usage.byDate[date("2026-01-05")]).containsExactly("a", 2.hours)
        assertThat(resolution.usage.byDate[date("2026-01-06")]).containsExactly("a", 2.hours)
        // Anteontem (dia 6) e antes já não mudam; ontem nunca é estimativa gravada.
        assertThat(resolution.toRecord.keys)
            .containsExactly(date("2026-01-05"), date("2026-01-06"))
    }

    @Test
    fun `open bucket never enters an estimate`() {
        val estimate = estimateAppUsageFromClosedBuckets(
            buckets = listOf(
                AggregatedAppBucket("a", ts("2026-01-07T12:00"), ts("2026-01-08T11:58"), 9.hours)
            ),
            rangeStartMs = ts("2026-01-07T00:00"),
            rangeEndMs = ts("2026-01-08T00:00"),
            nowMs = ts("2026-01-08T12:00")
        )

        assertThat(estimate).isNull()
    }

    @Test
    fun `apps that cannot be opened stay out`() {
        val resolution = resolve(
            now = "2026-01-08T12:00",
            dates = emptyList(),
            scan = scan(
                first = "2026-01-06T08:00",
                "a" to listOf(range("2026-01-08T09:00", "2026-01-08T09:20")),
                "launcher" to listOf(range("2026-01-08T09:20", "2026-01-08T10:00"))
            ),
            eligible = setOf("a")
        )

        assertThat(resolution.usage.today.keys).containsExactly("a")
    }

    @Test
    fun `history merge keeps recorded days and drops today`() {
        val today = date("2026-01-08")
        val merged = DailyAppUsageHistory.merge(
            stored = mapOf(date("2026-01-07") to mapOf("a" to 1L)),
            newDays = mapOf(
                date("2026-01-07") to mapOf("a" to 999L),
                date("2026-01-06") to mapOf("b" to 2L),
                today to mapOf("c" to 3L)
            ),
            today = today
        )

        assertThat(merged).containsExactly(
            date("2026-01-06"), mapOf("b" to 2L),
            date("2026-01-07"), mapOf("a" to 1L)
        )
    }

    @Test
    fun `history entries round trip and invalid ones are dropped`() {
        val encoded = DailyAppUsageHistory.encode(
            date("2026-01-07"),
            mapOf("com.whatsapp" to 1234L, "com.instagram.android" to 5L)
        )

        assertThat(DailyAppUsageHistory.decode(encoded)).isEqualTo(
            date("2026-01-07") to mapOf("com.whatsapp" to 1234L, "com.instagram.android" to 5L)
        )
        assertThat(DailyAppUsageHistory.decode("2026-01-07|")).isEqualTo(
            date("2026-01-07") to emptyMap<String, Long>()
        )
        assertThat(DailyAppUsageHistory.decode("2026-01-07|a=-1")).isNull()
        assertThat(DailyAppUsageHistory.decode("lixo")).isNull()
    }

    private fun resolve(
        now: String,
        dates: List<LocalDate>,
        stored: Map<LocalDate, Map<String, Long>> = emptyMap(),
        scan: ForegroundEventScan? = null,
        buckets: List<AggregatedAppBucket> = emptyList(),
        eligible: Set<String>? = null
    ) = resolveDailyAppUsage(
        dates = dates,
        today = LocalDateTime.parse(now).toLocalDate(),
        nowMs = ts(now),
        zoneId = zoneId,
        stored = stored,
        scan = scan,
        estimateBuckets = { buckets },
        isEligible = { eligible?.contains(it) ?: true }
    )

    private fun scan(
        first: String,
        vararg intervals: Pair<String, List<LongRange>>
    ) = ForegroundEventScan(
        intervalsByPackage = intervals.groupBy({ it.first }, { it.second })
            .mapValues { (_, lists) -> lists.flatten() },
        firstEventTimeMs = ts(first),
        lastEventTimeMs = ts(first),
        completed = true
    )

    private fun range(start: String, end: String): LongRange = ts(start) until ts(end)

    private fun date(value: String): LocalDate = LocalDate.parse(value)

    private fun ts(value: String): Long =
        LocalDateTime.parse(value).atZone(zoneId).toInstant().toEpochMilli()

    private val Int.minutes: Long
        get() = this * 60_000L

    private val Int.hours: Long
        get() = this * 60L * 60_000L
}
