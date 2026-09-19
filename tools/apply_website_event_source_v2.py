from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{label}: expected 1 occurrence, got {count}')
    return text.replace(old, new, 1)

service_path = Path('app/src/main/java/com/focusguard/service/BlockingAccessibilityService.kt')
service = service_path.read_text()

old = '''        if (event.eventType !in immediateBrowserBlockEventTypes ||
            packageName !in browserPackages ||
            event.windowId < 0 ||
            blockedWebsitesDomainSet.isEmpty() ||
            websiteBlockTransitionGuard.isActive(packageName)
        ) return false

        val addressText = WebsiteBlocker.extractAddressBarTextFromEvent(
'''
new = '''        if (event.eventType !in immediateBrowserBlockEventTypes ||
            packageName !in browserPackages ||
            blockedWebsitesDomainSet.isEmpty() ||
            websiteBlockTransitionGuard.isActive(packageName)
        ) return false

        val sourceWindowId = accessibilityEventSourceWindowId(event)
        val immediateWindowId = resolveImmediateBrowserWindowId(
            eventWindowId = event.windowId,
            sourceWindowId = sourceWindowId,
            currentWindowId = browserInspectionCoordinator.currentToken(packageName)?.windowId
        )
        if (immediateWindowId < 0) return false

        val addressText = WebsiteBlocker.extractAddressBarTextFromEvent(
'''
service = replace_once(service, old, new, 'immediate event window resolution')

service = replace_once(
    service,
    '''            windowId = event.windowId,
            eventType = event.eventType,
            eventUptimeMillis = event.eventTime,
''',
    '''            windowId = immediateWindowId,
            eventType = event.eventType,
            eventUptimeMillis = event.eventTime,
''',
    'immediate coordinator window'
)
service = replace_once(
    service,
    '''            browserPackageName = packageName,
            browserWindowId = event.windowId,
            blockedCandidate = candidate,
''',
    '''            browserPackageName = packageName,
            browserWindowId = immediateWindowId,
            blockedCandidate = candidate,
''',
    'immediate route window'
)

# Add a bounded source-window reader. AccessibilityEvent.source returns a fresh node
# handle, so recycle it immediately and never retain it across callbacks.
marker = '''    private fun handleImmediateBrowserAddressEvent(
        event: AccessibilityEvent,
        packageName: String
    ): Boolean {
'''
helper = '''    private fun accessibilityEventSourceWindowId(event: AccessibilityEvent): Int? {
        val source = runCatching { event.source }.getOrNull() ?: return null
        return try {
            source.windowId.takeIf { it >= 0 }
        } catch (_: RuntimeException) {
            null
        } finally {
            recycleSafely(source)
        }
    }

''' + marker
service = replace_once(service, marker, helper, 'event source window helper')

# Once both observations still match the same blocking rule, the current surface is
# still prohibited. Exact path/query equality is too strict because Chromium shortens
# or canonicalizes the visible URL between detection and omnibox activation.
old = '''            val candidateRule = WebsiteBlocker.findMatchingRule(candidate, rules) ?: return false
            val currentRule = WebsiteBlocker.findMatchingRule(current, rules) ?: return false
            if (candidateRule != currentRule) return false
            val candidateDomain = WebsiteBlocker.extractDomain(candidate)
            val currentDomain = WebsiteBlocker.extractDomain(current)
            if (candidateDomain.isNotBlank() && currentDomain.isNotBlank() &&
                candidateDomain != currentDomain
            ) return false
            return browserTargetIdentity(candidate) == browserTargetIdentity(current)
'''
new = '''            val candidateRule = WebsiteBlocker.findMatchingRule(candidate, rules) ?: return false
            val currentRule = WebsiteBlocker.findMatchingRule(current, rules) ?: return false
            // Enforcement is rule-scoped, not path-scoped. If the live browser surface
            // is still covered by the exact same blocked rule, rewriting it is correct
            // even when Chrome has shortened/canonicalized the visible path or the user
            // moved to another still-blocked page under that rule.
            return candidateRule == currentRule
'''
service = replace_once(service, old, new, 'blocked target identity')

marker = '''        internal fun resolveBrowserInspectionWindowId(
            eventWindowId: Int,
            currentWindowId: Int?
        ): Int = when {
            eventWindowId >= 0 -> eventWindowId
            currentWindowId != null && currentWindowId >= 0 -> currentWindowId
            else -> INVALID_BROWSER_WINDOW_ID
        }
'''
addition = marker + '''
        internal fun resolveImmediateBrowserWindowId(
            eventWindowId: Int,
            sourceWindowId: Int?,
            currentWindowId: Int?
        ): Int = when {
            sourceWindowId != null && sourceWindowId >= 0 -> sourceWindowId
            eventWindowId >= 0 -> eventWindowId
            currentWindowId != null && currentWindowId >= 0 -> currentWindowId
            else -> INVALID_BROWSER_WINDOW_ID
        }
'''
service = replace_once(service, marker, addition, 'immediate window policy')
service_path.write_text(service)

# Event-source URL readers must trust the source node's own window id rather than a
# sometimes-missing AccessibilityEvent.windowId.
blocker_path = Path('app/src/main/java/com/focusguard/utils/WebsiteBlocker.kt')
blocker = blocker_path.read_text()
old = '''            if (!isAddressBarNode(
                    source,
                    browserPackageName,
                    event.windowId,
                    httpsHandlerRecognized
                )
            ) return null
'''
new = '''            val sourceWindowId = source.windowId
            if (sourceWindowId < 0 || !isAddressBarNode(
                    source,
                    browserPackageName,
                    sourceWindowId,
                    httpsHandlerRecognized
                )
            ) return null
'''
count = blocker.count(old)
if count != 2:
    raise SystemExit(f'event source reader: expected 2 occurrences, got {count}')
blocker = blocker.replace(old, new)
blocker_path.write_text(blocker)

nav_test_path = Path('app/src/test/java/com/focusguard/service/WebsiteBlockNavigationTest.kt')
nav = nav_test_path.read_text()
nav = replace_once(
    nav,
    '    fun `obsolete detection in same window cannot touch a changed tab`() {\n',
    '    fun `same blocked rule remains current while stale or safe targets are rejected`() {\n',
    'target-current test name'
)
nav = replace_once(
    nav,
    '''        assertThat(
            BlockingAccessibilityService.detectedBrowserTargetStillCurrent(
                blockedCandidate = "https://m.facebook.com/profile",
                currentAddress = "https://m.facebook.com/other-tab",
                blockedRules = rules,
                detectionEventUptimeMillis = 100L,
                latestObservedEventUptimeMillis = 101L
            )
        ).isFalse()
''',
    '''        assertThat(
            BlockingAccessibilityService.detectedBrowserTargetStillCurrent(
                blockedCandidate = "https://m.facebook.com/profile",
                currentAddress = "https://m.facebook.com/other-tab",
                blockedRules = rules,
                detectionEventUptimeMillis = 100L,
                latestObservedEventUptimeMillis = 101L
            )
        ).isTrue()
''',
    'same blocked rule expectation'
)

insert_marker = '''    private companion object {
        const val CHROME_PACKAGE = "com.android.chrome"
'''
new_test = '''    @Test
    fun `immediate browser detection prefers source window and survives missing event window`() {
        assertThat(
            BlockingAccessibilityService.resolveImmediateBrowserWindowId(
                eventWindowId = -1,
                sourceWindowId = 7,
                currentWindowId = null
            )
        ).isEqualTo(7)
        assertThat(
            BlockingAccessibilityService.resolveImmediateBrowserWindowId(
                eventWindowId = -1,
                sourceWindowId = null,
                currentWindowId = 8
            )
        ).isEqualTo(8)
        assertThat(
            BlockingAccessibilityService.resolveImmediateBrowserWindowId(
                eventWindowId = 9,
                sourceWindowId = 7,
                currentWindowId = 8
            )
        ).isEqualTo(7)
    }

''' + insert_marker
nav = replace_once(nav, insert_marker, new_test, 'immediate detection regression test')
nav_test_path.write_text(nav)

print('Applied event-source and blocked-target reliability fix')
