package com.focusguard.analytics

import com.focusguard.utils.ForegroundIntervalCollector
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.random.Random

/**
 * Sequências de eventos sorteadas (com semente fixa) contra as regras que nenhuma
 * ordem de eventos pode quebrar: dois apps nunca estão em primeiro plano ao mesmo
 * tempo, o uso nunca passa do tempo decorrido e trechos de tela nunca se sobrepõem.
 */
class UsageIntervalInvariantsTest {

    @Test
    fun `app intervals never overlap nor exceed the elapsed time`() {
        val random = Random(42)
        repeat(RUNS) {
            val legacyBoot = if (random.nextBoolean()) random.nextLong(0, 4_000_000) else null
            val collector = ForegroundIntervalCollector(legacyBoot)
            var at = 0L
            repeat(EVENTS_PER_RUN) {
                at += random.nextLong(0, 120_000)
                collector.onUsageEvent(
                    eventType = APP_EVENT_TYPES[random.nextInt(APP_EVENT_TYPES.size)],
                    packageName = listOf("a", "b", "c", null)[random.nextInt(4)],
                    activityClassName = listOf("X", "Y", null)[random.nextInt(3)],
                    atMillis = at
                )
            }
            val end = at + random.nextLong(0, 300_000)
            val all = listOf("a", "b", "c")
                .flatMap { collector.intervalsFor(it, end, random.nextBoolean()) }
                .sortedBy { it.first }

            all.zipWithNext().forEach { (earlier, later) ->
                assertThat(later.first).isAtLeast(earlier.last + 1)
            }
            assertThat(all.sumOf { it.last + 1 - it.first }).isAtMost(end)
        }
    }

    @Test
    fun `phone intervals never overlap and stay inside the observed time`() {
        val random = Random(7)
        repeat(RUNS) {
            val legacyBoot = if (random.nextBoolean()) random.nextLong(0, 4_000_000) else null
            val accumulator = ScreenInteractiveSessionAccumulator(legacyBoot)
            var at = 1_000L
            repeat(EVENTS_PER_RUN) {
                at += random.nextLong(0, 120_000)
                when (random.nextInt(7)) {
                    0 -> accumulator.onScreenInteractive(at)
                    1 -> accumulator.onScreenNonInteractive(at)
                    2 -> accumulator.onKeyguardShown(at)
                    3 -> accumulator.onKeyguardHidden(at)
                    4 -> accumulator.onDeviceShutdown(at)
                    5 -> accumulator.onDeviceStartup(at)
                    6 -> accumulator.onAnyEvent(at)
                }
            }
            val end = at + 1
            val intervals = accumulator.finish(end)

            intervals.zipWithNext().forEach { (earlier, later) ->
                assertThat(later.startTimeMs).isAtLeast(earlier.endTimeMs)
            }
            intervals.forEach { interval ->
                assertThat(interval.endTimeMs).isGreaterThan(interval.startTimeMs)
                assertThat(interval.endTimeMs).isAtMost(end)
            }
        }
    }

    private companion object {
        const val RUNS = 2_000
        const val EVENTS_PER_RUN = 60

        // RESUMED, PAUSED, STOPPED, tela apagada, desligar, ligar, tela acesa,
        // bloqueio, desbloqueio e um tipo que o coletor ignora (STANDBY_BUCKET_CHANGED).
        val APP_EVENT_TYPES = listOf(1, 2, 23, 16, 26, 27, 15, 17, 18, 11)
    }
}
