from pathlib import Path

service_path = Path("app/src/main/java/com/focusguard/service/BlockingAccessibilityService.kt")
service = service_path.read_text()

old = """            // ACTION_VIEW can open Google in a fresh tab/window while the blocked
            // tab remains intact. A safe destination therefore cannot prove
            // neutralization after an external fallback unless the original tab
            // was independently confirmed closed first.
            if (transition.externalRedirectRequested && !transition.closeConfirmed) return false
"""
new = """            // The caller has already verified that this inspection is the safe Google
            // destination. For an external fallback the transition is rebound to the
            // newly observed browser window first; the old blocked tab remains protected
            // by the normal HardBlock detector if the user returns to it.
"""
assert service.count(old) == 1, "confirmGoogle external-close gate changed unexpectedly"
service = service.replace(old, new)

old = """            if (transition.externalRedirectRequested ||
                !transition.sanitizationRequested ||
                transition.expectedWindowId != windowId ||
"""
new = """            if (!transition.sanitizationRequested ||
                transition.expectedWindowId != windowId ||
"""
assert service.count(old) == 1, "stable-surface external gate changed unexpectedly"
service = service.replace(old, new)

old = """        if (!curtainReadyForTransition(transition) ||
            !transition.sanitizationRequested ||
            transition.externalRedirectRequested
        ) return false
"""
new = """        if (!curtainReadyForTransition(transition) ||
            !transition.sanitizationRequested
        ) return false
"""
assert service.count(old) == 1, "fresh-surface external gate changed unexpectedly"
service = service.replace(old, new)

old = """                // Same-tab rewrite is the only website redirect path. If the first
                // attempt raced an editor/focus animation, restore the exact blocked surface
                // and retry once with a fresh capability state. We still never close a tab,
                // launch a second browser document or evict the browser to HOME.
"""
new = """                // Same-tab rewrite remains the preferred website redirect path. If the
                // first attempt raced an editor/focus animation, restore the exact blocked
                // surface and retry once with a fresh capability state. Only after both
                // certified same-tab attempts fail may the guarded ACTION_VIEW fallback run.
"""
assert service.count(old) == 1, "same-tab retry comment changed unexpectedly"
service = service.replace(old, new)

old = """                // HardBlock must not treat ACTION_VIEW as neutralization. It can open
                // Google in another tab/window while the blocked tab remains reachable.
                // If both certified same-tab rewrites fail, keep the curtain and fall
                // through to fail-closed protection below instead of opening a second
                // browser surface that cannot satisfy the transition guarantee.

                if (!curtainReadyForTransition(transition)) return@launch
"""
new = """                // Some browser versions expose a readable address bar but reject or omit
                // the Accessibility actions required to submit a replacement URL. When both
                // certified same-tab attempts fail, use the browser's verified ACTION_VIEW
                // capability as the final redirect path. The curtain stays up until a fresh
                // exact Google root is observed and the transition is rebound to that window.
                // If the user later returns to the old blocked tab, HardBlock detects it again.
                if (!redirectRequested &&
                    supportsCapabilityBasedIntentRedirectFallback(
                        knownBrowser = browserPackageName in knownBrowserPackages,
                        verifiedHttpsHandler = isVerifiedHttpsHandler(browserPackageName)
                    )
                ) {
                    val blockedSurfaceRestored =
                        restoreBlockedSurfaceForSafeIntentFallback(transition)
                    if (blockedSurfaceRestored && curtainReadyForTransition(transition)) {
                        FocusGuardLogger.log(
                            "A11y",
                            "Usando fallback por intent para Google em $browserPackageName"
                        )
                        redirectRequested = requestSafeGoogleThroughBrowserIntent(transition)
                    }
                }

                if (!curtainReadyForTransition(transition)) return@launch
"""
assert service.count(old) == 1, "HardBlock fallback insertion point changed unexpectedly"
service = service.replace(old, new)
service_path.write_text(service)

test_path = Path("app/src/test/java/com/focusguard/service/ExternalRedirectNeutralizationTest.kt")
test = test_path.read_text()

test = test.replace(
    "fun `external Google window does not confirm while blocked tab remains unneutralized`() {",
    "fun `external Google window confirms after guarded rebind without close proof`() {",
)

old = """        assertThat(
            guard.confirmGoogle(
                browserPackageName = FIREFOX_PACKAGE,
                windowId = SAFE_WINDOW_ID,
                eventUptimeMillis = 130L
            )
        ).isFalse()
        assertThat(transition.safeGoogleConfirmed.isCompleted).isFalse()
"""
new = """        assertThat(
            guard.confirmGoogle(
                browserPackageName = FIREFOX_PACKAGE,
                windowId = SAFE_WINDOW_ID,
                eventUptimeMillis = 130L
            )
        ).isTrue()
        assertThat(transition.safeGoogleConfirmed.isCompleted).isTrue()
"""
assert test.count(old) == 1, "external redirect expectation changed unexpectedly"
test = test.replace(old, new)

test = test.replace(
    "fun `external Google window may confirm only after original tab close is independently confirmed`() {",
    "fun `external Google window also confirms when original tab close was recorded`() {",
)

insertion = r'''
    @Test
    fun `external Google window cannot confirm before transition is rebound`() {
        val guard = WebsiteBlockTransitionGuard()
        val transition = guard.tryStart(
            browserPackageName = FIREFOX_PACKAGE,
            transitionId = 4L,
            destination = WebsiteTransitionDestination.GOOGLE,
            expectedWindowId = BLOCKED_WINDOW_ID,
            inspectionGeneration = 1L,
            blockedCandidate = "https://blocked.example/path",
            blockedRules = setOf("blocked.example"),
            detectionEventUptimeMillis = 100L
        )!!

        assertThat(
            guard.markExternalRedirectRequested(
                browserPackageName = FIREFOX_PACKAGE,
                transitionId = transition.id,
                requestedAtUptimeMillis = 110L
            )
        ).isTrue()
        guard.observeBrowserEvent(
            browserPackageName = FIREFOX_PACKAGE,
            windowId = SAFE_WINDOW_ID,
            eventUptimeMillis = 120L,
            eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        )

        assertThat(
            guard.confirmGoogle(
                browserPackageName = FIREFOX_PACKAGE,
                windowId = SAFE_WINDOW_ID,
                eventUptimeMillis = 120L
            )
        ).isFalse()
        assertThat(transition.safeGoogleConfirmed.isCompleted).isFalse()
    }

    @Test
    fun `stable external Google surface confirms after guarded rebind`() {
        val guard = WebsiteBlockTransitionGuard()
        val transition = guard.tryStart(
            browserPackageName = FIREFOX_PACKAGE,
            transitionId = 5L,
            destination = WebsiteTransitionDestination.GOOGLE,
            expectedWindowId = BLOCKED_WINDOW_ID,
            inspectionGeneration = 1L,
            blockedCandidate = "https://blocked.example/path",
            blockedRules = setOf("blocked.example"),
            detectionEventUptimeMillis = 100L
        )!!

        assertThat(
            guard.markExternalRedirectRequested(
                browserPackageName = FIREFOX_PACKAGE,
                transitionId = transition.id,
                requestedAtUptimeMillis = 110L
            )
        ).isTrue()
        assertThat(
            guard.rebindExternalRedirectWindow(
                browserPackageName = FIREFOX_PACKAGE,
                transitionId = transition.id,
                windowId = SAFE_WINDOW_ID,
                inspectionGeneration = 2L,
                eventUptimeMillis = 120L
            )
        ).isTrue()
        assertThat(
            guard.confirmGoogleFromStableCurrentSurface(
                browserPackageName = FIREFOX_PACKAGE,
                windowId = SAFE_WINDOW_ID,
                observedAtUptimeMillis = 130L
            )
        ).isTrue()
        assertThat(transition.safeGoogleConfirmed.isCompleted).isTrue()
    }
'''
marker = "    private companion object {"
assert marker in test, "test insertion marker missing"
test = test.replace(marker, insertion + "\n" + marker, 1)
test_path.write_text(test)
