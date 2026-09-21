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
    "import com.focusguard.accessibility.website.redirection.WebsiteRedirectDestination\n",
    "import com.focusguard.accessibility.website.redirection.WebsiteRedirectDestination\n"
    "import com.focusguard.accessibility.website.redirection.WebsiteRedirectDestinationStore\n",
)

replace_exact(
    service,
    """                return
            }
        }

        // HARD or stale/unknown ownership stays fail-closed and uses the
        // existing opaque-curtain + safe-browser redirect pipeline.
""",
    """                return
            }
            if (resolution.nextStep == WebsiteProtectionHierarchyPolicy.NextStep.ALLOW) {
                stopWebsiteTracking()
                return
            }
        }

        // HARD ownership (or strict Pomodoro) uses the existing opaque-curtain +
        // safe-browser redirect pipeline. NONE/ALLOW has already returned above.
""",
)

replace_exact(
    service,
    """                        override fun ownsProtection(): Boolean = transitionOwnsCurtain(transition)

                        override suspend fun prepareSameTabRedirect(attemptNumber: Int): Boolean {
""",
    """                        override fun ownsProtection(): Boolean = transitionOwnsCurtain(transition)

                        override fun mayAttemptRedirect(): Boolean =
                            transitionOwnsCurtain(transition) &&
                                !WebsiteRedirectDestinationStore.currentConflictsWith(
                                    blockedWebsitesDomainSet
                                )

                        override suspend fun prepareSameTabRedirect(attemptNumber: Int): Boolean {
""",
)

text = service.read_text()
required = [
    "WebsiteProtectionHierarchyPolicy.NextStep.ALLOW",
    "WebsiteRedirectDestinationStore.currentConflictsWith",
    "private fun websiteSurfaceInspectionNeeded(): Boolean =\n        blockedWebsitesDomainSet.isNotEmpty() || limitedWebsiteDomains.isNotEmpty()",
]
for marker in required:
    if marker not in text:
        raise SystemExit(f"service integration marker missing: {marker}")
