package com.focusguard.manager

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import com.focusguard.database.AppDatabase
import com.focusguard.database.BlockSession
import com.focusguard.database.SessionAppCrossRef
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A reconciliação inteira (banco + relógio), como acontece no aparelho depois de a
 * data ser adiantada à mão: o jejum não pode ser dado como vencido.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ManualClockJumpReconciliationTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val hour = 60L * 60L * 1_000L
    private val day = 24L * hour

    /** O banco é o mesmo entre os testes: um jejum de outro teste não pode contar aqui. */
    @Before
    fun clearSessions() = runBlocking {
        AppDatabase.getDatabase(context).blockSessionDao().deactivateAllActiveSessions()
        Unit
    }

    @Test
    fun `a dopamine fast survives the date being moved past its end`() = runBlocking {
        val jump = 3L * day
        val anchorWallClock = givenClockMovedByHand(jump = jump, bootCount = 7)
        val createdAt = anchorWallClock - hour
        val id = insertFast(createdAt = createdAt, endTime = createdAt + day)

        BlockingSessionManager(context, mockk(relaxed = true)).checkAndEnforce()

        val session = AppDatabase.getDatabase(context).blockSessionDao().getActiveSessionById(id)
        assertThat(session).isNotNull()
        assertThat(session!!.endTime).isAtLeast(createdAt + day + jump)
    }

    @Test
    fun `the fast also survives on a phone that does not report the boot count`() = runBlocking {
        // Sem o número do boot a compensação não rodava, e o jejum era desativado.
        val jump = 3L * day
        val anchorWallClock = givenClockMovedByHand(jump = jump, bootCount = null)
        val createdAt = anchorWallClock - hour
        val id = insertFast(createdAt = createdAt, endTime = createdAt + day)

        BlockingSessionManager(context, mockk(relaxed = true)).checkAndEnforce()

        val session = AppDatabase.getDatabase(context).blockSessionDao().getActiveSessionById(id)
        assertThat(session).isNotNull()
        assertThat(session!!.endTime).isAtLeast(createdAt + day + jump)
    }

    @Test
    fun `the uninstall gate sees the fast once it syncs the clock compensation`() = runBlocking {
        // A desinstalação pelo app decide sem esperar a reconciliação: ela mesma
        // sincroniza a compensação, senão o jejum pareceria vencido.
        val jump = 3L * day
        val anchorWallClock = givenClockMovedByHand(jump = jump, bootCount = 7)
        val createdAt = anchorWallClock - hour
        insertFast(createdAt = createdAt, endTime = createdAt + day)
        val manager = BlockingSessionManager(context, mockk(relaxed = true))
        assertThat(manager.isUninstallBlockedByTimeFlow.first()).isFalse()

        manager.syncManualClockCompensation()

        assertThat(manager.isUninstallBlockedByTimeFlow.first()).isTrue()
    }

    /** @param bootCount null: o aparelho não informa o número do boot. */
    private fun givenClockMovedByHand(jump: Long, bootCount: Int?): Long {
        val resolver = context.contentResolver
        bootCount?.let { Settings.Global.putInt(resolver, Settings.Global.BOOT_COUNT, it) }
        // A pessoa desligou a hora automática para escolher a data.
        Settings.Global.putInt(resolver, Settings.Global.AUTO_TIME, 0)
        val wallNow = System.currentTimeMillis()
        val elapsedNow = SystemClock.elapsedRealtime()
        // A última reconciliação gravou a âncora com a hora certa, antes do salto.
        val anchorWallClock = wallNow - jump
        context.getSharedPreferences("clock_jump_anchor", Context.MODE_PRIVATE).edit()
            .putInt("boot_count", bootCount ?: -1)
            .putLong("boot_wall_clock_millis", wallNow - elapsedNow - jump)
            .putBoolean("auto_time_enabled", true)
            .putLong("wall_clock_millis", anchorWallClock)
            .putLong("elapsed_realtime_millis", elapsedNow)
            .commit()
        return anchorWallClock
    }

    private suspend fun insertFast(createdAt: Long, endTime: Long): Int {
        val database = AppDatabase.getDatabase(context)
        val id = database.blockSessionDao().insertNewSession(
            BlockSession(
                startTime = createdAt,
                endTime = endTime,
                sessionType = "TIME",
                isFixed24h = true,
                createdAt = createdAt
            )
        ).toInt()
        database.sessionAppCrossRefDao().insert(SessionAppCrossRef(id, "com.example.feed"))
        return id
    }
}
