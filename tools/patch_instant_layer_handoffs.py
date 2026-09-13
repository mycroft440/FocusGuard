from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one anchor, found {count}: {old[:100]!r}")
    p.write_text(text.replace(old, new, 1))


def replace_count(path: str, old: str, new: str, expected: int) -> None:
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != expected:
        raise SystemExit(
            f"{path}: expected {expected} anchors, found {count}: {old[:100]!r}"
        )
    p.write_text(text.replace(old, new))


blocker = "app/src/main/java/com/focusguard/utils/WebsiteBlocker.kt"
anchor = "    private fun findDirectMatchingRules(\n"
accounting = '''    /**
     * Matches configured rules without applying a temporary PASSWORD visit grant.
     *
     * Blocking decisions must use [findMatchingRules], which deliberately honors
     * an authenticated visit. Usage accounting and hierarchy ownership are
     * different: they must keep seeing the rule while PASSWORD is temporarily
     * released so the daily allowance can continue advancing underneath it.
     */
    fun findMatchingRulesIgnoringGrants(
        urlOrDomain: String,
        configuredRules: Collection<String>
    ): Set<String> {
        val normalizedRules = normalizeRules(configuredRules)
        if (normalizedRules.isEmpty()) return emptySet()
        return normalizedRules.filterTo(linkedSetOf()) { rule ->
            matchesRuleIgnoringGrants(urlOrDomain, rule)
        }
    }

'''
replace_once(blocker, anchor, accounting + anchor)

service = "app/src/main/java/com/focusguard/service/BlockingAccessibilityService.kt"
replace_once(
    service,
    "import com.focusguard.database.AppDatabase\n",
    "import com.focusguard.database.AppDatabase\nimport com.focusguard.database.BlockSession\n",
)
replace_once(
    service,
    "    @Volatile private var blockedWebsitesDomainSet: Set<String> = emptySet()\n",
    "    @Volatile private var blockedWebsitesDomainSet: Set<String> = emptySet()\n"
    "    @Volatile private var passwordWebsiteDomainSet: Set<String> = emptySet()\n"
    "    @Volatile private var strongerWebsiteDomainSet: Set<String> = emptySet()\n",
)
replace_once(
    service,
    "    private var appLimitMonitoringJob: Job? = null\n",
    "    private var appLimitMonitoringJob: Job? = null\n"
    "    private var hierarchyBoundaryJob: Job? = null\n"
    "    @Volatile private var hierarchyBoundaryAtMillis = Long.MIN_VALUE\n",
)

old_snapshot = '''        val sites = WebsiteBlocker.normalizeRules(
            intent.getStringArrayListExtra(EXTRA_BLOCKED_SITES_SNAPSHOT).orEmpty()
        )
        blockedAppsSet = apps
        blockedWebsitesDomainSet = sites
        blockedWebsiteAppDomains = WebsiteBlocker.appPackageDomainsFor(sites)
'''
new_snapshot = '''        val sites = WebsiteBlocker.normalizeRules(
            intent.getStringArrayListExtra(EXTRA_BLOCKED_SITES_SNAPSHOT).orEmpty()
        )
        val passwordSites = WebsiteBlocker.normalizeRules(
            intent.getStringArrayListExtra(EXTRA_PASSWORD_SITES_SNAPSHOT).orEmpty()
        )
        val strongerSites = WebsiteBlocker.normalizeRules(
            intent.getStringArrayListExtra(EXTRA_STRONGER_SITES_SNAPSHOT).orEmpty()
        )
        blockedAppsSet = apps
        blockedWebsitesDomainSet = sites
        passwordWebsiteDomainSet = passwordSites
        strongerWebsiteDomainSet = strongerSites
        blockedWebsiteAppDomains = WebsiteBlocker.appPackageDomainsFor(sites)
'''
replace_once(service, old_snapshot, new_snapshot)

replace_once(
    service,
    "        syncWarmOverlays()\n        lastLoadTime = System.currentTimeMillis()\n    }\n\n"
    "    private fun relinquishAccessibilityForDevelopment() {\n",
    "        syncWarmOverlays()\n"
    "        lastLoadTime = System.currentTimeMillis()\n"
    "        enforceCurrentForegroundFromSnapshot()\n"
    "    }\n\n"
    '''    private fun enforceCurrentForegroundFromSnapshot() {
        val currentPackage = foregroundPackageName?.takeIf(String::isNotBlank) ?: return
        if (currentPackage == packageName ||
            currentPackage == defaultLauncherPackage ||
            currentPackage in focusModeAllowedAppsSet
        ) return

        if (currentPackage in browserPackages && blockedWebsitesDomainSet.isNotEmpty()) {
            val root = rootInActiveWindow ?: return
            val windowId = root.windowId
            val rootPackage = runCatching {
                root.packageName?.toString().orEmpty()
            }.getOrDefault("")
            val candidate = try {
                if (rootPackage != currentPackage) {
                    null
                } else {
                    WebsiteBlocker.extractUrlFromRoot(
                        root,
                        currentPackage,
                        isVerifiedHttpsHandler(currentPackage)
                    ) ?: WebsiteBlocker.extractAddressBarTextFromRoot(
                        root,
                        currentPackage,
                        isVerifiedHttpsHandler(currentPackage)
                    )
                }
            } finally {
                recycleSafely(root)
            }
            val blockedCandidate = candidate?.takeIf(String::isNotBlank) ?: return
            if (WebsiteBlocker.findMatchingRule(
                    blockedCandidate,
                    blockedWebsitesDomainSet
                ) == null
            ) return
            routeWebsiteBlockByHierarchy(
                browserPackageName = currentPackage,
                browserWindowId = windowId,
                blockedCandidate = blockedCandidate,
                detectionEventUptimeMillis = SystemClock.uptimeMillis()
            )
            return
        }

        if (currentPackage !in blockedAppsSet ||
            PasswordTargetAccessGrant.isPackageGranted(currentPackage)
        ) return
        val root = rootInActiveWindow ?: return
        val targetStillForeground = try {
            root.packageName?.toString() == currentPackage
        } finally {
            recycleSafely(root)
        }
        if (targetStillForeground) blockApp(currentPackage)
    }

    private fun relinquishAccessibilityForDevelopment() {
''',
)
replace_once(
    service,
    "            blockedWebsitesDomainSet = emptySet()\n"
    "            blockedWebsiteAppDomains = emptyMap()\n",
    "            blockedWebsitesDomainSet = emptySet()\n"
    "            passwordWebsiteDomainSet = emptySet()\n"
    "            strongerWebsiteDomainSet = emptySet()\n"
    "            blockedWebsiteAppDomains = emptyMap()\n",
)

schedule_anchor = "    private fun refreshData() {\n"
schedule_code = '''    private fun scheduleHierarchyBoundaryRefresh(
        sessions: Collection<BlockSession>,
        nowMillis: Long = System.currentTimeMillis()
    ) {
        val nextBoundary = HierarchyBoundaryPolicy.nextBoundary(sessions, nowMillis)
        if (nextBoundary == null) {
            hierarchyBoundaryAtMillis = Long.MIN_VALUE
            hierarchyBoundaryJob?.cancel()
            hierarchyBoundaryJob = null
            return
        }
        if (hierarchyBoundaryAtMillis == nextBoundary &&
            hierarchyBoundaryJob?.isActive == true
        ) return

        hierarchyBoundaryJob?.cancel()
        hierarchyBoundaryAtMillis = nextBoundary
        hierarchyBoundaryJob = scope.launch {
            delay(
                HierarchyBoundaryPolicy.delayMillis(
                    boundaryMillis = nextBoundary,
                    nowMillis = System.currentTimeMillis()
                )
            )
            if (hierarchyBoundaryAtMillis != nextBoundary) return@launch
            hierarchyBoundaryAtMillis = Long.MIN_VALUE
            hierarchyBoundaryJob = null

            // AlarmManager remains the process-death/doze fallback. While the
            // accessibility service is alive, this handoff runs at the actual
            // TIME start/end boundary instead of the platform's inexact-alarm window.
            sessionManager.checkAndEnforce()
            refreshData()
        }
    }

'''
replace_once(service, schedule_anchor, schedule_code + schedule_anchor)
replace_once(
    service,
    "                        val activeSessions = database.blockSessionDao().getAllActiveSessionsStatic()\n"
    "                        val enforcingSessions = activeSessions.filter {\n",
    "                        val activeSessions = database.blockSessionDao().getAllActiveSessionsStatic()\n"
    "                        scheduleHierarchyBoundaryRefresh(activeSessions)\n"
    "                        val enforcingSessions = activeSessions.filter {\n",
)
replace_once(
    service,
    "                        val enforcingIds = enforcingSessions.map { it.id }\n\n"
    "                        val sessionApps = getAppsForSessions(enforcingIds).toSet()\n",
    "                        val enforcingIds = enforcingSessions.map { it.id }\n"
    "                        val passwordSessionIds = enforcingSessions\n"
    "                            .filter { it.sessionType.equals(\"PASSWORD\", ignoreCase = true) }\n"
    "                            .map { it.id }\n"
    "                        val strongerSessionIds = enforcingSessions\n"
    "                            .filter { !it.sessionType.equals(\"PASSWORD\", ignoreCase = true) }\n"
    "                            .map { it.id }\n\n"
    "                        val sessionApps = getAppsForSessions(enforcingIds).toSet()\n",
)
replace_once(
    service,
    "                        val sessionSites = WebsiteBlocker.normalizeRules(\n"
    "                            getSitesForSessions(enforcingIds)\n"
    "                        )\n\n"
    "                        val activeAppLimits = database.appUsageLimitDao()\n",
    "                        val sessionSites = WebsiteBlocker.normalizeRules(\n"
    "                            getSitesForSessions(enforcingIds)\n"
    "                        )\n"
    "                        val passwordSessionSites = WebsiteBlocker.normalizeRules(\n"
    "                            getSitesForSessions(passwordSessionIds)\n"
    "                        )\n"
    "                        val strongerSessionSites = WebsiteBlocker.normalizeRules(\n"
    "                            getSitesForSessions(strongerSessionIds)\n"
    "                        )\n\n"
    "                        val activeAppLimits = database.appUsageLimitDao()\n",
)
replace_once(
    service,
    "                        val blockedWebsiteDomains = WebsiteBlocker.normalizeRules(\n"
    "                            sessionSites + exceededWebsiteDomains + adultRules\n"
    "                        )\n",
    "                        val strongerWebsiteDomains = WebsiteBlocker.normalizeRules(\n"
    "                            strongerSessionSites + exceededWebsiteDomains + adultRules\n"
    "                        )\n"
    "                        val blockedWebsiteDomains = WebsiteBlocker.normalizeRules(\n"
    "                            sessionSites + exceededWebsiteDomains + adultRules\n"
    "                        )\n",
)
replace_once(
    service,
    "                            blockedWebsitesDomainSet = blockedWebsiteDomains\n"
    "                            blockedWebsiteAppDomains = blockedWebsiteApps\n",
    "                            blockedWebsitesDomainSet = blockedWebsiteDomains\n"
    "                            passwordWebsiteDomainSet = passwordSessionSites\n"
    "                            strongerWebsiteDomainSet = strongerWebsiteDomains\n"
    "                            blockedWebsiteAppDomains = blockedWebsiteApps\n",
)

replace_once(
    service,
    '''        val matchingRules = WebsiteBlocker.findMatchingRules(
            urlOrDomain,
            limitedWebsiteDomains
        )
''',
    '''        val matchingRules = WebsiteBlocker.findMatchingRulesIgnoringGrants(
            urlOrDomain,
            limitedWebsiteDomains
        )
''',
)
replace_once(
    service,
    '''            val matchingRules = WebsiteBlocker.findMatchingRules(
                usage.domain,
                WebsiteBlocker.normalizeRules(limits.map { it.domain })
            )
''',
    '''            val matchingRules = WebsiteBlocker.findMatchingRulesIgnoringGrants(
                usage.domain,
                WebsiteBlocker.normalizeRules(limits.map { it.domain })
            )
''',
)

block_call = '''        blockWebsite(
            browserPackageName = packageName,
            browserWindowId = event.windowId,
            blockedCandidate = url ?: addressText ?: blockedCandidate,
            detectionEventUptimeMillis = event.eventTime
        )
'''
route_call = '''        routeWebsiteBlockByHierarchy(
            browserPackageName = packageName,
            browserWindowId = event.windowId,
            blockedCandidate = url ?: addressText ?: blockedCandidate,
            detectionEventUptimeMillis = event.eventTime
        )
'''
replace_count(service, block_call, route_call, 2)

block_anchor = '''    private fun blockWebsite(
        browserPackageName: String,
'''
route_code = '''    private fun routeWebsiteBlockByHierarchy(
        browserPackageName: String,
        browserWindowId: Int,
        blockedCandidate: String,
        detectionEventUptimeMillis: Long
    ) {
        if (!isPomodoroStrictActive) {
            val resolution = WebsiteProtectionHierarchyPolicy.resolve(
                candidate = blockedCandidate,
                passwordRules = passwordWebsiteDomainSet,
                strongerRules = strongerWebsiteDomainSet
            )
            if (resolution.owner == WebsiteProtectionHierarchyPolicy.Owner.PASSWORD) {
                stopWebsiteTracking()
                launchBlockNotice(
                    blockedPackage = null,
                    blockedDomain = WebsiteBlocker.displayRule(
                        resolution.matchedRule ?: blockedCandidate
                    ),
                    redirectBrowserPackage = browserPackageName,
                    eventUptimeMillis = detectionEventUptimeMillis
                )
                return
            }
        }

        // HARD or stale/unknown ownership stays fail-closed and uses the
        // existing opaque-curtain + safe-browser redirect pipeline.
        blockWebsite(
            browserPackageName = browserPackageName,
            browserWindowId = browserWindowId,
            blockedCandidate = blockedCandidate,
            detectionEventUptimeMillis = detectionEventUptimeMillis
        )
    }

'''
replace_once(service, block_anchor, route_code + block_anchor)

replace_once(
    service,
    '        internal const val EXTRA_BLOCKED_SITES_SNAPSHOT = "BLOCKED_SITES_SNAPSHOT"\n',
    '        internal const val EXTRA_BLOCKED_SITES_SNAPSHOT = "BLOCKED_SITES_SNAPSHOT"\n'
    '        internal const val EXTRA_PASSWORD_SITES_SNAPSHOT = "PASSWORD_SITES_SNAPSHOT"\n'
    '        internal const val EXTRA_STRONGER_SITES_SNAPSHOT = "STRONGER_SITES_SNAPSHOT"\n',
)
replace_once(
    service,
    '''            blockedSites: Collection<String>,
            blockingActive: Boolean,
            strictPomodoro: Boolean
        ): Intent {
            val normalizedApps = blockedApps.filter(String::isNotBlank).distinct()
            val normalizedSites = WebsiteBlocker.normalizeRules(blockedSites)
''',
    '''            blockedSites: Collection<String>,
            blockingActive: Boolean,
            strictPomodoro: Boolean,
            passwordSites: Collection<String> = emptyList(),
            strongerSites: Collection<String> = emptyList()
        ): Intent {
            val normalizedApps = blockedApps.filter(String::isNotBlank).distinct()
            val normalizedSites = WebsiteBlocker.normalizeRules(blockedSites)
            val normalizedPasswordSites = WebsiteBlocker.normalizeRules(passwordSites)
            val normalizedStrongerSites = WebsiteBlocker.normalizeRules(strongerSites)
''',
)
replace_once(
    service,
    '''                putStringArrayListExtra(
                    EXTRA_BLOCKED_SITES_SNAPSHOT,
                    ArrayList(normalizedSites)
                )
                putExtra(EXTRA_BLOCKING_ACTIVE_SNAPSHOT, blockingActive)
''',
    '''                putStringArrayListExtra(
                    EXTRA_BLOCKED_SITES_SNAPSHOT,
                    ArrayList(normalizedSites)
                )
                putStringArrayListExtra(
                    EXTRA_PASSWORD_SITES_SNAPSHOT,
                    ArrayList(normalizedPasswordSites)
                )
                putStringArrayListExtra(
                    EXTRA_STRONGER_SITES_SNAPSHOT,
                    ArrayList(normalizedStrongerSites)
                )
                putExtra(EXTRA_BLOCKING_ACTIVE_SNAPSHOT, blockingActive)
''',
)

manager = "app/src/main/java/com/focusguard/manager/BlockingSessionManager.kt"
replace_once(
    manager,
    "                val sessionSites = getSitesForSessions(enforcingIds)\n"
    "                val passwordSessionApps = getAppsForSessions(passwordSessionIds)\n",
    "                val sessionSites = getSitesForSessions(enforcingIds)\n"
    "                val passwordSessionSites = getSitesForSessions(passwordSessionIds)\n"
    "                val passwordSessionApps = getAppsForSessions(passwordSessionIds)\n",
)
replace_once(
    manager,
    '''                        blockedSites = sitesToBlock,
                        blockingActive = selfProtectionRequired,
                        strictPomodoro = strictPomodoro
                    )
''',
    '''                        blockedSites = sitesToBlock,
                        blockingActive = selfProtectionRequired,
                        strictPomodoro = strictPomodoro,
                        passwordSites = passwordSessionSites,
                        strongerSites = strongerWebsiteRules
                    )
''',
)

Path("app/src/main/java/com/focusguard/service/HierarchyBoundaryPolicy.kt").write_text(
    '''package com.focusguard.service

import com.focusguard.database.BlockSession
import com.focusguard.receiver.BlockingScheduleCalculator
import java.util.TimeZone

/** Pure timing policy for live protection handoffs while Accessibility is alive. */
internal object HierarchyBoundaryPolicy {
    fun nextBoundary(
        sessions: Collection<BlockSession>,
        nowMillis: Long,
        timeZone: TimeZone = TimeZone.getDefault()
    ): Long? = BlockingScheduleCalculator.nextBoundary(
        sessions = sessions,
        additionalBoundaries = emptyList(),
        nowMillis = nowMillis,
        timeZone = timeZone
    )

    fun delayMillis(boundaryMillis: Long, nowMillis: Long): Long =
        (boundaryMillis - nowMillis).coerceAtLeast(1L)
}
'''
)

Path("app/src/main/java/com/focusguard/service/WebsiteProtectionHierarchyPolicy.kt").write_text(
    '''package com.focusguard.service

import com.focusguard.utils.WebsiteBlocker

/** Resolves only the PASSWORD-vs-stronger ownership of a website attempt. */
internal object WebsiteProtectionHierarchyPolicy {
    enum class Owner { HARD, PASSWORD, NONE }

    data class Resolution(
        val owner: Owner,
        val matchedRule: String? = null
    )

    fun resolve(
        candidate: String,
        passwordRules: Collection<String>,
        strongerRules: Collection<String>
    ): Resolution {
        WebsiteBlocker.findMatchingRulesIgnoringGrants(
            candidate,
            strongerRules
        ).firstOrNull()?.let { return Resolution(Owner.HARD, it) }

        WebsiteBlocker.findMatchingRulesIgnoringGrants(
            candidate,
            passwordRules
        ).firstOrNull()?.let { return Resolution(Owner.PASSWORD, it) }

        return Resolution(Owner.NONE)
    }
}
'''
)

Path("app/src/test/java/com/focusguard/service/HierarchyBoundaryPolicyTest.kt").write_text(
    '''package com.focusguard.service

import com.focusguard.database.BlockSession
import com.google.common.truth.Truth.assertThat
import java.util.Calendar
import java.util.TimeZone
import org.junit.Test

class HierarchyBoundaryPolicyTest {
    private val utc = TimeZone.getTimeZone("UTC")

    @Test
    fun `fixed TIME end is the exact live handoff boundary`() {
        val now = instant(2026, 9, 13, 10, 0)
        val end = instant(2026, 9, 13, 10, 5)

        val next = HierarchyBoundaryPolicy.nextBoundary(
            sessions = listOf(BlockSession(endTime = end, isFixed24h = true)),
            nowMillis = now,
            timeZone = utc
        )

        assertThat(next).isEqualTo(end)
        assertThat(HierarchyBoundaryPolicy.delayMillis(end, now)).isEqualTo(300_000L)
    }

    @Test
    fun `recurring TIME window hands back to PASSWORD at its exact end`() {
        val now = instant(2026, 9, 14, 9, 30) // Monday
        val end = instant(2026, 9, 14, 10, 0)
        val session = BlockSession(
            isFixed24h = false,
            isRecurring = true,
            recurringStartHour = 9,
            recurringEndHour = 10,
            recurringDaysOfWeek = Calendar.MONDAY.toString()
        )

        assertThat(
            HierarchyBoundaryPolicy.nextBoundary(
                sessions = listOf(session),
                nowMillis = now,
                timeZone = utc
            )
        ).isEqualTo(end)
    }

    @Test
    fun `late callback reconciles immediately instead of waiting another interval`() {
        assertThat(
            HierarchyBoundaryPolicy.delayMillis(
                boundaryMillis = 1_000L,
                nowMillis = 1_050L
            )
        ).isEqualTo(1L)
    }

    private fun instant(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int
    ): Long = Calendar.getInstance(utc).apply {
        clear()
        set(year, month - 1, day, hour, minute)
    }.timeInMillis
}
'''
)

Path("app/src/test/java/com/focusguard/service/WebsiteProtectionHierarchyPolicyTest.kt").write_text(
    '''package com.focusguard.service

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WebsiteProtectionHierarchyPolicyTest {
    @Test
    fun `strong website layer always outranks PASSWORD`() {
        val result = WebsiteProtectionHierarchyPolicy.resolve(
            candidate = "https://www.youtube.com/watch?v=1",
            passwordRules = setOf("youtube.com"),
            strongerRules = setOf("youtube.com")
        )

        assertThat(result.owner).isEqualTo(WebsiteProtectionHierarchyPolicy.Owner.HARD)
    }

    @Test
    fun `PASSWORD owns site while no stronger layer is active`() {
        val result = WebsiteProtectionHierarchyPolicy.resolve(
            candidate = "https://m.youtube.com/shorts/1",
            passwordRules = setOf("youtube.com"),
            strongerRules = emptySet()
        )

        assertThat(result.owner).isEqualTo(WebsiteProtectionHierarchyPolicy.Owner.PASSWORD)
        assertThat(result.matchedRule).isEqualTo("youtube.com")
    }

    @Test
    fun `unrelated rule has no hierarchy owner`() {
        val result = WebsiteProtectionHierarchyPolicy.resolve(
            candidate = "https://example.com",
            passwordRules = setOf("youtube.com"),
            strongerRules = setOf("reddit.com")
        )

        assertThat(result.owner).isEqualTo(WebsiteProtectionHierarchyPolicy.Owner.NONE)
    }
}
'''
)

Path(
    "app/src/test/java/com/focusguard/utils/WebsiteBlockerGrantIndependentMatchingTest.kt"
).write_text(
    '''package com.focusguard.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WebsiteBlockerGrantIndependentMatchingTest {
    @Test
    fun `accounting matcher still sees configured rule independently of visit grants`() {
        assertThat(
            WebsiteBlocker.findMatchingRulesIgnoringGrants(
                "https://m.youtube.com/watch?v=abc",
                setOf("youtube.com")
            )
        ).containsExactly("youtube.com")
    }

    @Test
    fun `accounting matcher preserves parent domain coverage`() {
        assertThat(
            WebsiteBlocker.findMatchingRulesIgnoringGrants(
                "https://news.example.com/article",
                setOf("example.com")
            )
        ).containsExactly("example.com")
    }
}
'''
)
