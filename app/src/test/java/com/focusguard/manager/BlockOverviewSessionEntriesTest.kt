package com.focusguard.manager

import android.content.Context
import com.focusguard.database.AppDatabase
import com.focusguard.database.BlockSession
import com.focusguard.database.SessionAppCrossRef
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Dois bloqueios por período do mesmo app são duas camadas: a tela de detalhe precisa
 * de uma linha para cada, com o horário e a sessão (para cancelar) de cada uma.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BlockOverviewSessionEntriesTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val hour = 60L * 60L * 1_000L

    @Before
    fun clearSessions() = runBlocking {
        AppDatabase.getDatabase(context).blockSessionDao().deactivateAllActiveSessions()
        Unit
    }

    @Test
    fun `the same app in two daily periods shows one entry per session`() = runBlocking {
        val now = System.currentTimeMillis()
        val morning = insertPeriod(createdAt = now - 2 * hour, startHour = 8, endHour = 12)
        val evening = insertPeriod(createdAt = now - hour, startHour = 20, endHour = 23)

        val entries = BlockingSessionManager(context, mockk(relaxed = true))
            .getBlockOverview()
            .scheduledTimeEntries
            .filter { it.identifier == FEED }

        assertThat(entries.map { it.sessionId }).containsExactly(morning, evening).inOrder()
        assertThat(entries.map { it.dailyWindow?.startMinutes })
            .containsExactly(8 * 60, 20 * 60)
            .inOrder()
    }

    private suspend fun insertPeriod(createdAt: Long, startHour: Int, endHour: Int): Int {
        val database = AppDatabase.getDatabase(context)
        val id = database.blockSessionDao().insertNewSession(
            BlockSession(
                startTime = createdAt,
                endTime = createdAt + 30L * 24L * hour,
                sessionType = "TIME",
                isRecurring = true,
                isFixed24h = false,
                recurringStartHour = startHour,
                recurringEndHour = endHour,
                createdAt = createdAt
            )
        ).toInt()
        database.sessionAppCrossRefDao().insert(SessionAppCrossRef(id, FEED))
        return id
    }

    private companion object {
        const val FEED = "com.example.feed"
    }
}
