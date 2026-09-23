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
class PasswordAppSessionDaoTest {

    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `app lookup returns only its active password sessions newest first`() = runBlocking {
        val sessions = database.blockSessionDao()
        val apps = database.sessionAppCrossRefDao()
        val target = "com.example.target"

        val olderPassword = sessions.insertBlockSession(
            BlockSession(startTime = 1_000L, sessionType = "PASSWORD")
        ).toInt()
        val newerPassword = sessions.insertBlockSession(
            BlockSession(startTime = 2_000L, sessionType = "PASSWORD")
        ).toInt()
        val timed = sessions.insertBlockSession(
            BlockSession(startTime = 3_000L, sessionType = "TIME")
        ).toInt()
        val inactivePassword = sessions.insertBlockSession(
            BlockSession(startTime = 4_000L, sessionType = "PASSWORD", isActive = false)
        ).toInt()
        val otherApp = sessions.insertBlockSession(
            BlockSession(startTime = 5_000L, sessionType = "PASSWORD")
        ).toInt()

        listOf(olderPassword, newerPassword, timed, inactivePassword).forEach { sessionId ->
            apps.insert(SessionAppCrossRef(sessionId, target))
        }
        apps.insert(SessionAppCrossRef(otherApp, "com.example.other"))

        assertThat(sessions.getActivePasswordSessionsForApp(target).map { it.id })
            .containsExactly(newerPassword, olderPassword).inOrder()
        assertThat(sessions.getActivePasswordSessionsForApp("com.example.other").map { it.id })
            .containsExactly(otherApp)
        assertThat(sessions.getActivePasswordSessionsForApp("com.example.unrelated"))
            .isEmpty()
    }
}
