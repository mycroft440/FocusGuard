package com.focusguard.service

import com.focusguard.database.BlockSession
import com.focusguard.receiver.BlockingScheduleCalculator
import java.util.TimeZone

/** Pure timing policy for live protection handoffs while Accessibility is alive. */
internal object HierarchyBoundaryPolicy {
    fun nextBoundary(
        sessions: Collection<BlockSession>,
        nowMillis: Long,
        timeZone: TimeZone = TimeZone.getDefault()
    ): Long? = BlockingScheduleCalculator.nextBoundary(
        sessions = sessions,
        additionalBoundaries = emptyList(),
        nowMillis = nowMillis,
        timeZone = timeZone
    )

    fun delayMillis(boundaryMillis: Long, nowMillis: Long): Long =
        (boundaryMillis - nowMillis).coerceAtLeast(1L)
}
