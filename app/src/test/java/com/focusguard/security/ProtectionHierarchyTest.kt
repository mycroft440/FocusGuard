package com.focusguard.security

import com.focusguard.database.AppUsageLimit
import com.focusguard.database.BlockSession
import com.focusguard.utils.AppUsageLimitMeter
import com.google.common.truth.Truth.assertThat
import java.util.Calendar
import java.util.TimeZone
import org.junit.Test

class ProtectionHierarchyTest {

    private val utc = TimeZone.getTimeZone("UTC")
    private val app = "com.example.social"
    private val minute = 60_000L

    @Test
    fun `layers follow the documented precedence`() {
        val order = ProtectionHierarchy.Layer.entries
        assertThat(order).containsExactly(
            ProtectionHierarchy.Layer.FOCUS_MODE,
            ProtectionHierarchy.Layer.TIME,
            ProtectionHierarchy.Layer.DAILY_LIMIT,
            ProtectionHierarchy.Layer.PASSWORD
        ).inOrder()
        assertThat(
            ProtectionHierarchy.Layer.TIME.outranks(ProtectionHierarchy.Layer.DAILY_LIMIT)
        ).isTrue()
        assertThat(
            ProtectionHierarchy.Layer.DAILY_LIMIT.outranks(ProtectionHierarchy.Layer.PASSWORD)
        ).isTrue()
        assertThat(
            ProtectionHierarchy.Layer.PASSWORD.outranks(ProtectionHierarchy.Layer.DAILY_LIMIT)
        ).isFalse()
    }

    @Test
    fun `session types map to their layer`() {
        assertThat(ProtectionHierarchy.layerOf(BlockSession(sessionType = "PASSWORD")))
            .isEqualTo(ProtectionHierarchy.Layer.PASSWORD)
        assertThat(ProtectionHierarchy.layerOf(BlockSession(sessionType = "TIME")))
            .isEqualTo(ProtectionHierarchy.Layer.TIME)
        // Sessões POMODORO sobram do antigo Pomodoro rigoroso e não bloqueiam.
        assertThat(
            ProtectionHierarchy.layerOf(
                BlockSession(sessionType = "POMODORO", isBlockingEnabled = true)
            )
        ).isNull()
        assertThat(
            ProtectionHierarchy.layerOf(
                BlockSession(sessionType = "POMODORO", isBlockingEnabled = false)
            )
        ).isNull()
    }

    @Test
    fun `five minute limit counts only inside the 19h to 21h window`() {
        // Usuário só pode usar das 19h às 21h: período agendado de 21h até 19h.
        val day = instant(2025, 1, 6, 0, 0)
        val schedule = scheduled(startHour = 21, endHour = 19)
        val limit = limit(dailyMinutes = 5, createdAt = day - 3 * 24 * 60 * minute)
        val foreground = listOf(
            // Uso às 10h: o período agendado segurava o app, o limite aguardava.
            at(10, 0) until at(10, 30),
            // Uso dentro da janela liberada.
            at(19, 0) until at(19, 3)
        )

        val used = AppUsageLimitMeter.countedMillis(
            limit = limit,
            foregroundIntervals = foreground,
            waitingSessions = listOf(schedule),
            dayStartMillis = day,
            nowMillis = at(19, 10),
            timeZone = utc
        )

        assertThat(used).isEqualTo(3 * minute)
        assertThat(used / minute).isLessThan(limit.dailyLimitMinutes.toLong())
    }

    @Test
    fun `window created later in the day still defines the allowed period for that day`() {
        val day = instant(2025, 1, 6, 0, 0)
        // Agendado às 15h, depois do uso das 10h.
        val schedule = scheduled(startHour = 21, endHour = 19, startTime = at(15, 0))

        val waiting = ProtectionHierarchy.appLimitWaitingIntervals(
            packageName = app,
            sessions = listOf(schedule),
            rangeStartMillis = day,
            rangeEndMillis = at(20, 0),
            timeZone = utc
        )

        assertThat(waiting).containsExactly(day until at(19, 0))
    }

    @Test
    fun `password sessions never pause the limit`() {
        val password = ProtectionHierarchy.SessionTargets(
            session = BlockSession(sessionType = "PASSWORD", startTime = 0L, isFixed24h = true),
            appPackages = setOf(app)
        )

        val waiting = ProtectionHierarchy.appLimitWaitingIntervals(
            packageName = app,
            sessions = listOf(password),
            rangeStartMillis = at(0, 0),
            rangeEndMillis = at(23, 0),
            timeZone = utc
        )

        assertThat(waiting).isEmpty()
    }

    @Test
    fun `sessions that do not hold the app leave its limit running`() {
        val otherApp = scheduled(startHour = 21, endHour = 19, packages = setOf("com.other"))

        val waiting = ProtectionHierarchy.appLimitWaitingIntervals(
            packageName = app,
            sessions = listOf(otherApp),
            rangeStartMillis = at(0, 0),
            rangeEndMillis = at(23, 0),
            timeZone = utc
        )

        assertThat(waiting).isEmpty()
    }

    @Test
    fun `leftover strict pomodoro session never pauses an app limit`() {
        val pomodoro = ProtectionHierarchy.SessionTargets(
            session = BlockSession(
                sessionType = "POMODORO",
                isBlockingEnabled = true,
                isFixed24h = true,
                startTime = at(9, 0),
                endTime = at(9, 25)
            )
        )

        val waiting = ProtectionHierarchy.appLimitWaitingIntervals(
            packageName = app,
            sessions = listOf(pomodoro),
            rangeStartMillis = at(0, 0),
            rangeEndMillis = at(23, 0),
            timeZone = utc
        )

        assertThat(waiting).isEmpty()
    }

    @Test
    fun `dopamine fast pauses the limit only from its start`() {
        val fast = ProtectionHierarchy.SessionTargets(
            session = BlockSession(sessionType = "TIME", isFixed24h = true, startTime = at(14, 0)),
            appPackages = setOf(app)
        )

        val used = ProtectionHierarchy.countedUsageMillis(
            foregroundIntervals = listOf(at(13, 50) until at(14, 5)),
            waitingIntervals = ProtectionHierarchy.appLimitWaitingIntervals(
                packageName = app,
                sessions = listOf(fast),
                rangeStartMillis = at(0, 0),
                rangeEndMillis = at(15, 0),
                timeZone = utc
            ),
            countFromMillis = at(0, 0),
            untilMillis = at(15, 0)
        )

        assertThat(used).isEqualTo(10 * minute)
    }

    @Test
    fun `overlapping foreground and waiting intervals are never double counted`() {
        val used = ProtectionHierarchy.countedUsageMillis(
            foregroundIntervals = listOf(0L until 10 * minute, 5 * minute until 20 * minute),
            waitingIntervals = listOf(8 * minute until 12 * minute, 10 * minute until 14 * minute),
            countFromMillis = 2 * minute,
            untilMillis = 18 * minute
        )

        // [2, 18) de primeiro plano, menos [8, 14) aguardando = 10 minutos.
        assertThat(used).isEqualTo(10 * minute)
    }

    @Test
    fun `usage before the limit activation is not charged`() {
        val day = instant(2025, 1, 6, 0, 0)
        val limit = limit(dailyMinutes = 3, createdAt = at(12, 0))

        val used = AppUsageLimitMeter.countedMillis(
            limit = limit,
            foregroundIntervals = listOf(at(11, 0) until at(12, 2)),
            waitingSessions = emptyList(),
            dayStartMillis = day,
            nowMillis = at(12, 30),
            timeZone = utc
        )

        assertThat(used).isEqualTo(2 * minute)
    }

    @Test
    fun `limit activated on an earlier day counts from midnight`() {
        val day = instant(2025, 1, 6, 0, 0)
        val limit = limit(dailyMinutes = 30, createdAt = day - 60 * minute)

        val used = AppUsageLimitMeter.countedMillis(
            limit = limit,
            foregroundIntervals = listOf((day - 10 * minute) until (day + 4 * minute)),
            waitingSessions = emptyList(),
            dayStartMillis = day,
            nowMillis = at(8, 0),
            timeZone = utc
        )

        assertThat(used).isEqualTo(4 * minute)
    }

    private fun scheduled(
        startHour: Int,
        endHour: Int,
        startTime: Long = 0L,
        packages: Set<String> = setOf(app)
    ) = ProtectionHierarchy.SessionTargets(
        session = BlockSession(
            sessionType = "TIME",
            startTime = startTime,
            isFixed24h = false,
            isRecurring = true,
            recurringStartHour = startHour,
            recurringEndHour = endHour
        ),
        appPackages = packages
    )

    private fun limit(dailyMinutes: Int, createdAt: Long) = AppUsageLimit(
        packageName = app,
        appName = "Social",
        dailyLimitMinutes = dailyMinutes,
        createdAt = createdAt
    )

    /** Horário do dia 06/01/2025 (segunda-feira) em UTC. */
    private fun at(hour: Int, minute: Int): Long = instant(2025, 1, 6, hour, minute)

    private fun instant(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance(utc).apply {
            clear()
            set(year, month - 1, day, hour, minute)
        }.timeInMillis
}
