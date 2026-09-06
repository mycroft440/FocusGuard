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
class AppUsageLimitDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: AppUsageLimitDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = database.appUsageLimitDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `editing an existing limit preserves original activation timestamp`() = runBlocking {
        dao.insert(
            AppUsageLimit(
                packageName = "com.example.social",
                appName = "Social",
                dailyLimitMinutes = 15,
                createdAt = 1_000L
            )
        )

        dao.insert(
            AppUsageLimit(
                packageName = "com.example.social",
                appName = "Social",
                dailyLimitMinutes = 45,
                lockMode = "BLOCK_UNTIL_TOMORROW:com.example.social",
                lockUntilTimestamp = 99_000L,
                createdAt = 50_000L
            )
        )

        val saved = dao.getLimitForPackage("com.example.social")
        assertThat(saved?.createdAt).isEqualTo(1_000L)
        assertThat(saved?.dailyLimitMinutes).isEqualTo(45)
        assertThat(saved?.lockMode)
            .isEqualTo("BLOCK_UNTIL_TOMORROW:com.example.social")
        assertThat(saved?.lockUntilTimestamp).isEqualTo(99_000L)
    }

    @Test
    fun `remove and readd creates a genuinely new activation`() = runBlocking {
        val first = AppUsageLimit(
            packageName = "com.example.social",
            appName = "Social",
            dailyLimitMinutes = 15,
            createdAt = 1_000L
        )
        dao.insert(first)
        dao.delete(first)

        dao.insert(
            first.copy(
                dailyLimitMinutes = 30,
                createdAt = 50_000L
            )
        )

        assertThat(dao.getLimitForPackage("com.example.social")?.createdAt)
            .isEqualTo(50_000L)
    }
}
