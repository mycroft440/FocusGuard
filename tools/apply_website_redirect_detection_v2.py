from pathlib import Path

service_path = Path('app/src/main/java/com/focusguard/service/BlockingAccessibilityService.kt')
service = service_path.read_text()


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{label}: expected 1 occurrence, got {count}')
    return text.replace(old, new, 1)

# Keep an active same-tab transition bound to the original browser document while
# Chrome/Fenix expose transient suggestion/native windows. Only a real handoff
# (external fallback or confirmed close) may move inspection to a foreign window.
old = '''            if (activeBrowserTransition != null) {
                // Do not bind an external redirect to the first browser-owned window.
                // Chromium and Fenix can expose suggestion/native/transient windows before
                // the destination page. Only a positively identified Google WEB_CONTENT
                // inspection is allowed to rebind the transition later.
                if (isWindowOrTabTransitionEvent(event.eventType) && event.windowId >= 0) {
                    browserInspectionCoordinator.observeWindow(transitionPackage, event.windowId)
                }
                retireStaleWebsiteTransitions()
                if (websiteBlockTransitionGuard.isActive(transitionPackage)) {
                    websiteBlockTransitionGuard.observeBrowserEvent(
                        browserPackageName = transitionPackage,
                        windowId = event.windowId,
                        eventUptimeMillis = event.eventTime,
                        eventType = event.eventType
                    )
                    scheduleBrowserInspection(event, transitionPackage)
                }
                return
            }
'''
new = '''            if (activeBrowserTransition != null) {
                // Omnibox activation can expose browser-owned suggestion/native windows
                // with a different accessibility window id. Those are transient UI, not
                // a replacement for the blocked tab, so they must not invalidate the
                // same-tab transaction. A foreign window becomes eligible only after an
                // explicit external redirect or a confirmed close handoff.
                val transitionInspectionWindowId = resolveTransitionInspectionWindowId(
                    expectedWindowId = activeBrowserTransition.expectedWindowId,
                    eventWindowId = event.windowId,
                    externalRedirectRequested = activeBrowserTransition.externalRedirectRequested,
                    closeConfirmed = activeBrowserTransition.closeConfirmed
                )
                if (isWindowOrTabTransitionEvent(event.eventType) &&
                    event.windowId >= 0 && transitionInspectionWindowId == event.windowId
                ) {
                    browserInspectionCoordinator.observeWindow(transitionPackage, event.windowId)
                }
                retireStaleWebsiteTransitions()
                if (websiteBlockTransitionGuard.isActive(transitionPackage)) {
                    websiteBlockTransitionGuard.observeBrowserEvent(
                        browserPackageName = transitionPackage,
                        windowId = event.windowId,
                        eventUptimeMillis = event.eventTime,
                        eventType = event.eventType
                    )
                    if (transitionInspectionWindowId >= 0) {
                        scheduleBrowserInspection(
                            event,
                            transitionPackage,
                            transitionInspectionWindowId
                        )
                    }
                }
                return
            }
'''
service = replace_once(service, old, new, 'active transition routing')

# Invalid window ids must not erase a valid browser document. Reinspect the last
# valid browser window instead; valid window events keep the existing behavior.
old = '''            if ((event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                    event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) &&
                inspectionPackage.isNotBlank()
            ) {
                browserInspectionCoordinator.observeWindow(inspectionPackage, event.windowId)
                retireStaleWebsiteTransitions()
            }
'''
new = '''            val browserInspectionWindowId = if (
                browserInspectionEvent && inspectionPackage.isNotBlank()
            ) {
                resolveBrowserInspectionWindowId(
                    eventWindowId = event.windowId,
                    currentWindowId = browserInspectionCoordinator
                        .currentToken(inspectionPackage)?.windowId
                )
            } else {
                INVALID_BROWSER_WINDOW_ID
            }

            if ((event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                    event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) &&
                inspectionPackage.isNotBlank() && event.windowId >= 0
            ) {
                browserInspectionCoordinator.observeWindow(inspectionPackage, event.windowId)
                retireStaleWebsiteTransitions()
            }
'''
service = replace_once(service, old, new, 'generic browser window routing')

old = '''            if (browserInspectionEvent && inspectionPackage.isNotBlank() &&
                websiteSurfaceInspectionNeeded()
            ) {
                scheduleBrowserInspection(event, inspectionPackage)
            }
'''
new = '''            if (browserInspectionEvent && inspectionPackage.isNotBlank() &&
                websiteSurfaceInspectionNeeded() && browserInspectionWindowId >= 0
            ) {
                scheduleBrowserInspection(event, inspectionPackage, browserInspectionWindowId)
            }
'''
service = replace_once(service, old, new, 'async browser scheduling')

old = '''    private fun scheduleBrowserInspection(event: AccessibilityEvent, packageName: String) {
        if (packageName.isBlank() || event.windowId < 0) return
        val offer = browserInspectionCoordinator.offer(
            packageName = packageName,
            windowId = event.windowId,
'''
new = '''    private fun scheduleBrowserInspection(
        event: AccessibilityEvent,
        packageName: String,
        windowId: Int = event.windowId
    ) {
        if (packageName.isBlank() || windowId < 0) return
        val offer = browserInspectionCoordinator.offer(
            packageName = packageName,
            windowId = windowId,
'''
service = replace_once(service, old, new, 'scheduleBrowserInspection signature')

# Transition-owned actions may continue using the exact original browser window
# while a suggestion popup temporarily owns Android's active-window bit.
old = '''    private fun activeBrowserRoot(
        browserPackageName: String,
        expectedWindowId: Int
    ): AccessibilityNodeInfo? = browserRootForExpectedWindow(
        browserPackageName = browserPackageName,
        expectedWindowId = expectedWindowId,
        requireActive = true
    )

    private fun browserRootForExpectedWindow(
'''
new = '''    private fun activeBrowserRoot(
        browserPackageName: String,
        expectedWindowId: Int
    ): AccessibilityNodeInfo? = browserRootForExpectedWindow(
        browserPackageName = browserPackageName,
        expectedWindowId = expectedWindowId,
        requireActive = true
    )

    private fun transitionBrowserRoot(
        transition: WebsiteBlockTransitionHandle
    ): AccessibilityNodeInfo? {
        if (!transitionWindowIsCurrent(transition)) return null
        return browserRootForExpectedWindow(
            browserPackageName = transition.browserPackageName,
            expectedWindowId = transition.expectedWindowId,
            requireActive = false
        )
    }

    private fun browserRootForExpectedWindow(
'''
service = replace_once(service, old, new, 'transition browser root helper')

# Replace active-only root acquisition inside transition-specific functions.
def replace_in_region(text: str, start: str, end: str, old_value: str, new_value: str, min_count: int) -> str:
    start_i = text.index(start)
    end_i = text.index(end, start_i)
    region = text[start_i:end_i]
    count = region.count(old_value)
    if count < min_count:
        raise SystemExit(f'{start}: expected at least {min_count} replacements, got {count}')
    region = region.replace(old_value, new_value)
    return text[:start_i] + region + text[end_i:]

service = replace_in_region(
    service,
    '    private fun currentBrowserSurfaceMatchesBlockedTransition(',
    '    private fun activeBrowserRoot(',
    '''        val root = activeBrowserRoot(transition.browserPackageName, transition.expectedWindowId)
            ?: return false''',
    '''        val root = transitionBrowserRoot(transition) ?: return false''',
    1
)
service = replace_in_region(
    service,
    '    private suspend fun restoreBlockedSurfaceAfterAddressEdit(',
    '    private suspend fun restoreBlockedSurfaceForSafeIntentFallback(',
    '''        val editorRoot = activeBrowserRoot(
            transition.browserPackageName,
            transition.expectedWindowId
        ) ?: return false''',
    '''        val editorRoot = transitionBrowserRoot(transition) ?: return false''',
    1
)
service = replace_in_region(
    service,
    '    private suspend fun prepareSafeAddressBar(',
    '    private suspend fun submitSafeAddressBar(',
    'activeBrowserRoot(browserPackageName, expectedWindowId)',
    'transitionBrowserRoot(transition)',
    5
)
service = replace_in_region(
    service,
    '    private suspend fun submitSafeAddressBar(',
    '    private suspend fun confirmSafeGoogleFromFreshBrowserSurface(',
    'activeBrowserRoot(browserPackageName, expectedWindowId)',
    'transitionBrowserRoot(transition)',
    2
)
service = replace_in_region(
    service,
    '    private suspend fun confirmSafeGoogleFromFreshBrowserSurface(',
    '    private fun transitionWindowIsCurrent(',
    '''            val root = activeBrowserRoot(
                transition.browserPackageName,
                transition.expectedWindowId
            ) ?: return false''',
    '''            val root = transitionBrowserRoot(transition) ?: return false''',
    1
)

# Restore stabilization timings from the last reliable baseline. The shorter
# curtain remains, because it occurs after safe navigation and does not race the omnibox.
for old_value, new_value, label in [
    ('        private const val WEBSITE_ADDRESS_BAR_FOCUS_SETTLE_MILLIS = 32L\n',
     '        private const val WEBSITE_ADDRESS_BAR_FOCUS_SETTLE_MILLIS = 48L\n', 'focus settle'),
    ('        private const val WEBSITE_ADDRESS_BAR_ACTION_RETRY_MILLIS = 16L\n',
     '        private const val WEBSITE_ADDRESS_BAR_ACTION_RETRY_MILLIS = 32L\n', 'action retry'),
    ('        private const val WEBSITE_GOOGLE_SURFACE_SETTLE_MILLIS = 80L\n',
     '        private const val WEBSITE_GOOGLE_SURFACE_SETTLE_MILLIS = 120L\n', 'google settle'),
]:
    service = replace_once(service, old_value, new_value, label)

# Add pure routing policies for regression tests.
marker = '''        internal fun supportsCapabilityBasedIntentRedirectFallback(
            knownBrowser: Boolean,
            verifiedHttpsHandler: Boolean
        ): Boolean = knownBrowser || verifiedHttpsHandler
'''
addition = marker + '''
        internal fun resolveTransitionInspectionWindowId(
            expectedWindowId: Int,
            eventWindowId: Int,
            externalRedirectRequested: Boolean,
            closeConfirmed: Boolean
        ): Int = when {
            expectedWindowId < 0 -> INVALID_BROWSER_WINDOW_ID
            eventWindowId < 0 -> expectedWindowId
            eventWindowId == expectedWindowId -> expectedWindowId
            externalRedirectRequested || closeConfirmed -> eventWindowId
            else -> INVALID_BROWSER_WINDOW_ID
        }

        internal fun resolveBrowserInspectionWindowId(
            eventWindowId: Int,
            currentWindowId: Int?
        ): Int = when {
            eventWindowId >= 0 -> eventWindowId
            currentWindowId != null && currentWindowId >= 0 -> currentWindowId
            else -> INVALID_BROWSER_WINDOW_ID
        }
'''
service = replace_once(service, marker, addition, 'routing policy helpers')
service_path.write_text(service)

# Restore external fallback as the final safety net. Same-tab remains preferred.
plan_path = Path('app/src/main/java/com/focusguard/accessibility/website/redirection/WebsiteRedirectionPlan.kt')
plan = plan_path.read_text()
plan = replace_once(
    plan,
    '    const val ALLOW_EXTERNAL_BROWSER_INTENT_FALLBACK = false\n',
    '    const val ALLOW_EXTERNAL_BROWSER_INTENT_FALLBACK = true\n',
    'external fallback policy'
)
plan = plan.replace(
    'ACTION_VIEW hands navigation back to the browser and can create\n     * another tab while leaving the blocked tab alive, so it is deliberately not a\n     * valid fallback for a blocking transition.',
    'ACTION_VIEW may open another tab, so it remains a last-resort fallback only after\n     * the bounded same-tab rewrite attempts fail. The blocked tab is still protected\n     * if the user returns to it.'
)
plan = plan.replace(
    'After the bounded same-tab attempts are exhausted the\n     * transition stays fail-closed rather than launching a browser ACTION_VIEW,\n     * because an external navigation request cannot prove that the original blocked\n     * tab was replaced or otherwise neutralized.',
    'After the bounded same-tab attempts are exhausted the service may request the\n     * package-scoped safe-browser fallback. That request is never treated as proof\n     * that the original tab was neutralized; destination confirmation remains required.'
)
plan_path.write_text(plan)

plan_test_path = Path('app/src/test/java/com/focusguard/accessibility/website/redirection/WebsiteRedirectionPlanTest.kt')
plan_test = plan_test_path.read_text()
plan_test = replace_once(
    plan_test,
    '''    fun `external browser intent fallback is disabled to preserve the blocked tab identity`() {
        assertThat(WebsiteRedirectionPlan.ALLOW_EXTERNAL_BROWSER_INTENT_FALLBACK).isFalse()
    }
''',
    '''    fun `external browser intent fallback remains available after same tab attempts`() {
        assertThat(WebsiteRedirectionPlan.ALLOW_EXTERNAL_BROWSER_INTENT_FALLBACK).isTrue()
    }
''',
    'plan fallback test'
)
plan_test_path.write_text(plan_test)

nav_test_path = Path('app/src/test/java/com/focusguard/service/WebsiteBlockNavigationTest.kt')
nav_test = nav_test_path.read_text()
insert_marker = '''    private companion object {
        const val CHROME_PACKAGE = "com.android.chrome"
'''
new_tests = '''    @Test
    fun `transient omnibox window cannot replace same tab transition target`() {
        assertThat(
            BlockingAccessibilityService.resolveTransitionInspectionWindowId(
                expectedWindowId = 7,
                eventWindowId = 8,
                externalRedirectRequested = false,
                closeConfirmed = false
            )
        ).isEqualTo(-1)
        assertThat(
            BlockingAccessibilityService.resolveTransitionInspectionWindowId(
                expectedWindowId = 7,
                eventWindowId = -1,
                externalRedirectRequested = false,
                closeConfirmed = false
            )
        ).isEqualTo(7)
        assertThat(
            BlockingAccessibilityService.resolveTransitionInspectionWindowId(
                expectedWindowId = 7,
                eventWindowId = 8,
                externalRedirectRequested = true,
                closeConfirmed = false
            )
        ).isEqualTo(8)
    }

    @Test
    fun `invalid browser window event reuses the last valid inspected window`() {
        assertThat(
            BlockingAccessibilityService.resolveBrowserInspectionWindowId(
                eventWindowId = -1,
                currentWindowId = 7
            )
        ).isEqualTo(7)
        assertThat(
            BlockingAccessibilityService.resolveBrowserInspectionWindowId(
                eventWindowId = -1,
                currentWindowId = null
            )
        ).isEqualTo(-1)
        assertThat(
            BlockingAccessibilityService.resolveBrowserInspectionWindowId(
                eventWindowId = 8,
                currentWindowId = 7
            )
        ).isEqualTo(8)
    }

''' + insert_marker
nav_test = replace_once(nav_test, insert_marker, new_tests, 'navigation regression tests')
nav_test_path.write_text(nav_test)

print('Applied website redirect/detection v2 fix')
