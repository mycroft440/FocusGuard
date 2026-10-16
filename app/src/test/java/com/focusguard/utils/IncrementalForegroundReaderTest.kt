package com.focusguard.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Mudar a hora faz o Android mover todo o histórico de uso; a leitura incremental
 * não pode continuar com o estado da hora antiga.
 */
class IncrementalForegroundReaderTest {
    private val minute = 60_000L
    private val hour = 60L * minute
    private val dayStart = 10L * 24L * hour
    private val t0 = dayStart + 9L * hour
    private val bootWall = 1_000_000L

    @Test
    fun `setting the clock back does not drop the time used after the change`() {
        val history = History()
        val reader = IncrementalForegroundReader()
        history.add(RESUMED, "feed", t0)
        assertThat(reader.usage(history, now = t0 + hour, bootWall = bootWall))
            .isEqualTo(hour)

        // Relógio atrasado 3 h: o Android leva o histórico junto.
        history.shift(-3L * hour)
        // Ainda na mesma tela, duas horas reais depois e mais duas depois disso.
        reader.usage(history, now = t0, bootWall = bootWall - 3L * hour)
        val used = reader.usage(history, now = t0 + 2L * hour, bootWall = bootWall - 3L * hour)

        assertThat(used).isEqualTo(5L * hour)
    }

    @Test
    fun `setting the clock forward does not stretch the open stretch by the jump`() {
        val history = History()
        val reader = IncrementalForegroundReader()
        history.add(RESUMED, "feed", t0)
        reader.usage(history, now = t0 + 30L * minute, bootWall = bootWall)

        history.shift(2L * hour)
        // Sai do app uma hora real depois de abrir.
        history.add(PAUSED, "feed", t0 + 3L * hour)
        history.add(RESUMED, "launcher", t0 + 3L * hour)
        val used = reader.usage(
            history,
            now = t0 + 3L * hour + 10_000L,
            bootWall = bootWall + 2L * hour
        )

        assertThat(used).isEqualTo(hour)
    }

    @Test
    fun `small drift keeps the incremental state`() {
        val history = History()
        val reader = IncrementalForegroundReader()
        history.add(RESUMED, "feed", t0)
        reader.usage(history, now = t0 + hour, bootWall = bootWall)
        val firstReads = history.reads

        reader.usage(history, now = t0 + hour + 1_000L, bootWall = bootWall + 500L)

        // Só a cauda recente foi lida de novo, não o dia inteiro.
        assertThat(history.reads.drop(firstReads.size).none { it.first < t0 }).isTrue()
    }

    private fun IncrementalForegroundReader.usage(
        history: History,
        now: Long,
        bootWall: Long
    ): Long = intervals(
        packages = setOf("feed"),
        dayStartMillis = dayStart,
        nowMillis = now,
        isDeviceInteractive = true,
        bootWallClockMillis = bootWall,
        feed = history::feed
    ).getValue("feed").sumOf { it.last + 1 - it.first }

    private class History {
        private val events = mutableListOf<Triple<Int, String, Long>>()
        val reads = mutableListOf<Pair<Long, Long>>()

        fun add(type: Int, packageName: String, at: Long) {
            events += Triple(type, packageName, at)
        }

        fun shift(delta: Long) {
            events.replaceAll { it.copy(third = it.third + delta) }
        }

        fun feed(collector: ForegroundIntervalCollector, from: Long, until: Long) {
            reads += from to until
            events.filter { it.third in from until until }
                .sortedBy { it.third }
                .forEach { (type, packageName, at) ->
                    collector.onUsageEvent(type, packageName, "Main", at)
                }
        }
    }

    private companion object {
        const val RESUMED = 1
        const val PAUSED = 2
    }
}
