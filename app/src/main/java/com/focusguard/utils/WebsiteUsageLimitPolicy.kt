package com.focusguard.utils

import java.util.Locale

/** Regra única para decidir quando um limite diário de site vira bloqueio. */
object WebsiteUsageLimitPolicy {

    fun shouldBlock(
        usedMillis: Long,
        dailyLimitMinutes: Int,
        lockMode: String,
        lockUntilTimestamp: Long?,
        nowMillis: Long
    ): Boolean {
        val thresholdMillis = dailyLimitMinutes.coerceAtLeast(0) * 60_000L
        if (usedMillis.coerceAtLeast(0L) < thresholdMillis) return false

        return when (lockMode.uppercase(Locale.ROOT)) {
            "WARNING" -> false
            "TIME" -> lockUntilTimestamp == null || lockUntilTimestamp > nowMillis
            else -> true
        }
    }
}
