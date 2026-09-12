package com.focusguard.database

import androidx.room.Room
import com.focusguard.utils.WebsiteUsageLimitPolicy
import com.google.common.truth.Truth.assertThat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
class WebsiteUsageLimitDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var websiteDao: WebsiteUsageLimitDao
    private lateinit var dailyDao: DailyUsageStatDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        websiteDao = database.websiteUsageLimitDao()
        dailyDao = database.dailyUsageStatDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `recreated limit starts after usage already accumulated that day`() = runBlocking {
        val activation = 1_756_704_600_000L
        val date = dateOf(activation)
        dailyDao.addUsage("youtube.com", date, 50 * 60_000L)

        websiteDao.insert(
            WebsiteUsageLimit(
                domain = "youtube.com",
                dailyLimitMinutes = 30,
                createdAt = activation
            )
        )

        val statsAtActivation = websiteDao.getDailyStatsForDateStatic(date)
        val effectiveAtActivation = WebsiteUsageLimitPolicy.aggregateUsageByRule(
            usageByIdentifier = statsAtActivation.map { it.identifier to it.timeSpentMs },
            configuredRules = listOf("youtube.com")
        )
        assertThat(effectiveAtActivation["youtube.com"] ?: 0L).isEqualTo(0L)

        dailyDao.addUsage("youtube.com", date, 15 * 60_000L)
        val statsAfterNewUsage = websiteDao.getDailyStatsForDateStatic(date)
        val effectiveAfterNewUsage = WebsiteUsageLimitPolicy.aggregateUsageByRule(
            usageByIdentifier = statsAfterNewUsage.map { it.identifier to it.timeSpentMs },
            configuredRules = listOf("youtube.com")
        )
        assertThat(effectiveAfterNewUsage["youtube.com"]).isEqualTo(15 * 60_000L)
    }

    @Test
    fun `editing website limit preserves activation and does not refresh baseline`() = runBlocking {
        val activation = 1_756_704_600_000L
        val date = dateOf(activation)
        dailyDao.addUsage("example.com", date, 20 * 60_000L)
        websiteDao.insert(
            WebsiteUsageLimit(
                domain = "example.com",
                dailyLimitMinutes = 15,
                createdAt = activation
            )
        )
        dailyDao.addUsage("example.com", date, 10 * 60_000L)

        websiteDao.insert(
            WebsiteUsageLimit(
                domain = "example.com",
                dailyLimitMinutes = 45,
                lockMode = "BLOCK_UNTIL_TOMORROW:example.com",
                lockUntilTimestamp = activation + 86_400_000L,
                createdAt = activation + 50_000L
            )
        )

        val saved = websiteDao.getAllStatic().single()
        assertThat(saved.createdAt).isEqualTo(activation)
        assertThat(saved.dailyLimitMinutes).isEqualTo(45)

        val stats = websiteDao.getDailyStatsForDateStatic(date)
        val effective = WebsiteUsageLimitPolicy.aggregateUsageByRule(
            usageByIdentifier = stats.map { it.identifier to it.timeSpentMs },
            configuredRules = listOf("example.com")
        )
        assertThat(effective["example.com"]).isEqualTo(10 * 60_000L)
    }

    private fun dateOf(timestamp: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(timestamp))
}
