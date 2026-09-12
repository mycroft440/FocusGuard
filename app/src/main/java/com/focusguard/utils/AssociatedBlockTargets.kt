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
}
