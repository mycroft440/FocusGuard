package com.focusguard.utils

import com.focusguard.database.UsageLimitLockMode
import java.util.Locale

/** Regra única para decidir quando um limite diário de site vira bloqueio. */
object WebsiteUsageLimitPolicy {

    fun aggregateUsageByRule(
        usageByIdentifier: Iterable<Pair<String, Long>>,
        configuredRules: Collection<String>
    ): Map<String, Long> {
        val normalizedRules = WebsiteBlocker.normalizeRules(configuredRules)
        if (normalizedRules.isEmpty()) return emptyMap()

        val totals = mutableMapOf<String, Long>()
        usageByIdentifier.forEach { (identifier, timeSpentMs) ->
            if (timeSpentMs <= 0L) return@forEach
            WebsiteBlocker.findMatchingRules(identifier, normalizedRules).forEach { rule ->
                totals[rule] = (totals[rule] ?: 0L) + timeSpentMs
            }
        }
        return totals
    }

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
            // Em PASSWORD, o timestamp representa uma liberação temporária
            // concedida após autenticação e válida até a próxima meia-noite.
            "PASSWORD" -> lockUntilTimestamp?.let { nowMillis >= it } ?: true
            else -> true
        }
    }

    fun shouldBlock(
        usedMillis: Long,
        dailyLimitMinutes: Int,
        lockMode: UsageLimitLockMode,
        lockUntilTimestamp: Long?,
        nowMillis: Long
    ): Boolean = shouldBlock(
        usedMillis,
        dailyLimitMinutes,
        lockMode.name,
        lockUntilTimestamp,
        nowMillis
    )

    fun isBlockingModeActive(
        lockMode: UsageLimitLockMode,
        lockUntilTimestamp: Long?,
        nowMillis: Long
    ): Boolean = isBlockingModeActive(lockMode.name, lockUntilTimestamp, nowMillis)
}
