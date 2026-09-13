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
 * matched by an existing parent-domain/keyword rule. Broader new rules remain
 * available (for example, adding `keyword:video` when only one video site is
 * configured), because they protect additional targets.
 */
internal fun isWebsiteRuleAlreadyBlocked(
    candidate: String,
    configuredRules: Collection<String>
): Boolean {
    val normalizedCandidate = WebsiteBlocker.normalizeRule(candidate)
    if (normalizedCandidate.isEmpty()) return false

    val normalizedConfigured = WebsiteBlocker.normalizeRules(configuredRules)
    if (normalizedCandidate in normalizedConfigured) return true

    return WebsiteBlocker.findMatchingRule(
        normalizedCandidate,
        normalizedConfigured
    ) != null
}

/**
 * The three protection modes are independent layers. A target is unavailable for
 * a mode only when that same mode is already configured for it. Other configured
 * layers remain selectable so PASSWORD, daily limit and TIME can coexist and let
 * the runtime hierarchy decide which one owns access at each instant.
 */
internal fun configuredAppPackagesForMode(
    mode: ProtectionMode,
    configured: BlockingSessionManager.ConfiguredBlockedTargets
): Set<String> = when (mode) {
    ProtectionMode.LIMIT -> configured.limitedAppPackageNames
    ProtectionMode.PASSWORD -> configured.passwordAppPackageNames
    ProtectionMode.DOPAMINE_FAST -> configured.exclusiveAppPackageNames
}

internal fun configuredWebsiteRulesForMode(
    mode: ProtectionMode,
    configured: BlockingSessionManager.ConfiguredBlockedTargets
): Set<String> = when (mode) {
    ProtectionMode.LIMIT -> configured.limitedWebsiteRules
    ProtectionMode.PASSWORD -> configured.passwordWebsiteRules
    ProtectionMode.DOPAMINE_FAST -> configured.exclusiveWebsiteRules
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
