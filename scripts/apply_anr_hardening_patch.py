from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    file = Path(path)
    text = file.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one replacement target, found {count}")
    file.write_text(text.replace(old, new, 1), encoding="utf-8")


service = "app/src/main/java/com/focusguard/service/BlockingAccessibilityService.kt"

replace_once(
    service,
    '''    private val isRefreshingLauncherIndex = AtomicBoolean(false)
    private val launcherIndexRefreshRequested = AtomicBoolean(false)
''',
    '''    private val isRefreshingLauncherIndex = AtomicBoolean(false)
    private val launcherIndexRefreshRequested = AtomicBoolean(false)
    private val lastSlowCallbackLogElapsed = AtomicLong(0L)
'''
)

replace_once(
    service,
    '''    private var browserPackages: Set<String> = emptySet()
    private var verifiedHttpsHandlerPackages: Set<String> = emptySet()
    private val knownBrowserPackages = setOf(
''',
    '''    private var browserPackages: Set<String> = emptySet()
    private var verifiedHttpsHandlerPackages: Set<String> = emptySet()
    private data class BrowserDiscoveryMiss(
        val windowId: Int,
        val checkedAtElapsed: Long
    )
    private val browserDiscoveryMisses = mutableMapOf<String, BrowserDiscoveryMiss>()
    private val knownBrowserPackages = setOf(
'''
)

replace_once(
    service,
    '''    private fun calculateBrowserPackages() {
        browserPackages = try {
''',
    '''    private fun calculateBrowserPackages() {
        browserDiscoveryMisses.clear()
        browserPackages = try {
'''
)

replace_once(
    service,
    '''                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
                AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                    val fastEvent = event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                    if (isRecognizedBrowserSurface(event, packageName) &&
                        (fastEvent || now - lastBrowserCheck >= browserDebounceMillis)
                    ) {
                        lastBrowserCheck = now
                        handleBrowserEvent(event, packageName)
                    }
                }
''',
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
'''
)

replace_once(
    service,
    '''        } catch (error: RuntimeException) {
            FocusGuardLogger.logError("A11y", "Erro no evento de acessibilidade", error)
        }
    }

    private fun consumeInputUiEvent(
''',
    '''        } catch (error: RuntimeException) {
            FocusGuardLogger.logError("A11y", "Erro no evento de acessibilidade", error)
        } finally {
            val elapsedMillis = (
                SystemClock.elapsedRealtimeNanos() - eventDetectedAtNanos
            ).coerceAtLeast(0L) / 1_000_000L
            if (elapsedMillis >= SLOW_ACCESSIBILITY_CALLBACK_MILLIS) {
                val nowElapsed = SystemClock.elapsedRealtime()
                val previousLog = lastSlowCallbackLogElapsed.get()
                val shouldLog = (previousLog == 0L ||
                    nowElapsed - previousLog >= SLOW_CALLBACK_LOG_INTERVAL_MILLIS) &&
                    lastSlowCallbackLogElapsed.compareAndSet(previousLog, nowElapsed)
                if (shouldLog) {
                    val eventType = event.eventType
                    val eventPackage = event.packageName?.toString().orEmpty()
                    val eventWindowId = event.windowId
                    scope.launch {
                        FocusGuardLogger.log(
                            "A11yPerf",
                            "Callback lento: ${elapsedMillis}ms, type=$eventType, " +
                                "package=$eventPackage, window=$eventWindowId"
                        )
                    }
                }
            }
        }
    }

    private fun consumeInputUiEvent(
'''
)

replace_once(
    service,
    '''            isRecognizedBrowserSurface(event, packageName) &&
                (blockedWebsitesDomainSet.isNotEmpty() || limitedWebsiteDomains.isNotEmpty()) ->
                handleBrowserEvent(event, packageName)
''',
    '''            websiteSurfaceInspectionNeeded() &&
                isRecognizedBrowserSurface(event, packageName) ->
                handleBrowserEvent(event, packageName)
'''
)

old_recognizer = '''    private fun isRecognizedBrowserSurface(
        event: AccessibilityEvent,
        packageName: String
    ): Boolean {
        if (packageName.isBlank()) return false
        if (packageName in browserPackages) return true

        if (WebsiteBlocker.extractAddressBarTextFromEvent(
                event,
                packageName,
                httpsHandlerRecognized = false
            ) != null
        ) {
            browserPackages = browserPackages + packageName
            return true
        }

        val canInspectRoot = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED
        if (!canInspectRoot) return false

        val root = rootInActiveWindow ?: sourceNodeForEvent(event) ?: return false
        val recognized = try {
            WebsiteBlocker.hasAddressBarNode(
                root,
                packageName,
                httpsHandlerRecognized = false
            )
        } finally {
            recycleSafely(root)
        }
        if (recognized) browserPackages = browserPackages + packageName
        return recognized
    }
'''
new_recognizer = '''    private fun isRecognizedBrowserSurface(
        event: AccessibilityEvent,
        packageName: String
    ): Boolean {
        if (packageName.isBlank()) return false
        if (packageName in browserPackages) {
            browserDiscoveryMisses.remove(packageName)
            return true
        }

        if (WebsiteBlocker.extractAddressBarTextFromEvent(
                event,
                packageName,
                httpsHandlerRecognized = false
            ) != null
        ) {
            browserPackages = browserPackages + packageName
            browserDiscoveryMisses.remove(packageName)
            return true
        }

        val canInspectRoot = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED
        if (!canInspectRoot) return false

        val nowElapsed = SystemClock.elapsedRealtime()
        val cachedMiss = browserDiscoveryMisses[packageName]
        if (cachedMiss != null &&
            cachedMiss.windowId == event.windowId &&
            nowElapsed - cachedMiss.checkedAtElapsed < UNKNOWN_BROWSER_DISCOVERY_RETRY_MILLIS
        ) return false

        val root = rootInActiveWindow ?: sourceNodeForEvent(event) ?: return false
        val recognized = try {
            WebsiteBlocker.hasAddressBarNode(
                root,
                packageName,
                httpsHandlerRecognized = false
            )
        } finally {
            recycleSafely(root)
        }
        if (recognized) {
            browserPackages = browserPackages + packageName
            browserDiscoveryMisses.remove(packageName)
        } else {
            recordBrowserDiscoveryMiss(packageName, event.windowId, nowElapsed)
        }
        return recognized
    }

    private fun recordBrowserDiscoveryMiss(
        packageName: String,
        windowId: Int,
        checkedAtElapsed: Long
    ) {
        if (packageName !in browserDiscoveryMisses &&
            browserDiscoveryMisses.size >= MAX_BROWSER_DISCOVERY_MISSES
        ) {
            browserDiscoveryMisses.minByOrNull { it.value.checkedAtElapsed }
                ?.key
                ?.let(browserDiscoveryMisses::remove)
        }
        browserDiscoveryMisses[packageName] = BrowserDiscoveryMiss(
            windowId = windowId,
            checkedAtElapsed = checkedAtElapsed
        )
    }
'''
replace_once(service, old_recognizer, new_recognizer)

replace_once(
    service,
    '''    private fun websiteObservationRequired(): Boolean =
        blockedWebsitesDomainSet.isNotEmpty() || hardLimitedWebsiteDomains.isNotEmpty()
''',
    '''    private fun websiteSurfaceInspectionNeeded(): Boolean =
        blockedWebsitesDomainSet.isNotEmpty() || limitedWebsiteDomains.isNotEmpty()

    private fun websiteObservationRequired(): Boolean =
        blockedWebsitesDomainSet.isNotEmpty() || hardLimitedWebsiteDomains.isNotEmpty()
'''
)

replace_once(
    service,
    '''            opaqueBrowserFirstSeenElapsed.clear()
            opaqueBrowserWindowIds.clear()
            opaqueBrowserVerificationScheduled.clear()
            opaqueBrowserRecoveriesRunning.clear()
            isBlockingSessionActive = false
''',
    '''            opaqueBrowserFirstSeenElapsed.clear()
            opaqueBrowserWindowIds.clear()
            opaqueBrowserVerificationScheduled.clear()
            opaqueBrowserRecoveriesRunning.clear()
            browserDiscoveryMisses.clear()
            isBlockingSessionActive = false
'''
)

replace_once(
    service,
    '''                            opaqueBrowserFirstSeenElapsed.clear()
                            opaqueBrowserWindowIds.clear()
                            opaqueBrowserVerificationScheduled.clear()
                            opaqueBrowserRecoveriesRunning.clear()
                        }
                        activeAppLimitsByPackage = activeAppLimits.associateBy { it.packageName }
''',
    '''                            opaqueBrowserFirstSeenElapsed.clear()
                            opaqueBrowserWindowIds.clear()
                            opaqueBrowserVerificationScheduled.clear()
                            opaqueBrowserRecoveriesRunning.clear()
                        }
                        if (blockedWebsiteDomains.isEmpty() && configuredWebsiteDomains.isEmpty()) {
                            browserDiscoveryMisses.clear()
                        }
                        activeAppLimitsByPackage = activeAppLimits.associateBy { it.packageName }
'''
)

replace_once(
    service,
    '''        internal const val UNSAFE_WINDOW_RECHECK_MILLIS = 240L
        internal const val EVENT_NOTIFICATION_TIMEOUT_MILLIS = 0L
''',
    '''        internal const val UNSAFE_WINDOW_RECHECK_MILLIS = 240L
        private const val SLOW_ACCESSIBILITY_CALLBACK_MILLIS = 250L
        private const val SLOW_CALLBACK_LOG_INTERVAL_MILLIS = 5_000L
        private const val UNKNOWN_BROWSER_DISCOVERY_RETRY_MILLIS = 750L
        private const val MAX_BROWSER_DISCOVERY_MISSES = 32
        internal const val EVENT_NOTIFICATION_TIMEOUT_MILLIS = 0L
'''
)


test_file = "app/src/test/java/com/focusguard/service/BlockingAccessibilityInputTest.kt"
replace_once(
    test_file,
    '''    @Test
    fun `keyboard open type and close preserve the app and do not read either tree`() {
        val service = spyk(BlockingAccessibilityService())
        val ownWindow = window(1, AccessibilityWindowInfo.TYPE_APPLICATION)
        val imeWindow = window(2, AccessibilityWindowInfo.TYPE_INPUT_METHOD)
        var visibleWindows = listOf(ownWindow, imeWindow)
        every { service.packageName } returns ownPackage
        every { service.windows } answers { visibleWindows }
        every { service.rootInActiveWindow } returns null

        service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_VIEW_FOCUSED, ownPackage, 1))
        val transition = event(AccessibilityEvent.TYPE_WINDOWS_CHANGED, null, 2)
        val typing = event(
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            "com.samsung.android.honeyboard",
            2
        )
        service.onAccessibilityEvent(transition)
        repeat(100) { service.onAccessibilityEvent(typing) }
        visibleWindows = listOf(ownWindow)
        service.onAccessibilityEvent(transition)

        verify(exactly = 2) { service.windows }
        verify(exactly = 0) { service.rootInActiveWindow }
        verify(exactly = 0) { ownWindow.root }
        verify(exactly = 0) { imeWindow.root }
        verify(exactly = 0) { transition.source }
        verify(exactly = 0) { typing.source }
        assertThat(ReflectionHelpers.getField<String>(service, "foregroundPackageName"))
            .isEqualTo(ownPackage)
    }
''',
    '''    @Test
    fun `keyboard open type and close preserve the app and do not read either tree`() {
        val service = spyk(BlockingAccessibilityService())
        val ownWindow = window(1, AccessibilityWindowInfo.TYPE_APPLICATION)
        val imeWindow = window(2, AccessibilityWindowInfo.TYPE_INPUT_METHOD)
        var visibleWindows = listOf(ownWindow, imeWindow)
        every { service.packageName } returns ownPackage
        every { service.windows } answers { visibleWindows }
        every { service.rootInActiveWindow } returns null

        service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_VIEW_FOCUSED, ownPackage, 1))
        val transition = event(AccessibilityEvent.TYPE_WINDOWS_CHANGED, null, 2)
        val typing = event(
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            "com.samsung.android.honeyboard",
            2
        )
        service.onAccessibilityEvent(transition)
        repeat(100) { service.onAccessibilityEvent(typing) }
        visibleWindows = listOf(ownWindow)
        service.onAccessibilityEvent(transition)

        verify(exactly = 2) { service.windows }
        verify(exactly = 0) { service.rootInActiveWindow }
        verify(exactly = 0) { ownWindow.root }
        verify(exactly = 0) { imeWindow.root }
        verify(exactly = 0) { transition.source }
        verify(exactly = 0) { typing.source }
        assertThat(ReflectionHelpers.getField<String>(service, "foregroundPackageName"))
            .isEqualTo(ownPackage)
    }

    @Test
    fun `app only blocking storm never probes an unrelated app as a browser`() {
        val service = spyk(BlockingAccessibilityService())
        every { service.packageName } returns ownPackage
        every { service.rootInActiveWindow } returns null
        ReflectionHelpers.setField(service, "isBlockingSessionActive", true)
        ReflectionHelpers.setField(service, "lastLoadTime", System.currentTimeMillis())

        val spotify = event(
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            "com.spotify.music",
            7
        )
        repeat(100) { service.onAccessibilityEvent(spotify) }

        verify(exactly = 0) { service.rootInActiveWindow }
        verify(exactly = 0) { spotify.source }
        verify(exactly = 0) { spotify.getSource(0) }
    }
'''
)


release = ".github/workflows/release.yml"
replace_once(
    release,
    '''      - name: Run checks and build canonical Release APK and AAB
        shell: bash
        env:
          KEYSTORE_FILE: ${{ runner.temp }}/focusguard-release.jks
        run: |
          set -euo pipefail
          ./gradlew \\
            lintRelease \\
            testReleaseUnitTest \\
            assembleRelease \\
            bundleRelease \\
            --no-daemon \\
            --stacktrace
''',
    '''      - name: Run release lint
        shell: bash
        env:
          KEYSTORE_FILE: ${{ runner.temp }}/focusguard-release.jks
        run: |
          set -euo pipefail
          set -o pipefail
          ./gradlew lintRelease --no-daemon --stacktrace 2>&1 | tee release-lint-gradle.log

      - name: Run release unit tests
        shell: bash
        env:
          KEYSTORE_FILE: ${{ runner.temp }}/focusguard-release.jks
        run: |
          set -euo pipefail
          set +e
          ./gradlew testReleaseUnitTest --no-daemon --stacktrace 2>&1 | tee release-unit-tests-gradle.log
          first_status=${PIPESTATUS[0]}
          set -e
          if [ "$first_status" -eq 0 ]; then
            exit 0
          fi

          if grep -q "MavenArtifactFetcher" release-unit-tests-gradle.log && \\
             grep -Eq "SocketException|ConnectException|UnknownHostException|SocketTimeoutException" release-unit-tests-gradle.log; then
            echo "::warning::Transient Robolectric dependency transport failure detected; retrying release unit tests once."
            ./gradlew testReleaseUnitTest --no-daemon --stacktrace 2>&1 | tee -a release-unit-tests-gradle.log
          else
            exit "$first_status"
          fi

      - name: Build canonical Release APK and AAB
        shell: bash
        env:
          KEYSTORE_FILE: ${{ runner.temp }}/focusguard-release.jks
        run: |
          set -euo pipefail
          set -o pipefail
          ./gradlew assembleRelease bundleRelease --no-daemon --stacktrace 2>&1 | tee release-build-gradle.log

      - name: Upload release diagnostics
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: release-diagnostics-${{ github.run_number }}
          path: |
            release-lint-gradle.log
            release-unit-tests-gradle.log
            release-build-gradle.log
            app/build/reports/tests/
            app/build/test-results/
            app/build/reports/lint-results-*.html
            app/build/reports/lint-results-*.xml
          if-no-files-found: warn
          retention-days: 14
'''
)

print("ANR hardening replacements applied successfully")
