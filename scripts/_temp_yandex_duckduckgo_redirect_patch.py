from pathlib import Path

service_path = Path("app/src/main/java/com/focusguard/service/BlockingAccessibilityService.kt")
test_path = Path("app/src/test/java/com/focusguard/service/WebsiteBlockNavigationTest.kt")
service = service_path.read_text()

old_flow = '''                if (!redirectRequested) {
                    FocusGuardLogger.log(
                        "A11y",
                        "Redirecionamento na mesma aba não pôde ser certificado para " +
                            "$browserPackageName (API ${Build.VERSION.SDK_INT}); bloqueando fail-closed"
                    )
                    stateMachine.onFailureOrTimeout()
                    launchOpaqueBrowserFailClosedNotice(
                        browserPackageName = browserPackageName,
                        eventUptimeMillis = SystemClock.uptimeMillis(),
                        curtainGeneration = transition.curtainGeneration
                    )
                    return@launch
                }
'''
new_flow = '''                if (!redirectRequested &&
                    supportsSafeBrowserIntentRedirectFallback(browserPackageName)
                ) {
                    // Yandex and DuckDuckGo can expose a readable blocked URL while
                    // withholding a certifiable editable/submit surface. Only after
                    // both same-tab attempts are exhausted, restore and re-certify
                    // the exact blocked surface before asking that same browser
                    // package to open the safe Google homepage. The curtain remains
                    // up until normal Google confirmation succeeds below.
                    val blockedSurfaceRestored =
                        restoreBlockedSurfaceAfterAddressEdit(transition)
                    if (blockedSurfaceRestored && curtainReadyForTransition(transition)) {
                        FocusGuardLogger.log(
                            "A11y",
                            "Usando fallback seguro por intent para $browserPackageName"
                        )
                        redirectRequested = requestSafeGoogleThroughBrowserIntent(transition)
                    }
                }

                if (!redirectRequested) {
                    FocusGuardLogger.log(
                        "A11y",
                        "Redirecionamento seguro não pôde ser certificado para " +
                            "$browserPackageName (API ${Build.VERSION.SDK_INT}); bloqueando fail-closed"
                    )
                    stateMachine.onFailureOrTimeout()
                    launchOpaqueBrowserFailClosedNotice(
                        browserPackageName = browserPackageName,
                        eventUptimeMillis = SystemClock.uptimeMillis(),
                        curtainGeneration = transition.curtainGeneration
                    )
                    return@launch
                }
'''
assert service.count(old_flow) == 1, f"redirect flow anchors: {service.count(old_flow)}"
service = service.replace(old_flow, new_flow)

old_policy = '''        internal fun mayOpenDestinationAfterSanitization(
            safeGoogleConfirmed: Boolean
        ): Boolean = safeGoogleConfirmed
'''
new_policy = '''        private val SAFE_BROWSER_INTENT_REDIRECT_FALLBACK_PACKAGES = setOf(
            "com.duckduckgo.mobile.android",
            "com.yandex.browser",
            "com.yandex.browser.beta",
            "com.yandex.browser.alpha",
            "com.yandex.browser.lite"
        )

        internal fun supportsSafeBrowserIntentRedirectFallback(
            browserPackageName: String
        ): Boolean = browserPackageName in SAFE_BROWSER_INTENT_REDIRECT_FALLBACK_PACKAGES

        internal fun mayOpenDestinationAfterSanitization(
            safeGoogleConfirmed: Boolean
        ): Boolean = safeGoogleConfirmed
'''
assert service.count(old_policy) == 1, f"policy anchors: {service.count(old_policy)}"
service = service.replace(old_policy, new_policy)
service_path.write_text(service)

tests = test_path.read_text()
anchor = '''    @Test
    fun `Chromium capability policy is package based and rejects stale surfaces`() {
'''
new_tests = '''    @Test
    fun `intent redirect fallback is limited to Yandex family and DuckDuckGo`() {
        listOf(
            "com.duckduckgo.mobile.android",
            "com.yandex.browser",
            "com.yandex.browser.beta",
            "com.yandex.browser.alpha",
            "com.yandex.browser.lite"
        ).forEach { packageName ->
            assertThat(
                BlockingAccessibilityService.supportsSafeBrowserIntentRedirectFallback(packageName)
            ).isTrue()
        }

        listOf(
            CHROME_PACKAGE,
            FIREFOX_PACKAGE,
            BRAVE_PACKAGE,
            "com.sec.android.app.sbrowser",
            "mark.via.gp"
        ).forEach { packageName ->
            assertThat(
                BlockingAccessibilityService.supportsSafeBrowserIntentRedirectFallback(packageName)
            ).isFalse()
        }
    }

    @Test
    fun `Chromium capability policy is package based and rejects stale surfaces`() {
'''
assert tests.count(anchor) == 1, f"test anchors: {tests.count(anchor)}"
test_path.write_text(tests.replace(anchor, new_tests))
