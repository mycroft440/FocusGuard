package com.focusguard.security

import com.focusguard.database.BlockSession

/** Repository-level invariant for destructive changes to protected session content. */
object SessionMutationPolicy {

    fun canRemoveProtectedContent(
        session: BlockSession?,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        if (session == null || !session.isActive) return true
        if (session.endTime != null && session.endTime <= nowMillis) return true

        return when (session.sessionType.uppercase()) {
            "TIME", "POMODORO" -> false
            else -> true
        }
    }
}
