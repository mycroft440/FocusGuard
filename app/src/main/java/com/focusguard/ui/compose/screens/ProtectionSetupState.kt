package com.focusguard.ui.compose.screens

import com.focusguard.manager.BlockingSessionManager
import com.focusguard.utils.WebsiteBlocker

internal enum class ProtectionMode {
    LIMIT,
    PASSWORD,
    DOPAMINE_FAST
}

internal data class ProtectionDraft(
    val appPackageNames: Set<String> = emptySet(),
    val websiteRules: Set<String> = emptySet()
) {
    val hasTargets: Boolean
        get() = appPackageNames.isNotEmpty() || websiteRules.isNotEmpty()

    val availableModes: List<ProtectionMode>
        get() = if (hasTargets) ProtectionMode.entries else emptyList()
}

/**
 * A domain is already covered when it exactly matches an existing rule or is
 * matched by an existing parent-domain/keyword/category rule. This is a
 * configuration comparison, so temporary PASSWORD visit grants are deliberately
 * ignored. Broader new rules remain available because they protect extra targets.
 */
internal fun isWebsiteRuleAlreadyBlocked(
    candidate: String,
    configuredRules: Collection<String>
): Boolean {
    val normalizedCandidate = WebsiteBlocker.normalizeRule(candidate)
    if (normalizedCandidate.isEmpty()) return false

    val normalizedConfigured = WebsiteBlocker.normalizeRules(configuredRules)
    if (normalizedCandidate in normalizedConfigured) return true

    return normalizedConfigured.any { configuredRule ->
        WebsiteBlocker.matchesRuleIgnoringGrants(
            urlOrDomain = normalizedCandidate,
            normalizedRule = configuredRule
        )
    }
}

/**
 * The protection modes are independent layers. A target is unavailable for a mode
 * only when that same mode is already configured for it. Other configured layers
 * (including a scheduled daily period) remain selectable so PASSWORD, daily limit,
 * scheduled periods and the dopamine fast can coexist and let
 * [com.focusguard.security.ProtectionHierarchy] decide which one owns access.
 */
internal fun configuredAppPackagesForMode(
    mode: ProtectionMode,
    configured: BlockingSessionManager.ConfiguredBlockedTargets
): Set<String> = when (mode) {
    ProtectionMode.LIMIT -> configured.limitedAppPackageNames
    ProtectionMode.PASSWORD -> configured.passwordAppPackageNames
    ProtectionMode.DOPAMINE_FAST -> configured.continuousAppPackageNames
}

internal fun configuredWebsiteRulesForMode(
    mode: ProtectionMode,
    configured: BlockingSessionManager.ConfiguredBlockedTargets
): Set<String> = when (mode) {
    ProtectionMode.LIMIT -> configured.limitedWebsiteRules
    ProtectionMode.PASSWORD -> configured.passwordWebsiteRules
    ProtectionMode.DOPAMINE_FAST -> configured.continuousWebsiteRules
}

/** Converte a duração informada na UI em um limite diário válido de até 24 horas. */
internal fun parseDailyLimitMinutes(hoursText: String, minutesText: String): Int? {
    val hoursValue = hoursText.trim()
    val minutesValue = minutesText.trim()
    val hours = if (hoursValue.isEmpty()) 0 else hoursValue.toIntOrNull() ?: return null
    val minutes = if (minutesValue.isEmpty()) 0 else minutesValue.toIntOrNull() ?: return null

    if (hours !in 0..24 || minutes !in 0..59) return null

    val totalMinutes = hours * 60 + minutes
    return totalMinutes.takeIf { it in 1..24 * 60 }
}
