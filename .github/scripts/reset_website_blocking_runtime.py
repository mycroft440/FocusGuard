from pathlib import Path


def replace_exact(path: Path, old: str, new: str, expected: int = 1) -> None:
    text = path.read_text()
    count = text.count(old)
    if count != expected:
        raise SystemExit(f"{path}: expected {expected} occurrence(s), found {count}")
    path.write_text(text.replace(old, new))


decision = Path(
    "app/src/main/java/com/focusguard/accessibility/website/blocking/WebsiteBlockDecisionPolicy.kt"
)
decision.write_text(
    """package com.focusguard.accessibility.website.blocking

/**
 * Runtime seam for website protection.
 *
 * Website selection, normalization and persistence intentionally remain available,
 * but enforcement is reset: no configured website currently owns a browser visit.
 * This keeps the configuration UI intact while the blocking implementation can be
 * rebuilt from zero without leaving an old redirect path active.
 */
internal object WebsiteBlockDecisionPolicy {
    enum class Owner { HARD, PASSWORD, NONE }

    data class Resolution(
        val owner: Owner,
        val matchedRule: String? = null
    )

    @Suppress(\"UNUSED_PARAMETER\")
    fun resolve(
        candidate: String,
        passwordRules: Collection<String>,
        strongerRules: Collection<String>
    ): Resolution = Resolution(Owner.NONE)
}
"""
)

service = Path("app/src/main/java/com/focusguard/service/BlockingAccessibilityService.kt")
replace_exact(
    service,
    """    private fun websiteSurfaceInspectionNeeded(): Boolean =
        blockedWebsitesDomainSet.isNotEmpty() || limitedWebsiteDomains.isNotEmpty()

    private fun websiteObservationRequired(): Boolean =
        blockedWebsitesDomainSet.isNotEmpty() || hardLimitedWebsiteDomains.isNotEmpty()
""",
    """    // Website configuration remains persisted, but its runtime enforcement is
    // intentionally reset. No browser inspection/redirect pipeline is started.
    private fun websiteSurfaceInspectionNeeded(): Boolean = false

    private fun websiteObservationRequired(): Boolean = false
""",
)
replace_exact(
    service,
    """        val blockedWebsiteDomain = blockedWebsiteAppDomains[packageName]
        val limitedWebsiteDomain = limitedWebsiteAppDomains[packageName]
""",
    """        // Native apps are no longer treated as an enforcement extension of a
        // configured website while the website runtime is being rebuilt.
        val blockedWebsiteDomain: String? = null
        val limitedWebsiteDomain: String? = null
""",
)
replace_exact(
    service,
    """        if (currentPackage in browserPackages && blockedWebsitesDomainSet.isNotEmpty()) {
""",
    """        if (websiteSurfaceInspectionNeeded() &&
            currentPackage in browserPackages && blockedWebsitesDomainSet.isNotEmpty()
        ) {
""",
)

manager = Path("app/src/main/java/com/focusguard/manager/BlockingSessionManager.kt")
replace_exact(
    manager,
    """                val strongerWebsiteRules = WebsiteBlocker.normalizeRules(
                    strongerSessionSites + limitSites + adultFilterRules
                )
                PasswordTargetAccessGrant.updateStrongerWebsiteRules(strongerWebsiteRules)

                val sitesToBlock = (sessionSites + limitSites + adultFilterRules)
                    .map(WebsiteBlocker::normalizeRule)
                    .filter { it.isNotBlank() }
                    .distinct()
                val pornographyCategoryActive =
                    WebsiteBlocker.containsPornographyRule(sitesToBlock)
                deviceOwnerManager.setPornographyCategoryActive(pornographyCategoryActive)
""",
    """                // Keep website rules configured in Room/UI, but publish no runtime
                // website owner while the blocking implementation is reset.
                val strongerWebsiteRules = emptySet<String>()
                PasswordTargetAccessGrant.updateStrongerWebsiteRules(emptySet())
                val sitesToBlock = emptyList<String>()
                deviceOwnerManager.setPornographyCategoryActive(false)
""",
)
replace_exact(
    manager,
    """                if (sitesToBlock.isEmpty() && !adultFilterEnabled) {
                    deviceOwnerManager.clearWebsiteRestrictions()
                } else {
                    deviceOwnerManager.enforceWebsiteRestrictions(sitesToBlock)
                }
""",
    """                // Do not leave a Device Owner URLBlocklist active after resetting
                // the Accessibility website runtime. Global adult-DNS configuration is
                // intentionally owned by its separate setting and is not changed here.
                deviceOwnerManager.clearWebsiteRestrictions()
""",
)
replace_exact(
    manager,
    """                val selfProtectionRequired = shouldArmSelfProtection(
                    hasEnforcingSessions = enforcingSessions.isNotEmpty(),
                    hasBlockedApps = appsToBlock.isNotEmpty(),
                    hasBlockedSites = sitesToBlock.isNotEmpty(),
                    adultFilterEnabled = adultFilterEnabled,
                    focusModeActive = focusModeSession != null
                ) || activeTimeCommitment
""",
    """                val selfProtectionRequired = shouldArmSelfProtection(
                    // A website-only session must not arm app/device enforcement while
                    // website runtime blocking is intentionally disabled.
                    hasEnforcingSessions = appsToBlock.isNotEmpty() || strictPomodoro,
                    hasBlockedApps = appsToBlock.isNotEmpty(),
                    hasBlockedSites = false,
                    adultFilterEnabled = adultFilterEnabled,
                    focusModeActive = focusModeSession != null
                ) || (activeTimeCommitment && appsToBlock.isNotEmpty())
""",
)
replace_exact(
    manager,
    """                        blockedSites = sitesToBlock,
                        blockingActive = selfProtectionRequired,
                        strictPomodoro = strictPomodoro,
                        passwordSites = passwordSessionSites,
                        strongerSites = strongerWebsiteRules
""",
    """                        blockedSites = emptyList(),
                        blockingActive = selfProtectionRequired,
                        strictPomodoro = strictPomodoro,
                        passwordSites = emptyList(),
                        strongerSites = emptySet()
""",
)

# Scope guard: configuration/selection remains present and runtime Device Owner
# URL blocking is no longer invoked by the reconciler.
required = [
    Path("app/src/main/java/com/focusguard/ui/compose/screens/BlockTargetTabs.kt"),
    Path("app/src/main/java/com/focusguard/data/PredefinedWebsites.kt"),
]
for path in required:
    if not path.is_file():
        raise SystemExit(f"required configuration file missing: {path}")

website_blocker = Path("app/src/main/java/com/focusguard/utils/WebsiteBlocker.kt").read_text()
if "fun normalizeRule" not in website_blocker:
    raise SystemExit("website normalization/configuration seam is missing")
if "deviceOwnerManager.enforceWebsiteRestrictions(sitesToBlock)" in manager.read_text():
    raise SystemExit("Device Owner website URL blocking call is still wired")
