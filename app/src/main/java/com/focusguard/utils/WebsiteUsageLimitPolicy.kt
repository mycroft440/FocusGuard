package com.focusguard.utils

import java.util.Locale

/** Regra única para decidir quando um limite diário de site vira bloqueio. */
object WebsiteUsageLimitPolicy {
    private const val ACTIVATION_BASELINE_PREFIX = "__focusguard_website_limit_baseline__:"

    /**
     * Marker persisted in daily_usage_stats when a website rule is created.
     *
     * Website usage is stored as a day-wide aggregate. Without a baseline, deleting
     * and recreating a rule on the same day would immediately inherit the usage from
     * the previous activation. Keeping the offset in the same date bucket avoids a
     * schema migration and preserves the raw usage rows for analytics.
     */
    fun activationBaselineIdentifier(rule: String): String {
        val normalized = WebsiteBlocker.normalizeRule(rule)
        return if (normalized.isEmpty()) "" else ACTIVATION_BASELINE_PREFIX + normalized
    }

    fun aggregateUsageByRule(
        usageByIdentifier: Iterable<Pair<String, Long>>,
        configuredRules: Collection<String>
    ): Map<String, Long> {
        val normalizedRules = WebsiteBlocker.normalizeRules(configuredRules)
        if (normalizedRules.isEmpty()) return emptyMap()

        val rawTotals = aggregateRawUsageByRule(usageByIdentifier, normalizedRules)
        val baselines = mutableMapOf<String, Long>()
        usageByIdentifier.forEach { (identifier, timeSpentMs) ->
            val baselineRule = baselineRuleFromIdentifier(identifier) ?: return@forEach
            if (baselineRule !in normalizedRules) return@forEach
            baselines[baselineRule] = maxOf(
                baselines[baselineRule] ?: 0L,
                timeSpentMs.coerceAtLeast(0L)
            )
        }

        return normalizedRules.associateWith { rule ->
            ((rawTotals[rule] ?: 0L) - (baselines[rule] ?: 0L)).coerceAtLeast(0L)
        }.filterValues { it > 0L }
    }

    /** Raw day total, deliberately ignoring activation-baseline marker rows. */
    fun aggregateRawUsageByRule(
        usageByIdentifier: Iterable<Pair<String, Long>>,
        configuredRules: Collection<String>
    ): Map<String, Long> {
        val normalizedRules = WebsiteBlocker.normalizeRules(configuredRules)
        if (normalizedRules.isEmpty()) return emptyMap()

        val totals = mutableMapOf<String, Long>()
        usageByIdentifier.forEach { (identifier, timeSpentMs) ->
            if (timeSpentMs <= 0L || baselineRuleFromIdentifier(identifier) != null) {
                return@forEach
            }
            WebsiteBlocker.findMatchingRules(identifier, normalizedRules).forEach { rule ->
                totals[rule] = (totals[rule] ?: 0L) + timeSpentMs
            }
        }
        return totals
    }

    internal fun baselineRuleFromIdentifier(identifier: String): String? {
        if (!identifier.startsWith(ACTIVATION_BASELINE_PREFIX)) return null
        return WebsiteBlocker.normalizeRule(identifier.removePrefix(ACTIVATION_BASELINE_PREFIX))
            .takeIf(String::isNotEmpty)
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
     * A mesma entrada continua atendendo os modos legados de sites, mas reconhece os dois modos
     * novos dos limites de apps. Esses modos carregam o packageName depois de ':' para que o
     * estado persistente da pausa seja isolado por aplicativo sem exigir migração do banco.
     */
    fun requiresUrlObservationForHardLimit(
        lockMode: String,
        lockUntilTimestamp: Long?,
        nowMillis: Long
    ): Boolean {
        return when {
            UsageLimitBehaviorPolicy.isPauseMode(lockMode) ||
                UsageLimitBehaviorPolicy.isBlockUntilTomorrowMode(lockMode) ->
                UsageLimitBehaviorPolicy.isRuleActive(lockUntilTimestamp, nowMillis)
            else -> when (lockMode.uppercase(Locale.ROOT)) {
                "WARNING" -> false
                "TIME" -> lockUntilTimestamp?.let { it > nowMillis } == true
                "PASSWORD" -> lockUntilTimestamp?.let { nowMillis >= it } ?: true
                else -> true
            }
        }
    }

    fun isBlockingModeActive(
        lockMode: String,
        lockUntilTimestamp: Long?,
        nowMillis: Long
    ): Boolean {
        return when {
            UsageLimitBehaviorPolicy.isPauseMode(lockMode) -> {
                if (!UsageLimitBehaviorPolicy.isRuleActive(lockUntilTimestamp, nowMillis)) {
                    false
                } else {
                    UsageLimitPauseStateStore.shouldBlockForPause(
                        lockMode = lockMode,
                        ruleEndMillis = lockUntilTimestamp,
                        nowMillis = nowMillis
                    )
                }
            }
            UsageLimitBehaviorPolicy.isBlockUntilTomorrowMode(lockMode) -> {
                val active = UsageLimitBehaviorPolicy.isRuleActive(
                    lockUntilTimestamp,
                    nowMillis
                )
                if (active) {
                    UsageLimitPauseStateStore.notifyDailyBlockOnce(
                        lockMode = lockMode,
                        ruleEndMillis = lockUntilTimestamp,
                        nowMillis = nowMillis
                    )
                }
                active
            }
            else -> when (lockMode.uppercase(Locale.ROOT)) {
                "WARNING" -> false
                "TIME" -> lockUntilTimestamp?.let { it > nowMillis } == true
                // Em PASSWORD, o timestamp representa uma liberação temporária
                // concedida após autenticação e válida até a próxima meia-noite.
                "PASSWORD" -> lockUntilTimestamp?.let { nowMillis >= it } ?: true
                else -> true
            }
        }
    }
}
