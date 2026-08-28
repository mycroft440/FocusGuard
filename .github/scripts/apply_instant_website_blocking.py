from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected 1 match, found {count}")
    return text.replace(old, new, 1)


# -----------------------------------------------------------------------------
# BlockingAccessibilityService: website blocking gets the same priority as app
# blocking. A browser address-bar event can cover the screen before any window
# resolution/root traversal/usage accounting runs.
# -----------------------------------------------------------------------------
p = Path("app/src/main/java/com/focusguard/service/BlockingAccessibilityService.kt")
s = p.read_text()

old = '''            if (eligibleForInterception &&
                directPackage in interceptionPackages &&
                handleSettingsInterception(
                    event,
                    directPackage,
                    eventDetectedAtNanos,
                    eventDeliveredAtUptimeMillis
                )
            ) {
                return
            }

            val packageName = resolveEventPackageName(event)
'''
new = '''            if (eligibleForInterception &&
                directPackage in interceptionPackages &&
                handleSettingsInterception(
                    event,
                    directPackage,
                    eventDetectedAtNanos,
                    eventDeliveredAtUptimeMillis
                )
            ) {
                return
            }

            // Website fast path: mirror the launcher/app fast path above. For a
            // known browser, an address-bar event already contains enough evidence
            // to decide a configured block. Do that BEFORE resolving windows or
            // touching rootInActiveWindow, because those binder/tree reads are the
            // largest avoidable delay between the browser event and our warm
            // accessibility curtain becoming opaque and touch-consuming.
            if (event.eventType in immediateBrowserBlockEventTypes &&
                directPackage in browserPackages &&
                handleImmediateBrowserBlock(event, directPackage)
            ) {
                return
            }

            val packageName = resolveEventPackageName(event)
'''
s = replace_once(s, old, new, "browser fast path placement")

marker = '''    private fun handleBrowserEvent(
        event: AccessibilityEvent,
        resolvedPackageName: String
    ) {
'''
method = '''    private fun handleImmediateBrowserBlock(
        event: AccessibilityEvent,
        packageName: String
    ): Boolean {
        if (blockedWebsitesDomainSet.isEmpty()) return false

        val addressText = WebsiteBlocker.extractAddressBarTextFromEvent(event, packageName)
        val url = addressText?.let(WebsiteBlocker::extractUrlCandidate)
            ?: WebsiteBlocker.extractUrlFromEvent(event, packageName)
        val blockTarget = immediateWebsiteBlockTarget(
            addressText = addressText,
            url = url,
            blockedRules = blockedWebsitesDomainSet
        ) ?: return false

        blockWebsite(
            domain = blockTarget,
            browserPackageName = packageName,
            eventUptimeMillis = event.eventTime
        )
        return true
    }

''' + marker
s = replace_once(s, marker, method, "immediate browser handler")

old = '''        val addressBarObservable = fastAddressText != null ||
            url != null || WebsiteBlocker.hasAddressBarNode(root, packageName)
        if (handleBrowserObservability(packageName, addressBarObservable)) {
            recycleSafely(root)
            return
        }
        val now = System.currentTimeMillis()

        if (pornographyCategoryActive) {
            val addressBarHasBlockedSearch = addressText?.let(
                WebsiteBlocker::isPornographySearchInput
            ) == true
            val googlePageFieldHasBlockedSearch = fastAddressText == null &&
                url?.let(WebsiteBlocker::isGoogleUrl) == true &&
                WebsiteBlocker.extractEditableTextFromEvent(event)?.let(
                    WebsiteBlocker::containsPornographySearchTerm
                ) == true
            if (addressBarHasBlockedSearch || googlePageFieldHasBlockedSearch) {
                blockWebsite(
                    PredefinedWebsites.PORNOGRAPHY_RULE,
                    packageName
                )
                recycleSafely(root)
                return
            }
        }

        if (!url.isNullOrBlank()) {
            val domain = WebsiteBlocker.extractDomain(url)
            updateWebsiteTracking(url, packageName, now)
            val matchingRule = WebsiteBlocker.findMatchingRule(
                url,
                blockedWebsitesDomainSet
            )
            if (matchingRule != null) {
                val blockTarget = if (WebsiteBlocker.isPornographyRule(matchingRule)) {
                    matchingRule
                } else {
                    domain
                }
                blockWebsite(blockTarget, packageName)
                recycleSafely(root)
                return
            }
        } else if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
'''
new = '''        val addressBarObservable = fastAddressText != null ||
            url != null || WebsiteBlocker.hasAddressBarNode(root, packageName)

        // Even on the root-fallback path, the block decision outranks tracking,
        // policy refreshes and observability bookkeeping. Once the URL is known,
        // cover the page immediately just like blockApp() covers an app window.
        val immediateTarget = immediateWebsiteBlockTarget(
            addressText = addressText,
            url = url,
            blockedRules = blockedWebsitesDomainSet
        )
        if (immediateTarget != null) {
            blockWebsite(
                domain = immediateTarget,
                browserPackageName = packageName,
                eventUptimeMillis = event.eventTime
            )
            recycleSafely(root)
            return
        }

        if (handleBrowserObservability(packageName, addressBarObservable)) {
            recycleSafely(root)
            return
        }
        val now = System.currentTimeMillis()

        if (pornographyCategoryActive) {
            // Some Google result pages expose the search field event without the
            // omnibox in that same event. Keep this secondary detector after the
            // address-bar fast path so it adds coverage without delaying it.
            val googlePageFieldHasBlockedSearch = fastAddressText == null &&
                url?.let(WebsiteBlocker::isGoogleUrl) == true &&
                WebsiteBlocker.extractEditableTextFromEvent(event)?.let(
                    WebsiteBlocker::containsPornographySearchTerm
                ) == true
            if (googlePageFieldHasBlockedSearch) {
                blockWebsite(
                    domain = PredefinedWebsites.PORNOGRAPHY_RULE,
                    browserPackageName = packageName,
                    eventUptimeMillis = event.eventTime
                )
                recycleSafely(root)
                return
            }
        }

        if (!url.isNullOrBlank()) {
            updateWebsiteTracking(url, packageName, now)
        } else if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
'''
s = replace_once(s, old, new, "root browser blocking priority")

old = '''    private fun blockWebsite(domain: String, browserPackageName: String) {
        if (!beginWebsiteBlock(domain, browserPackageName)) return
        val noticeLaunched = launchBlockNotice(
            blockedPackage = null,
            blockedDomain = WebsiteBlocker.displayRule(domain),
            redirectBrowserPackage = browserPackageName
        )
'''
new = '''    private fun blockWebsite(
        domain: String,
        browserPackageName: String,
        eventUptimeMillis: Long = SystemClock.uptimeMillis()
    ) {
        if (!beginWebsiteBlock(domain, browserPackageName)) return
        val noticeLaunched = launchBlockNotice(
            blockedPackage = null,
            blockedDomain = WebsiteBlocker.displayRule(domain),
            redirectBrowserPackage = browserPackageName,
            eventUptimeMillis = eventUptimeMillis
        )
'''
s = replace_once(s, old, new, "website event timestamp")

old = '''        private val settingsInterceptionEventTypes = setOf(
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_CLICKED
        )
        internal const val WEBSITE_BLOCK_NOTICE_DURATION_MILLIS = 1_000L
'''
new = '''        private val settingsInterceptionEventTypes = setOf(
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_CLICKED
        )
        // Events whose source can expose an address bar before a root/window walk.
        // Zero notification timeout means Android delivers them without batching.
        private val immediateBrowserBlockEventTypes = setOf(
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED
        )
        internal const val WEBSITE_BLOCK_NOTICE_DURATION_MILLIS = 1_000L
'''
s = replace_once(s, old, new, "immediate browser event types")

marker = '''        internal fun settingsInterceptionEventTypesForTest(): Set<Int> =
        settingsInterceptionEventTypes

        internal fun settingsTransitionGuardMillisForTest(): Long =
'''
addition = '''        internal fun settingsInterceptionEventTypesForTest(): Set<Int> =
        settingsInterceptionEventTypes

        internal fun immediateBrowserBlockEventTypesForTest(): Set<Int> =
            immediateBrowserBlockEventTypes

        internal fun immediateWebsiteBlockTarget(
            addressText: String?,
            url: String?,
            blockedRules: Collection<String>
        ): String? {
            val rules = WebsiteBlocker.normalizeRules(blockedRules)
            if (rules.isEmpty()) return null

            val pornographyActive = rules.any(WebsiteBlocker::isPornographyRule)
            if (pornographyActive &&
                !addressText.isNullOrBlank() &&
                WebsiteBlocker.isPornographySearchInput(addressText)
            ) {
                return PredefinedWebsites.PORNOGRAPHY_RULE
            }

            val candidate = url?.takeIf(String::isNotBlank)
                ?: addressText?.let(WebsiteBlocker::extractUrlCandidate)
                ?: return null
            val matchingRule = WebsiteBlocker.findMatchingRule(candidate, rules)
                ?: return null
            return if (WebsiteBlocker.isPornographyRule(matchingRule)) {
                matchingRule
            } else {
                WebsiteBlocker.extractDomain(candidate)
                    .ifBlank { WebsiteBlocker.displayRule(matchingRule) }
            }
        }

        internal fun settingsTransitionGuardMillisForTest(): Long =
'''
s = replace_once(s, marker, addition, "immediate target test API")

p.write_text(s)


# -----------------------------------------------------------------------------
# Fail closed much sooner when a hard website rule is active and a browser hides
# its address bar. Normal observable browsers use the zero-delay path above.
# -----------------------------------------------------------------------------
p = Path("app/src/main/java/com/focusguard/utils/WebsiteObservabilityPolicy.kt")
s = p.read_text()
s = replace_once(
    s,
    "    const val OPAQUE_BROWSER_GRACE_MILLIS = 800L\n",
    "    const val OPAQUE_BROWSER_GRACE_MILLIS = 200L\n",
    "opaque browser grace"
)
p.write_text(s)


# -----------------------------------------------------------------------------
# Unit tests: prove the immediate classifier and subscription path cannot drift.
# -----------------------------------------------------------------------------
p = Path("app/src/test/java/com/focusguard/service/WebsiteBlockNavigationTest.kt")
s = p.read_text()
s = replace_once(
    s,
    "import com.focusguard.ui.BlockNoticeActivity\n",
    "import com.focusguard.data.PredefinedWebsites\nimport com.focusguard.ui.BlockNoticeActivity\n",
    "test predefined websites import"
)
marker = '''    @Test
    fun `settings interception listens to the earliest window signals`() {
'''
tests = '''    @Test
    fun `website blocking listens to address bar events with no delivery debounce`() {
        val immediateTypes = BlockingAccessibilityService.immediateBrowserBlockEventTypesForTest()
        val requested = BlockingAccessibilityService.requestedAccessibilityEventTypes()

        assertThat(immediateTypes).containsAtLeast(
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED
        )
        immediateTypes.forEach { type ->
            assertThat(requested and type).isNotEqualTo(0)
        }
        assertThat(BlockingAccessibilityService.EVENT_NOTIFICATION_TIMEOUT_MILLIS).isEqualTo(0L)
    }

    @Test
    fun `blocked domain is classified immediately from address bar`() {
        assertThat(
            BlockingAccessibilityService.immediateWebsiteBlockTarget(
                addressText = "https://m.facebook.com/profile",
                url = "https://m.facebook.com/profile",
                blockedRules = listOf("facebook.com")
            )
        ).isEqualTo("m.facebook.com")
    }

    @Test
    fun `pornography search is classified before navigation finishes`() {
        assertThat(
            BlockingAccessibilityService.immediateWebsiteBlockTarget(
                addressText = "free porn videos",
                url = null,
                blockedRules = listOf(PredefinedWebsites.PORNOGRAPHY_RULE)
            )
        ).isEqualTo(PredefinedWebsites.PORNOGRAPHY_RULE)
    }

    @Test
    fun `safe address is not blocked by the immediate classifier`() {
        assertThat(
            BlockingAccessibilityService.immediateWebsiteBlockTarget(
                addressText = "https://example.com/news",
                url = "https://example.com/news",
                blockedRules = listOf("facebook.com", PredefinedWebsites.PORNOGRAPHY_RULE)
            )
        ).isNull()
    }

''' + marker
s = replace_once(s, marker, tests, "website fast path tests")
p.write_text(s)

print("Instant website blocking patch applied")
