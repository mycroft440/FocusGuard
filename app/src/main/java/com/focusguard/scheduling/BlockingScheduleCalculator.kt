package com.focusguard.scheduling

import com.focusguard.database.BlockSession
import java.util.Calendar
import java.util.TimeZone

/** Pure boundary calculation, independent from AlarmManager and receivers. */
object BlockingScheduleCalculator {

    fun nextLocalMidnight(
        nowMillis: Long,
        timeZone: TimeZone = TimeZone.getDefault()
    ): Long = Calendar.getInstance(timeZone).apply {
        timeInMillis = nowMillis
        add(Calendar.DAY_OF_YEAR, 1)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun nextBoundary(
        sessions: Collection<BlockSession>,
        additionalBoundaries: Collection<Long>,
        nowMillis: Long,
        timeZone: TimeZone = TimeZone.getDefault()
    ): Long? {
        var nextBoundary = additionalBoundaries
            .asSequence()
            .filter { it > nowMillis }
            .minOrNull()

        fun consider(candidate: Long, sessionEnd: Long? = null) {
            if (candidate <= nowMillis) return
            if (sessionEnd != null && candidate > sessionEnd) return
            if (nextBoundary == null || candidate < nextBoundary!!) nextBoundary = candidate
        }

        sessions.asSequence().filter { it.isActive }.forEach { session ->
            session.endTime?.let { consider(it) }
            if (session.isFixed24h) return@forEach

            val startMinutes = session.recurringStartHour * 60 + session.recurringStartMinute
            val endMinutes = session.recurringEndHour * 60 + session.recurringEndMinute
            if (startMinutes == endMinutes) return@forEach

            val allowedDays = session.recurringDaysOfWeek
            val baseDay = Calendar.getInstance(timeZone).apply {
                timeInMillis = nowMillis
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }

            // The previous day covers the end of windows that cross midnight.
            for (offset in -1..7) {
                val logicalDay = (baseDay.clone() as Calendar).apply {
                    add(Calendar.DAY_OF_YEAR, offset)
                }
                if (allowedDays.isNotEmpty() &&
                    logicalDay.get(Calendar.DAY_OF_WEEK) !in allowedDays
                ) continue

                val start = (logicalDay.clone() as Calendar).apply {
                    set(Calendar.HOUR_OF_DAY, session.recurringStartHour)
                    set(Calendar.MINUTE, session.recurringStartMinute)
                }
                val end = (logicalDay.clone() as Calendar).apply {
                    set(Calendar.HOUR_OF_DAY, session.recurringEndHour)
                    set(Calendar.MINUTE, session.recurringEndMinute)
                    if (endMinutes < startMinutes) add(Calendar.DAY_OF_YEAR, 1)
                }
                consider(start.timeInMillis, session.endTime)
                consider(end.timeInMillis, session.endTime)
            }
        }
        return nextBoundary
    }
}
