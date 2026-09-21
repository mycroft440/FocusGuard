from pathlib import Path


def replace_exact(path: Path, old: str, new: str, expected: int = 1) -> None:
    text = path.read_text()
    count = text.count(old)
    if count != expected:
        raise SystemExit(f"{path}: expected {expected} occurrence(s), found {count}")
    path.write_text(text.replace(old, new))


service = Path("app/src/main/java/com/focusguard/service/BlockingAccessibilityService.kt")
replace_exact(
    service,
    """    // Website configuration remains persisted, but its runtime enforcement is
    // intentionally reset. No browser inspection/redirect pipeline is started.
    private fun websiteSurfaceInspectionNeeded(): Boolean = false

    private fun websiteObservationRequired(): Boolean = false
""",
    """    private fun websiteSurfaceInspectionNeeded(): Boolean =
        blockedWebsitesDomainSet.isNotEmpty() || limitedWebsiteDomains.isNotEmpty()

    private fun websiteObservationRequired(): Boolean =
        blockedWebsitesDomainSet.isNotEmpty() || hardLimitedWebsiteDomains.isNotEmpty()
""",
)
replace_exact(
    service,
    """        // Native apps are no longer treated as an enforcement extension of a
        // configured website while the website runtime is being rebuilt.
        val blockedWebsiteDomain: String? = null
        val limitedWebsiteDomain: String? = null
""",
    """        val blockedWebsiteDomain = blockedWebsiteAppDomains[packageName]
        val limitedWebsiteDomain = limitedWebsiteAppDomains[packageName]
""",
)

manager = Path("app/src/main/java/com/focusguard/manager/BlockingSessionManager.kt")
replace_exact(
    manager,
    """                // Publish website ownership before deriving associated native-app
                // packages. A PASSWORD visit grant for the same site must not hide
                // a TIME/limit rule from that derivation.
                // Keep website rules configured in Room/UI, but publish no runtime
                // website owner while the blocking implementation is reset.
                val strongerWebsiteRules = emptySet<String>()
                PasswordTargetAccessGrant.updateStrongerWebsiteRules(emptySet())
                val sitesToBlock = emptyList<String>()
                deviceOwnerManager.setPornographyCategoryActive(false)
""",
    """                // Publish website ownership before deriving associated native-app
                // packages. A PASSWORD visit grant for the same site must not hide
                // a TIME/limit rule from that derivation.
                val strongerWebsiteRules = WebsiteBlocker.normalizeRules(
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
)
replace_exact(
    manager,
    """                // Do not leave a Device Owner URLBlocklist active after resetting
                // the Accessibility website runtime. Global adult-DNS configuration is
                // intentionally owned by its separate setting and is not changed here.
                deviceOwnerManager.clearWebsiteRestrictions()
""",
    """                if (sitesToBlock.isEmpty() && !adultFilterEnabled) {
                    deviceOwnerManager.clearWebsiteRestrictions()
                } else {
                    deviceOwnerManager.enforceWebsiteRestrictions(sitesToBlock)
                }
""",
)
replace_exact(
    manager,
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
    """                val selfProtectionRequired = shouldArmSelfProtection(
                    hasEnforcingSessions = enforcingSessions.isNotEmpty(),
                    hasBlockedApps = appsToBlock.isNotEmpty(),
                    hasBlockedSites = sitesToBlock.isNotEmpty(),
                    adultFilterEnabled = adultFilterEnabled,
                    focusModeActive = focusModeSession != null
                ) || activeTimeCommitment
""",
)
replace_exact(
    manager,
    """                        blockedSites = emptyList(),
                        blockingActive = selfProtectionRequired,
                        strictPomodoro = strictPomodoro,
                        passwordSites = emptyList(),
                        strongerSites = emptySet()
""",
    """                        blockedSites = sitesToBlock,
                        blockingActive = selfProtectionRequired,
                        strictPomodoro = strictPomodoro,
                        passwordSites = passwordSessionSites,
                        strongerSites = strongerWebsiteRules
""",
)

# Guard against accidentally leaving the deliberate reset in the restored branch.
service_text = service.read_text()
manager_text = manager.read_text()
if "private fun websiteSurfaceInspectionNeeded(): Boolean = false" in service_text:
    raise SystemExit("website surface inspection is still reset")
if "val sitesToBlock = emptyList<String>()" in manager_text:
    raise SystemExit("website runtime publication is still reset")
