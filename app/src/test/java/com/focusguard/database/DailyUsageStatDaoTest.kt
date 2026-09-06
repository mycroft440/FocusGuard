package com.focusguard.database

import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DailyUsageStatDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: DailyUsageStatDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = database.dailyUsageStatDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `addUsage accumulates repeated pulses for the same site and date`() = runBlocking {
        dao.addUsage("example.com", "2026-07-12", 5_000L)
        dao.addUsage("example.com", "2026-07-12", 7_500L)

        assertThat(dao.getUsageMillis("example.com", "2026-07-12"))
            .isEqualTo(12_500L)
    }

    @Test
    fun `addUsage keeps different sites isolated`() = runBlocking {
        dao.addUsage("example.com", "2026-07-12", 10_000L)
        dao.addUsage("another.com", "2026-07-12", 20_000L)

        assertThat(dao.getUsageMillis("example.com", "2026-07-12"))
            .isEqualTo(10_000L)
        assertThat(dao.getUsageMillis("another.com", "2026-07-12"))
            .isEqualTo(20_000L)
    }

    @Test
    fun `addUsage keeps dates isolated`() = runBlocking {
        dao.addUsage("example.com", "2026-07-12", 10_000L)
        dao.addUsage("example.com", "2026-07-13", 20_000L)

        assertThat(dao.getUsageMillis("example.com", "2026-07-12"))
            .isEqualTo(10_000L)
        assertThat(dao.getUsageMillis("example.com", "2026-07-13"))
            .isEqualTo(20_000L)
    }

    @Test
    fun `addUsage ignores invalid pulses`() = runBlocking {
        dao.addUsage("", "2026-07-12", 10_000L)
        dao.addUsage("example.com", "2026-07-12", 0L)
        dao.addUsage("example.com", "2026-07-12", -1L)

        assertThat(dao.getStatsForDateStatic("2026-07-12")).isEmpty()
    }
}
