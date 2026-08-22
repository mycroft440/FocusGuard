package com.focusguard.security

import com.focusguard.database.BlockSession
import com.focusguard.domain.model.BlockSessionType

/** Repository-level invariant for destructive changes to protected session content. */
object SessionMutationPolicy {

    fun canRemoveProtectedContent(
        session: BlockSession?,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        if (session == null || !session.isActive) return true
        val endTime = session.endTime
        if (endTime != null && endTime <= nowMillis) return true

        return when (session.sessionType) {
            BlockSessionType.TIME, BlockSessionType.POMODORO -> false
            else -> true
        }
    }
}
