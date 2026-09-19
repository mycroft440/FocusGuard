from pathlib import Path

SERVICE = Path("app/src/main/java/com/focusguard/service/BlockingAccessibilityService.kt")
RACE_TEST = Path("app/src/test/java/com/focusguard/service/BrowserTransitionRaceTest.kt")
NAV_TEST = Path("app/src/test/java/com/focusguard/service/WebsiteBlockNavigationTest.kt")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


service = SERVICE.read_text()

service = replace_once(
    service,
'''            val transitionPackage = directPackage.ifBlank { foregroundPackageName.orEmpty() }
            val activeBrowserTransition = transitionPackage
                .takeIf(String::isNotBlank)
                ?.let(websiteBlockTransitionGuard::activeTransition)
            if (activeBrowserTransition != null) {
                val canRebindExternalWindow =
                    activeBrowserTransition.externalRedirectRequested &&
                        !activeBrowserTransition.externalRedirectWindowRebound &&
                        browserInspectionEvent &&
                        event.windowId >= 0 &&
                        event.windowId != activeBrowserTransition.expectedWindowId &&
                        event.eventTime >= activeBrowserTransition.externalRedirectRequestedAtUptimeMillis
                if (canRebindExternalWindow) {
                    val reboundGeneration = browserInspectionCoordinator.observeWindow(
                        transitionPackage,
                        event.windowId
                    )
                    websiteBlockTransitionGuard.rebindExternalRedirectWindow(
                        browserPackageName = transitionPackage,
                        transitionId = activeBrowserTransition.id,
                        windowId = event.windowId,
                        inspectionGeneration = reboundGeneration,
                        eventUptimeMillis = event.eventTime
                    )
                } else if (isWindowOrTabTransitionEvent(event.eventType)) {
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
''',
'''            val foregroundTransitionPackage = foregroundPackageName.orEmpty()
            val transitionPackage = when {
                directPackage.isNotBlank() && websiteBlockTransitionGuard.isActive(directPackage) ->
                    directPackage
                foregroundTransitionPackage.isNotBlank() &&
                    websiteBlockTransitionGuard.isActive(foregroundTransitionPackage) ->
                    foregroundTransitionPackage
                browserInspectionEvent -> resolveEventPackageName(event)
                    .takeIf(websiteBlockTransitionGuard::isActive)
                    .orEmpty()
                else -> ""
            }
            val activeBrowserTransition = transitionPackage
                .takeIf(String::isNotBlank)
                ?.let(websiteBlockTransitionGuard::activeTransition)
            if (activeBrowserTransition != null) {
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
''',
    "active transition event handoff",
)

service = replace_once(
    service,
'''        val transition = websiteBlockTransitionGuard.transitionForConfirmation(
            browserPackageName = token.packageName,
            windowId = token.windowId,
            eventUptimeMillis = outcome.eventUptimeMillis,
            eventType = outcome.eventType
        ) ?: return
        if (!transitionWindowIsCurrent(transition) || !isSafeGoogleRedirectSurface(outcome.bestCandidate) ||
            outcome.surface != BrowserSurfaceInspector.Surface.WEB_CONTENT ||
            outcome.focusedAddressEditor
        ) return
        scope.launch {
''',
'''        val transition = websiteBlockTransitionGuard.transitionForConfirmation(
            browserPackageName = token.packageName,
            windowId = token.windowId,
            eventUptimeMillis = outcome.eventUptimeMillis,
            eventType = outcome.eventType
        ) ?: return
        val stableGoogleCandidate =
            isSafeGoogleRedirectSurface(outcome.bestCandidate) &&
                outcome.surface == BrowserSurfaceInspector.Surface.WEB_CONTENT &&
                !outcome.focusedAddressEditor
        if (!stableGoogleCandidate) return

        // ACTION_VIEW may pass through several browser-owned Accessibility windows.
        // Rebind only after this exact inspected window has already proved that it is
        // the safe Google web surface; an arbitrary first post-intent window must never
        // consume the one allowed external handoff.
        if (transition.externalRedirectRequested &&
            token.windowId != transition.expectedWindowId
        ) {
            if (!websiteBlockTransitionGuard.rebindExternalRedirectWindow(
                    browserPackageName = token.packageName,
                    transitionId = transition.id,
                    windowId = token.windowId,
                    inspectionGeneration = token.generation,
                    eventUptimeMillis = outcome.eventUptimeMillis
                )
            ) return
        }
        if (!transitionWindowIsCurrent(transition)) return
        scope.launch {
''',
    "safe external rebind",
)

service = replace_once(
    service,
'''                awaitNextWebsiteRedirectFrame()
                if (!curtainReadyForTransition(transition)) return@launch

                val rewritePolicy = WebsiteTabNeutralizationPolicy(
                    browserPackageName = browserPackageName,
                    expectedWindowId = expectedWindowId
                )
                var redirectRequested = requestSafeGoogleInCurrentTab(
                    browserPackageName = browserPackageName,
                    expectedWindowId = expectedWindowId,
                    policy = rewritePolicy,
                    transition = transition
                )

                // Same-tab rewrite remains the preferred website redirect path. If the
                // first attempt raced an editor/focus animation, restore the exact blocked
                // surface and retry once with a fresh capability state. Only after both
                // certified same-tab attempts fail may the guarded ACTION_VIEW fallback run.
                if (!redirectRequested) {
                    val blockedSurfaceRestored =
                        restoreBlockedSurfaceAfterAddressEdit(transition)
                    if (blockedSurfaceRestored && curtainReadyForTransition(transition)) {
                        FocusGuardLogger.log(
                            "A11y",
                            "Repetindo redirecionamento seguro na mesma aba de $browserPackageName"
                        )
                        delay(WEBSITE_ADDRESS_BAR_ACTION_RETRY_MILLIS)
                        if (!curtainReadyForTransition(transition)) return@launch
                        redirectRequested = requestSafeGoogleInCurrentTab(
                            browserPackageName = browserPackageName,
                            expectedWindowId = expectedWindowId,
                            policy = WebsiteTabNeutralizationPolicy(
                                browserPackageName = browserPackageName,
                                expectedWindowId = expectedWindowId
                            ),
                            transition = transition
                        )
                    }
                }

                // Some browser versions expose a readable address bar but reject or omit
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
                if (!redirectRequested) {
''',
'''                awaitNextWebsiteRedirectFrame()
                if (!transitionOwnsCurtain(transition)) return@launch

                var redirectRequested = false
                if (curtainReadyForTransition(transition)) {
                    val rewritePolicy = WebsiteTabNeutralizationPolicy(
                        browserPackageName = browserPackageName,
                        expectedWindowId = expectedWindowId
                    )
                    redirectRequested = requestSafeGoogleInCurrentTab(
                        browserPackageName = browserPackageName,
                        expectedWindowId = expectedWindowId,
                        policy = rewritePolicy,
                        transition = transition
                    )

                    // Same-tab rewrite remains preferred. Retry only while the original
                    // browser window is still current. If the browser changes window while
                    // focusing/submitting the omnibox, preserve the curtain and fall through
                    // to the explicit package-scoped Google intent instead of abandoning the
                    // redirect transaction.
                    if (!redirectRequested && curtainReadyForTransition(transition)) {
                        val blockedSurfaceRestored =
                            restoreBlockedSurfaceAfterAddressEdit(transition)
                        if (blockedSurfaceRestored && curtainReadyForTransition(transition)) {
                            FocusGuardLogger.log(
                                "A11y",
                                "Repetindo redirecionamento seguro na mesma aba de $browserPackageName"
                            )
                            delay(WEBSITE_ADDRESS_BAR_ACTION_RETRY_MILLIS)
                            if (curtainReadyForTransition(transition)) {
                                redirectRequested = requestSafeGoogleInCurrentTab(
                                    browserPackageName = browserPackageName,
                                    expectedWindowId = expectedWindowId,
                                    policy = WebsiteTabNeutralizationPolicy(
                                        browserPackageName = browserPackageName,
                                        expectedWindowId = expectedWindowId
                                    ),
                                    transition = transition
                                )
                            }
                        }
                    }
                }

                // A failed/partial omnibox navigation can legitimately replace the Android
                // accessibility window before Google is visible. The safe intent does not
                // need the old blocked root: it is fixed to https://www.google.com and to
                // the exact browser package that exposed the blocked page. Keep the curtain
                // up and use this fallback whenever Google was not positively confirmed.
                if (!redirectRequested &&
                    transitionOwnsCurtain(transition) &&
                    supportsCapabilityBasedIntentRedirectFallback(
                        knownBrowser = browserPackageName in knownBrowserPackages,
                        verifiedHttpsHandler = isVerifiedHttpsHandler(browserPackageName)
                    )
                ) {
                    FocusGuardLogger.log(
                        "A11y",
                        "Usando fallback por intent para Google em $browserPackageName"
                    )
                    redirectRequested = requestSafeGoogleThroughBrowserIntent(transition)
                }

                if (!transitionOwnsCurtain(transition)) return@launch
                if (!redirectRequested) {
''',
    "redirect transaction fallback",
)

service = replace_once(
    service,
'''                if (!curtainReadyForTransition(transition)) return@launch
                if (!googleConfirmed) {
''',
'''                if (!transitionOwnsCurtain(transition)) return@launch
                if (!googleConfirmed) {
''',
    "post confirmation curtain check",
)

service = replace_once(
    service,
'''    private suspend fun requestSafeGoogleThroughBrowserIntent(
        transition: WebsiteBlockTransitionHandle
    ): Boolean {
        if (!curtainReadyForTransition(transition)) return false
        val blockedSurfaceStillCurrent = websiteTreeWorker.run {
            currentBrowserSurfaceMatchesBlockedTransition(transition)
        }
        if (!curtainReadyForTransition(transition) || !blockedSurfaceStillCurrent) return false

        return withContext(Dispatchers.Main.immediate) {
            if (!curtainReadyForTransition(transition)) return@withContext false
            val intent = createSafeBrowserRedirectIntent(transition.browserPackageName)
            if (!websiteBlockTransitionGuard.markExternalRedirectRequested(
                    transition.browserPackageName, transition.id, SystemClock.uptimeMillis()
                )
            ) return@withContext false
            try {
                if (!curtainReadyForTransition(transition)) return@withContext false
                startActivity(intent)
                true
            } catch (error: RuntimeException) {
                if (transitionWindowIsCurrent(transition)) {
                    FocusGuardLogger.logError(
                        "A11y",
                        "Falha ao solicitar redirecionamento seguro",
                        error
                    )
                }
                false
            }
        }
    }
''',
'''    private suspend fun requestSafeGoogleThroughBrowserIntent(
        transition: WebsiteBlockTransitionHandle
    ): Boolean {
        if (!transitionOwnsCurtain(transition)) return false

        return withContext(Dispatchers.Main.immediate) {
            if (!transitionOwnsCurtain(transition)) return@withContext false
            val intent = createSafeBrowserRedirectIntent(transition.browserPackageName)
            if (!websiteBlockTransitionGuard.markExternalRedirectRequested(
                    transition.browserPackageName, transition.id, SystemClock.uptimeMillis()
                )
            ) return@withContext false
            try {
                if (!transitionOwnsCurtain(transition)) return@withContext false
                startActivity(intent)
                true
            } catch (error: RuntimeException) {
                if (transitionOwnsCurtain(transition)) {
                    FocusGuardLogger.logError(
                        "A11y",
                        "Falha ao solicitar redirecionamento seguro",
                        error
                    )
                }
                false
            }
        }
    }
''',
    "explicit Google intent request",
)

service = replace_once(
    service,
'''    private fun transitionWindowIsCurrent(transition: WebsiteBlockTransitionHandle): Boolean =
        websiteBlockTransitionGuard.activeTransition(transition.browserPackageName)?.id == transition.id &&
            browserInspectionCoordinator.isCurrentWindow(
                transition.browserPackageName, transition.expectedWindowId, transition.inspectionGeneration
            )

    private fun curtainReadyForTransition(transition: WebsiteBlockTransitionHandle): Boolean =
        transitionWindowIsCurrent(transition) && curtainReadyForTabAction(
            attached = instantBlockCurtainAttached,
            visible = instantBlockCurtainVisible,
            currentGeneration = instantBlockCurtainGeneration,
            expectedGeneration = transition.curtainGeneration
        )

    private fun retireStaleWebsiteTransitions() {
        websiteBlockTransitionGuard.activeBrowserPackages().forEach { packageName ->
            val transition = websiteBlockTransitionGuard.activeTransition(packageName) ?: return@forEach
            if (!transition.handedOff && !transition.destinationRequested &&
                !transitionWindowIsCurrent(transition)
            ) finishWebsiteTransition(transition)
        }
    }
''',
'''    private fun transitionWindowIsCurrent(transition: WebsiteBlockTransitionHandle): Boolean =
        websiteBlockTransitionGuard.activeTransition(transition.browserPackageName)?.id == transition.id &&
            browserInspectionCoordinator.isCurrentWindow(
                transition.browserPackageName, transition.expectedWindowId, transition.inspectionGeneration
            )

    private fun transitionOwnsCurtain(transition: WebsiteBlockTransitionHandle): Boolean =
        websiteBlockTransitionGuard.activeTransition(transition.browserPackageName)?.id == transition.id &&
            curtainReadyForTabAction(
                attached = instantBlockCurtainAttached,
                visible = instantBlockCurtainVisible,
                currentGeneration = instantBlockCurtainGeneration,
                expectedGeneration = transition.curtainGeneration
            )

    private fun curtainReadyForTransition(transition: WebsiteBlockTransitionHandle): Boolean =
        transitionWindowIsCurrent(transition) && transitionOwnsCurtain(transition)

    private fun retireStaleWebsiteTransitions() {
        websiteBlockTransitionGuard.activeBrowserPackages().forEach { packageName ->
            val transition = websiteBlockTransitionGuard.activeTransition(packageName) ?: return@forEach
            // Browser navigation itself can replace the Accessibility window. While the
            // transition still owns the opaque curtain, keep it alive long enough to hand
            // off to a confirmed Google window or to the explicit browser intent fallback.
            if (!transition.handedOff && !transition.destinationRequested &&
                !transitionWindowIsCurrent(transition) &&
                !transitionOwnsCurtain(transition)
            ) finishWebsiteTransition(transition)
        }
    }
''',
    "curtain ownership across browser window changes",
)

service = replace_once(
    service,
'''    private fun failClosedWebsiteTransition(transition: WebsiteBlockTransitionHandle) {
        if (!curtainReadyForTransition(transition)) return
        transition.handedOff = true
        launchOpaqueBrowserFailClosedNotice(
            transition.browserPackageName, SystemClock.uptimeMillis(), transition.curtainGeneration,
            isCurrent = { transitionWindowIsCurrent(transition) }
        )
    }
''',
'''    private fun failClosedWebsiteTransition(transition: WebsiteBlockTransitionHandle) {
        if (!transitionOwnsCurtain(transition)) return
        transition.handedOff = true
        launchOpaqueBrowserFailClosedNotice(
            transition.browserPackageName, SystemClock.uptimeMillis(), transition.curtainGeneration,
            isCurrent = { transitionOwnsCurtain(transition) }
        )
    }
''',
    "fail closed after browser window replacement",
)

service = replace_once(
    service,
'''                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
''',
'''                // Let the browser's normal ACTION_VIEW dispatcher choose the correct
                // activity/tab. CLEAR_TOP/SINGLE_TOP can merely resurface an existing
                // browser activity on some builds without consuming the new URL intent.
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
''',
    "safe redirect intent flags",
)

SERVICE.write_text(service)

race = RACE_TEST.read_text()
race = replace_once(
    race,
'import android.app.Application\n',
'import android.app.Application\nimport android.content.Intent\n',
    "race test Intent import",
)
race = replace_once(
    race,
'''    @Test
    fun windowChangeBeforeIntentNeverStartsActivity() = runTest {
        coordinator.observeWindow(pkg, 11)
        assertEquals(false, callSuspend("requestSafeGoogleThroughBrowserIntent", transition))
        verify(exactly = 0) { service.startActivity(any()) }
    }
''',
'''    @Test
    fun windowChangeBeforeGuardedIntentStillStartsExplicitGoogleFallback() = runTest {
        coordinator.observeWindow(pkg, 11)
        assertEquals(true, callSuspend("requestSafeGoogleThroughBrowserIntent", transition))
        verify(exactly = 1) {
            service.startActivity(match {
                it.action == Intent.ACTION_VIEW &&
                    it.data?.toString() == "https://www.google.com" &&
                    it.`package` == pkg
            })
        }
    }

    @Test
    fun supersededCurtainStillPreventsExplicitGoogleFallback() = runTest {
        coordinator.observeWindow(pkg, 11)
        ReflectionHelpers.setField(service, "instantBlockCurtainGeneration", 2L)
        assertEquals(false, callSuspend("requestSafeGoogleThroughBrowserIntent", transition))
        verify(exactly = 0) { service.startActivity(any()) }
    }
''',
    "runtime intent race tests",
)
RACE_TEST.write_text(race)

nav = NAV_TEST.read_text()
nav = replace_once(
    nav,
'''        assertThat(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK).isNotEqualTo(0)
        assertThat(intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP).isNotEqualTo(0)
        assertThat(intent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP).isNotEqualTo(0)
''',
'''        assertThat(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK).isNotEqualTo(0)
        assertThat(intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP).isEqualTo(0)
        assertThat(intent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP).isEqualTo(0)
''',
    "safe intent flag expectations",
)
NAV_TEST.write_text(nav)

print("Applied HardBlock runtime Google redirect fix")
