package com.focusguard.utils

import com.focusguard.data.PredefinedApps

/**
 * Maps the native and web surfaces of predefined distraction targets.
 *
 * The association is intentionally opt-in at the UI layer. These helpers only
 * answer whether a known counterpart exists; they never expand a block on their
 * own.
 */
object AssociatedBlockTargets {
    const val DEFAULT_BLOCK_COMPANION = false

    fun domainForAppPackage(packageName: String): String? {
        return PredefinedApps.PREVENTIVE_APPS
            .firstOrNull { it.packageName == packageName }
            ?.domain
            ?.let(WebsiteBlocker::normalizeRule)
            ?.takeIf(String::isNotEmpty)
    }

    fun appForWebsiteRule(rule: String): PredefinedApps.AppInfo? {
        val normalizedRule = WebsiteBlocker.normalizeRule(rule)
        if (normalizedRule.isEmpty() || WebsiteBlocker.isKeywordRule(normalizedRule)) return null

        return PredefinedApps.PREVENTIVE_APPS.firstOrNull { app ->
            app.domain
                ?.let(WebsiteBlocker::normalizeRule)
                ?.let { it == normalizedRule } == true
        }
    }

    /**
     * Resolves only companion apps whose website is still part of the final saved rules.
     * This keeps the website picker transactional: leaving it with Back, or removing a
     * website before Save, cannot leak its companion app into the parent draft.
     */
    fun selectedAppsForWebsiteRules(
        rules: Collection<String>,
        optedInPackages: Collection<String>
    ): List<PredefinedApps.AppInfo> {
        val optedIn = optedInPackages.filter(String::isNotBlank).toSet()
        if (optedIn.isEmpty()) return emptyList()

        return rules.asSequence()
            .mapNotNull(::appForWebsiteRule)
            .filter { it.packageName in optedIn }
            .distinctBy { it.packageName }
            .toList()
    }
}
