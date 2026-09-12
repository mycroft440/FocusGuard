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

    data class WebsiteCompanion(
        val packageName: String,
        val appName: String,
        val domain: String
    )

    data class AppCompanion(
        val packageName: String,
        val appName: String,
        val domain: String
    )

    fun domainForAppPackage(packageName: String): String? {
        return PredefinedApps.PREVENTIVE_APPS
            .firstOrNull { it.packageName == packageName }
            ?.domain
            ?.let(WebsiteBlocker::normalizeRule)
            ?.takeIf(String::isNotEmpty)
    }

    fun websiteCompanionsForApps(packageNames: Collection<String>): List<WebsiteCompanion> {
        if (packageNames.isEmpty()) return emptyList()

        val requestedPackages = packageNames.filter(String::isNotBlank).toSet()
        if (requestedPackages.isEmpty()) return emptyList()

        return PredefinedApps.PREVENTIVE_APPS.asSequence()
            .filter { it.packageName in requestedPackages }
            .mapNotNull { app ->
                domainForAppPackage(app.packageName)?.let { domain ->
                    WebsiteCompanion(
                        packageName = app.packageName,
                        appName = app.appName,
                        domain = domain
                    )
                }
            }
            .distinctBy { it.domain }
            .toList()
    }

    fun appForWebsiteRule(rule: String): PredefinedApps.AppInfo? {
        val normalizedRule = WebsiteBlocker.normalizeRule(rule)
        if (
            normalizedRule.isEmpty() ||
            WebsiteBlocker.isKeywordRule(normalizedRule) ||
            WebsiteBlocker.isPornographyRule(normalizedRule)
        ) return null

        return PredefinedApps.PREVENTIVE_APPS.firstOrNull { app ->
            app.domain
                ?.let(WebsiteBlocker::normalizeRule)
                ?.let { it == normalizedRule } == true
        }
    }

    fun appCompanionsForWebsiteRules(rules: Collection<String>): List<AppCompanion> {
        if (rules.isEmpty()) return emptyList()

        return rules.asSequence()
            .mapNotNull(::appForWebsiteRule)
            .mapNotNull { app ->
                domainForAppPackage(app.packageName)?.let { domain ->
                    AppCompanion(
                        packageName = app.packageName,
                        appName = app.appName,
                        domain = domain
                    )
                }
            }
            .distinctBy { it.packageName }
            .toList()
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
