from pathlib import Path

path = Path('app/src/main/java/com/focusguard/service/BlockingAccessibilityService.kt')
text = path.read_text()

def replace(old: str, new: str, count: int = 1):
    global text
    found = text.count(old)
    if found < count:
        raise SystemExit(f'marker not found enough times ({found} < {count}): {old[:120]!r}')
    text = text.replace(old, new, count)

replace(
    'import com.focusguard.accessibility.website.identification.WebsiteIdentificationRecovery\n',
    'import com.focusguard.accessibility.website.identification.WebsiteIdentificationEngine\nimport com.focusguard.accessibility.website.identification.WebsiteIdentificationRecovery\n'
)

replace(
    '    private val lastSlowCallbackLogElapsed = AtomicLong(0L)\n',
    '    private val lastSlowCallbackLogElapsed = AtomicLong(0L)\n    private val browserInspectionCoordinator = BrowserInspectionCoordinator()\n'
)

replace(
    '    private var browserPackages: Set<String> = emptySet()\n',
    '    @Volatile private var browserPackages: Set<String> = emptySet()\n'
)

replace(
'''        internal var expectedWindowId: Int,
        val blockedCandidate: String?,''',
'''        internal var expectedWindowId: Int,
        val inspectionGeneration: Long,
        val blockedCandidate: String?,'''
)
replace(
'''            expectedWindowId: Int = INVALID_BROWSER_WINDOW_ID,
            blockedCandidate: String? = null,''',
'''            expectedWindowId: Int = INVALID_BROWSER_WINDOW_ID,
            inspectionGeneration: Long = 0L,
            blockedCandidate: String? = null,'''
)
replace(
'''                expectedWindowId = expectedWindowId,
                blockedCandidate = blockedCandidate,''',
'''                expectedWindowId = expectedWindowId,
                inspectionGeneration = inspectionGeneration,
                blockedCandidate = blockedCandidate,'''
)

replace(
'''            // An in-flight website transition still consumes browser events, but
            // first gives them a chance to prove that the requested safe surface
            // is actually visible. The curtain is never released on elapsed time.
            if (observeWebsiteTransitionDestination(event, directPackage)) return
''',
'''            // Browser events are reduced to immutable primitives here. Any tree/root
            // inspection and safe-destination confirmation happens on the serial IO worker.
            if (directPackage.isNotBlank() && websiteBlockTransitionGuard.isActive(directPackage)) {
                websiteBlockTransitionGuard.observeBrowserEvent(
                    browserPackageName = directPackage,
                    windowId = event.windowId,
                    eventUptimeMillis = event.eventTime,
                    eventType = event.eventType
                )
                scheduleBrowserInspection(event, directPackage)
                return
            }
'''
)

replace(
'''            // Website fast path: mirror the launcher/app fast path above. For a
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
''',
'''            // Website inspection is always asynchronous. Never dereference event.source,
            // rootInActiveWindow or a browser tree from the accessibility callback.
            val browserInspectionEvent = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
                event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
                event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ||
                event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED
            if (browserInspectionEvent && directPackage.isNotBlank()) {
                if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                    event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
                ) {
                    browserInspectionCoordinator.observeWindow(directPackage, event.windowId)
                }
                if (websiteSurfaceInspectionNeeded()) scheduleBrowserInspection(event, directPackage)
            }
'''
)

replace(
    '            val packageName = resolveEventPackageName(event)\n',
    '            val packageName = directPackage.ifBlank { foregroundPackageName.orEmpty() }\n'
)

replace(
'''                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
                AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                    val fastEvent = event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                    if (websiteSurfaceInspectionNeeded() &&
                        isRecognizedBrowserSurface(event, packageName) &&
                        (fastEvent || now - lastBrowserCheck >= browserDebounceMillis)
                    ) {
                        lastBrowserCheck = now
                        handleBrowserEvent(event, packageName)
                    }
                }
''',
'''                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
                AccessibilityEvent.TYPE_VIEW_FOCUSED -> Unit // queued above; tree work is off-main
'''
)

replace(
'''            websiteSurfaceInspectionNeeded() &&
                isRecognizedBrowserSurface(event, packageName) ->
                handleBrowserEvent(event, packageName)
''',
'''            websiteSurfaceInspectionNeeded() && packageName in browserPackages -> Unit
'''
)

anchor = '''    private fun websiteSurfaceInspectionNeeded(): Boolean =
'''
if anchor not in text:
    raise SystemExit('websiteSurfaceInspectionNeeded anchor missing')
async_impl = r'''    private fun scheduleBrowserInspection(event: AccessibilityEvent, packageName: String) {
        if (packageName.isBlank()) return
        val offer = browserInspectionCoordinator.offer(
            packageName = packageName,
            windowId = event.windowId,
            eventType = event.eventType,
            eventUptimeMillis = event.eventTime,
            receivedUptimeMillis = SystemClock.uptimeMillis(),
            className = event.className?.toString().orEmpty(),
            directText = event.text.orEmpty().mapNotNull { it?.toString() },
            contentDescription = event.contentDescription?.toString()
        )
        if (!offer.startWorker) return
        scope.launch { drainBrowserInspections() }
    }

    private suspend fun drainBrowserInspections() {
        var snapshot = browserInspectionCoordinator.takePending()
        while (snapshot != null) {
            val outcome = if (browserInspectionCoordinator.isCurrent(snapshot.token)) {
                inspectBrowserSnapshot(snapshot)
            } else null
            if (outcome != null &&
                browserInspectionCoordinator.isCurrent(
                    outcome.snapshot.token,
                    requireLatestSequence = true
                )
            ) {
                withContext(Dispatchers.Main.immediate) {
                    if (browserInspectionCoordinator.isCurrent(
                            outcome.snapshot.token,
                            requireLatestSequence = true
                        )
                    ) applyBrowserInspectionOutcome(outcome)
                }
            }
            snapshot = browserInspectionCoordinator.finishPass()
        }
    }

    private fun inspectBrowserSnapshot(
        snapshot: BrowserInspectionCoordinator.Snapshot
    ): BrowserInspectionOutcome? {
        val token = snapshot.token
        if (!browserInspectionCoordinator.isCurrent(token)) return null
        val root = activeBrowserRoot(token.packageName, token.windowId) ?: return null
        return try {
            if (!browserInspectionCoordinator.isCurrent(token)) return null
            val surface = BrowserSurfaceInspector.inspect(root, token.packageName)
            if (!browserInspectionCoordinator.isCurrent(token)) return null
            val identification = WebsiteIdentificationEngine.identifyFromRoot(
                root = root,
                browserPackageName = token.packageName,
                expectedWindowId = token.windowId,
                httpsHandlerRecognized = isVerifiedHttpsHandler(token.packageName)
            )
            if (!browserInspectionCoordinator.isCurrent(token)) return null
            val addressBarPresent = identification.addressBarObservable ||
                WebsiteBlocker.hasAddressBarNode(
                    root,
                    token.packageName,
                    isVerifiedHttpsHandler(token.packageName)
                )
            if (!browserInspectionCoordinator.isCurrent(token)) return null
            val focusedEditor = addressBarPresent &&
                AddressBarRedirectionActions.hasFocusedAddressEditor(
                    root,
                    token.packageName,
                    token.windowId,
                    isVerifiedHttpsHandler(token.packageName),
                    requireUnique = false
                )
            BrowserInspectionOutcome(
                snapshot = snapshot,
                identification = identification,
                surface = surface,
                addressBarPresent = addressBarPresent,
                focusedAddressEditor = focusedEditor,
                recognizedBrowser = token.packageName in browserPackages || addressBarPresent
            )
        } finally {
            recycleSafely(root)
        }
    }

    private fun applyBrowserInspectionOutcome(outcome: BrowserInspectionOutcome) {
        val snapshot = outcome.snapshot
        val token = snapshot.token
        if (!browserInspectionCoordinator.isCurrent(token, requireLatestSequence = true)) return
        val packageName = token.packageName
        if (!outcome.recognizedBrowser) {
            recordBrowserDiscoveryMiss(packageName, token.windowId, SystemClock.elapsedRealtime())
            return
        }
        if (packageName !in browserPackages) {
            browserPackages = browserPackages + packageName
            browserDiscoveryMisses.remove(packageName)
        }
        foregroundPackageName = packageName

        if (websiteBlockTransitionGuard.isActive(packageName)) {
            observeSafeDestinationFromInspection(outcome)
            return
        }

        if (outcome.surface == BrowserSurfaceInspector.Surface.NATIVE_PANEL) {
            clearOpaqueBrowserObservation(packageName)
            stopWebsiteTracking()
            return
        }

        val identification = outcome.identification
        val candidate = identification.bestCandidate
        val blocked = immediateWebsiteBlockTarget(
            addressText = identification.rawAddressText,
            url = identification.urlCandidate,
            blockedRules = blockedWebsitesDomainSet
        )
        if (blocked != null && candidate != null &&
            browserInspectionCoordinator.isCurrent(token, requireLatestSequence = true)
        ) {
            routeWebsiteBlockByHierarchy(
                browserPackageName = packageName,
                browserWindowId = token.windowId,
                blockedCandidate = candidate,
                detectionEventUptimeMillis = snapshot.eventUptimeMillis,
                browserWindowIdValidated = true
            )
            return
        }

        val url = identification.urlCandidate
        if (!url.isNullOrBlank()) {
            clearOpaqueBrowserObservation(packageName)
            updateWebsiteTracking(url, packageName, System.currentTimeMillis())
            return
        }

        if (outcome.surface == BrowserSurfaceInspector.Surface.WEB_CONTENT &&
            websiteObservationRequired() && !outcome.focusedAddressEditor
        ) {
            scheduleAsyncWebsiteRecovery(outcome)
        } else if (snapshot.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            stopWebsiteTracking()
        }
    }

    private fun observeSafeDestinationFromInspection(outcome: BrowserInspectionOutcome) {
        val snapshot = outcome.snapshot
        val token = snapshot.token
        val transition = websiteBlockTransitionGuard.transitionForConfirmation(
            browserPackageName = token.packageName,
            windowId = token.windowId,
            eventUptimeMillis = snapshot.eventUptimeMillis,
            eventType = snapshot.eventType
        ) ?: return
        if (!isSafeGoogleRedirectSurface(outcome.identification.bestCandidate) ||
            outcome.surface != BrowserSurfaceInspector.Surface.WEB_CONTENT ||
            outcome.focusedAddressEditor
        ) return
        scope.launch {
            delay(WEBSITE_GOOGLE_SURFACE_SETTLE_MILLIS)
            if (!browserInspectionCoordinator.isCurrent(token)) return@launch
            val stable = inspectBrowserSnapshot(snapshot) ?: return@launch
            if (!isSafeGoogleRedirectSurface(stable.identification.bestCandidate) ||
                stable.surface != BrowserSurfaceInspector.Surface.WEB_CONTENT ||
                stable.focusedAddressEditor
            ) return@launch
            withContext(Dispatchers.Main.immediate) {
                if (!browserInspectionCoordinator.isCurrent(token)) return@withContext
                val current = websiteBlockTransitionGuard.transitionForConfirmation(
                    browserPackageName = token.packageName,
                    windowId = token.windowId,
                    eventUptimeMillis = snapshot.eventUptimeMillis,
                    eventType = snapshot.eventType
                ) ?: return@withContext
                if (current.id != transition.id) return@withContext
                if (websiteBlockTransitionGuard.confirmGoogle(
                        browserPackageName = token.packageName,
                        windowId = token.windowId,
                        eventUptimeMillis = snapshot.eventUptimeMillis
                    )
                ) BrowserCompatibilityStore.recordNavigationConfirmed(token.packageName)
            }
        }
    }

    private fun scheduleAsyncWebsiteRecovery(outcome: BrowserInspectionOutcome) {
        val token = outcome.snapshot.token
        val packageName = token.packageName
        if (!opaqueBrowserRecoveriesRunning.add(packageName)) return
        scope.launch {
            try {
                delay(WebsiteObservabilityPolicy.OPAQUE_BROWSER_GRACE_MILLIS)
                fun current(): Boolean =
                    browserInspectionCoordinator.isCurrent(token) &&
                        foregroundPackageName == packageName &&
                        websiteObservationRequired() &&
                        !websiteBlockTransitionGuard.isActive(packageName)
                if (!current()) return@launch
                val identification = WebsiteIdentificationRecovery(
                    browserPackage = packageName,
                    windowId = token.windowId,
                    httpsHandlerRecognized = isVerifiedHttpsHandler(packageName),
                    rootProvider = {
                        if (current()) activeBrowserRoot(packageName, token.windowId) else null
                    },
                    isCurrent = ::current
                ).recover()
                if (!current()) return@launch
                withContext(Dispatchers.Main.immediate) {
                    if (!current()) return@withContext
                    val candidate = identification.bestCandidate
                    val blocked = immediateWebsiteBlockTarget(
                        identification.rawAddressText,
                        identification.urlCandidate,
                        blockedWebsitesDomainSet
                    )
                    when {
                        blocked != null && candidate != null -> routeWebsiteBlockByHierarchy(
                            packageName,
                            token.windowId,
                            candidate,
                            SystemClock.uptimeMillis(),
                            browserWindowIdValidated = true
                        )
                        identification.urlCandidate != null -> updateWebsiteTracking(
                            identification.urlCandidate,
                            packageName,
                            System.currentTimeMillis()
                        )
                        identification.webContentObserved &&
                            !identification.addressBarObservable &&
                            identification.status != WebsiteIdentificationStatus.REJECTED_CONTEXT ->
                            blockOpaqueBrowser(packageName)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: RuntimeException) {
                FocusGuardLogger.logError("A11y", "Falha na inspeção assíncrona do navegador", error)
            } finally {
                withContext(Dispatchers.Main.immediate) {
                    opaqueBrowserRecoveriesRunning.remove(packageName)
                }
            }
        }
    }

'''
text = text.replace(anchor, async_impl + anchor, 1)

replace(
'''        val strict = isPomodoroStrictActive
        val transitionId = websiteBlockTransitionCounter.incrementAndGet()
''',
'''        val inspectionGeneration = browserInspectionCoordinator.currentGeneration(
            browserPackageName,
            expectedWindowId
        ) ?: browserInspectionCoordinator.observeWindow(browserPackageName, expectedWindowId)
        val strict = isPomodoroStrictActive
        val transitionId = websiteBlockTransitionCounter.incrementAndGet()
'''
)
replace(
'''            expectedWindowId = expectedWindowId,
            blockedCandidate = blockedCandidate,
            blockedRules = blockedWebsitesDomainSet,
''',
'''            expectedWindowId = expectedWindowId,
            inspectionGeneration = inspectionGeneration,
            blockedCandidate = blockedCandidate,
            blockedRules = blockedWebsitesDomainSet,
'''
)

replace(
'''    private fun curtainReadyForTransition(transition: WebsiteBlockTransitionHandle): Boolean =
        curtainReadyForTabAction(
''',
'''    private fun curtainReadyForTransition(transition: WebsiteBlockTransitionHandle): Boolean =
        browserInspectionCoordinator.isCurrentWindow(
            transition.browserPackageName,
            transition.expectedWindowId,
            transition.inspectionGeneration
        ) && curtainReadyForTabAction(
'''
)

# Recovery must no longer run its tree/action phases on Main.
replace(
'''        scope.launch(Dispatchers.Main.immediate) {
            try {
                val identification = WebsiteIdentificationRecovery(
''',
'''        scope.launch {
            try {
                val identification = WebsiteIdentificationRecovery(
'''
)

path.write_text(text)
print('async accessibility refactor applied')
