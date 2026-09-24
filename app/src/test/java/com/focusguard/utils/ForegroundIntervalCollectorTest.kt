package com.focusguard.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ForegroundIntervalCollectorTest {

    private val video = "com.example.video"
    private val music = "com.example.music"

    @Test
    fun `only resumed time counts and switching apps closes at the pause`() {
        val collector = ForegroundIntervalCollector()
        collector.onActivityResumed(video, "Main", 1_000L)
        collector.onActivityLeft(video, "Main", 5_000L)
        collector.onActivityResumed(music, "Player", 5_400L)
        collector.onActivityLeft(music, "Player", 7_000L)
        collector.onActivityResumed("com.launcher", "Home", 7_100L)

        assertThat(collector.intervalsFor(video, 20_000L, deviceInteractive = true))
            .containsExactly(1_000L until 5_000L)
        // Música tocando em segundo plano depois de 7s não conta.
        assertThat(collector.intervalsFor(music, 20_000L, deviceInteractive = true))
            .containsExactly(5_400L until 7_000L)
    }

    @Test
    fun `activity change inside the same app keeps one continuous interval`() {
        val collector = ForegroundIntervalCollector()
        collector.onActivityResumed(video, "Feed", 0L)
        collector.onActivityLeft(video, "Feed", 3_000L)
        collector.onActivityResumed(video, "Player", 3_200L)
        collector.onActivityLeft(video, "Player", 9_000L)
        collector.onActivityResumed("com.launcher", "Home", 9_100L)

        assertThat(collector.intervalsFor(video, 20_000L, deviceInteractive = true))
            .containsExactly(0L until 9_000L)
    }

    @Test
    fun `screen off ends foreground use`() {
        val collector = ForegroundIntervalCollector()
        collector.onActivityResumed(video, "Main", 0L)
        collector.onDeviceInactive(4_000L)

        assertThat(collector.intervalsFor(video, 60_000L, deviceInteractive = false))
            .containsExactly(0L until 4_000L)
    }

    @Test
    fun `open interval reaches now only while the device is interactive`() {
        val collector = ForegroundIntervalCollector()
        collector.onActivityResumed(video, "Main", 1_000L)

        assertThat(collector.intervalsFor(video, 10_000L, deviceInteractive = true))
            .containsExactly(1_000L until 10_000L)
        assertThat(collector.intervalsFor(video, 10_000L, deviceInteractive = false))
            .isEmpty()
    }

    @Test
    fun `projection on a copy does not change committed state`() {
        val committed = ForegroundIntervalCollector()
        committed.onActivityResumed(video, "Main", 0L)

        val view = committed.copy()
        view.onActivityLeft(video, "Main", 2_000L)
        view.onActivityResumed(music, "Player", 2_100L)

        assertThat(view.intervalsFor(video, 5_000L, deviceInteractive = true))
            .containsExactly(0L until 2_000L)
        assertThat(committed.intervalsFor(video, 5_000L, deviceInteractive = true))
            .containsExactly(0L until 5_000L)
    }
}
