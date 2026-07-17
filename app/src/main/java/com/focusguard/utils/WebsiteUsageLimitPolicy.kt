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

        return isBlockingModeActive(lockMode, lockUntilTimestamp, nowMillis)
    }

    /**
     * Um bloqueio por tempo sem data final é inválido e falha de forma segura.
     * Isso evita que registros legados ou incompletos prendam o usuário para sempre.
     */
    fun isBlockingModeActive(
        lockMode: String,
        lockUntilTimestamp: Long?,
        nowMillis: Long
    ): Boolean {
        return when (lockMode.uppercase(Locale.ROOT)) {
            "WARNING" -> false
            "TIME" -> lockUntilTimestamp?.let { it > nowMillis } == true
            else -> true
        }
    }
}
