from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(relative_path: str, old: str, new: str) -> None:
    path = ROOT / relative_path
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{relative_path}: expected exactly one match, found {count}\n--- expected ---\n{old}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


# 1) Restore the executable redirect plan that existed in the known-good release:
# two certified same-tab attempts, then one package-scoped browser intent fallback.
replace_once(
    "app/src/main/java/com/focusguard/accessibility/website/redirection/WebsiteRedirectionPlan.kt",
    """    const val MAX_SAME_TAB_ATTEMPTS = 2\n    const val MAX_SUBMIT_ALTERNATIVES = 3\n    const val DESTINATION_CONFIRM_TIMEOUT_MILLIS = 2_000L\n""",
    """    const val MAX_SAME_TAB_ATTEMPTS = 2\n    const val MAX_SUBMIT_ALTERNATIVES = 3\n\n    /**\n     * The 601f93e release tried a package-scoped safe ACTION_VIEW only after the\n     * certified same-tab path was exhausted. The opaque curtain stays up until the\n     * destination is positively observed, so this request is never treated as proof.\n     */\n    const val ALLOW_EXTERNAL_BROWSER_INTENT_FALLBACK = true\n\n    const val DESTINATION_CONFIRM_TIMEOUT_MILLIS = 2_000L\n""",
)
replace_once(
    "app/src/main/java/com/focusguard/accessibility/website/redirection/WebsiteRedirectionPlan.kt",
    """    /**\n     * Coordinate taps, guessed keyboard positions and ACTION_VIEW are deliberately\n     * excluded. Exhausting this bounded same-tab budget is terminal fail-closed.\n     */\n    fun canRetry(attemptNumber: Int): Boolean = attemptNumber < MAX_SAME_TAB_ATTEMPTS\n""",
    """    /**\n     * Coordinate taps and guessed keyboard positions remain excluded. Once this\n     * bounded same-tab budget is exhausted, the legacy package-scoped browser\n     * intent fallback gets one chance; failure or unconfirmed navigation remains\n     * terminal fail-closed.\n     */\n    fun canRetry(attemptNumber: Int): Boolean = attemptNumber < MAX_SAME_TAB_ATTEMPTS\n""",
)

# 2) Restore the legacy transaction ordering without undoing the current separation
# of Android tree work behind the adapter.
replace_once(
    "app/src/main/java/com/focusguard/accessibility/website/redirection/WebsiteRedirectionCoordinator.kt",
    """        suspend fun beforeRetry(nextAttemptNumber: Int)\n        suspend fun awaitRedirectConfirmation(): Boolean\n        suspend fun completeStrictDestination(): Boolean\n""",
    """        suspend fun beforeRetry(nextAttemptNumber: Int)\n        suspend fun awaitRedirectConfirmation(): Boolean\n        suspend fun requestExternalBrowserRedirect(): Boolean = false\n        suspend fun completeStrictDestination(): Boolean\n""",
)
replace_once(
    "app/src/main/java/com/focusguard/accessibility/website/redirection/WebsiteRedirectionCoordinator.kt",
    """            if (!WebsiteRedirectionPlan.canRetry(attemptNumber)) {\n                return failClosed(session, adapter)\n            }\n            if (!adapter.restoreBlockedSurfaceForRetry()) {\n                if (!adapter.ownsProtection()) return Outcome.ABORTED\n                if (submittedAtLeastOnce && adapter.awaitRedirectConfirmation()) {\n                    if (!adapter.ownsProtection()) return Outcome.ABORTED\n                    return completeConfirmedRedirect(session, adapter)\n                }\n                if (!adapter.ownsProtection()) return Outcome.ABORTED\n                return failClosed(session, adapter)\n            }\n""",
    """            if (!WebsiteRedirectionPlan.canRetry(attemptNumber)) {\n                return completeExternalFallbackOrFailClosed(session, adapter)\n            }\n            if (!adapter.restoreBlockedSurfaceForRetry()) {\n                if (!adapter.ownsProtection()) return Outcome.ABORTED\n                if (submittedAtLeastOnce && adapter.awaitRedirectConfirmation()) {\n                    if (!adapter.ownsProtection()) return Outcome.ABORTED\n                    return completeConfirmedRedirect(session, adapter)\n                }\n                if (!adapter.ownsProtection()) return Outcome.ABORTED\n                return completeExternalFallbackOrFailClosed(session, adapter)\n            }\n""",
)
replace_once(
    "app/src/main/java/com/focusguard/accessibility/website/redirection/WebsiteRedirectionCoordinator.kt",
    """        return failClosed(session, adapter)\n    }\n\n    private suspend fun completeConfirmedRedirect(\n""",
    """        return completeExternalFallbackOrFailClosed(session, adapter)\n    }\n\n    private suspend fun completeExternalFallbackOrFailClosed(\n        session: Session,\n        adapter: Adapter\n    ): Outcome {\n        if (!adapter.ownsProtection()) return Outcome.ABORTED\n        if (!adapter.mayAttemptRedirect()) return failClosed(session, adapter)\n        if (!WebsiteRedirectionPlan.ALLOW_EXTERNAL_BROWSER_INTENT_FALLBACK) {\n            return failClosed(session, adapter)\n        }\n        if (!adapter.requestExternalBrowserRedirect()) {\n            if (!adapter.ownsProtection()) return Outcome.ABORTED\n            return failClosed(session, adapter)\n        }\n        if (!adapter.ownsProtection()) return Outcome.ABORTED\n        if (!adapter.awaitRedirectConfirmation()) {\n            if (!adapter.ownsProtection()) return Outcome.ABORTED\n            return failClosed(session, adapter)\n        }\n        if (!adapter.ownsProtection()) return Outcome.ABORTED\n        return completeConfirmedRedirect(session, adapter)\n    }\n\n    private suspend fun completeConfirmedRedirect(\n""",
)

# The working release bound edits to package+window+state. It did not reject a valid
# omnibox merely because another same-window accessibility event advanced a timestamp.
replace_once(
    "app/src/main/java/com/focusguard/accessibility/website/redirection/WebsiteRedirectionCoordinator.kt",
    """    fun mayActivateBlockedAddressBar(\n        activePackageName: String,\n        activeWindowId: Int,\n        phaseStartedAtUptimeMillis: Long,\n        latestWindowTransitionEventUptimeMillis: Long\n    ): Boolean = state == State.BLOCKED_TAB &&\n        phaseStartedAtUptimeMillis > 0L &&\n        latestWindowTransitionEventUptimeMillis <= phaseStartedAtUptimeMillis &&\n        activePackageName == browserPackageName &&\n        activeWindowId == expectedWindowId\n""",
    """    fun mayActivateBlockedAddressBar(\n        activePackageName: String,\n        activeWindowId: Int,\n        @Suppress(\"UNUSED_PARAMETER\") phaseStartedAtUptimeMillis: Long,\n        @Suppress(\"UNUSED_PARAMETER\") latestWindowTransitionEventUptimeMillis: Long\n    ): Boolean = state == State.BLOCKED_TAB &&\n        activePackageName == browserPackageName &&\n        activeWindowId == expectedWindowId\n""",
)
replace_once(
    "app/src/main/java/com/focusguard/accessibility/website/redirection/WebsiteRedirectionCoordinator.kt",
    """    fun maySubmitSafeAddress(\n        activePackageName: String,\n        activeWindowId: Int,\n        latestWindowTransitionEventUptimeMillis: Long\n    ): Boolean = safeAddressSetAtUptimeMillis > 0L &&\n        activePackageName == browserPackageName &&\n        activeWindowId == expectedWindowId &&\n        when (state) {\n            State.SAFE_ADDRESS_SET ->\n                latestWindowTransitionEventUptimeMillis <= safeAddressSetAtUptimeMillis\n            State.REDIRECT_REQUESTED -> true\n            State.BLOCKED_TAB -> false\n        }\n""",
    """    fun maySubmitSafeAddress(\n        activePackageName: String,\n        activeWindowId: Int,\n        @Suppress(\"UNUSED_PARAMETER\") latestWindowTransitionEventUptimeMillis: Long\n    ): Boolean = safeAddressSetAtUptimeMillis > 0L &&\n        activePackageName == browserPackageName &&\n        activeWindowId == expectedWindowId &&\n        (state == State.SAFE_ADDRESS_SET || state == State.REDIRECT_REQUESTED)\n""",
)

# 3) Wire the current Android adapter back to the same legacy fallback. The modern
# safe-destination verifier is retained: ACTION_VIEW is only a request and the curtain
# is released only after the configured safe root is observed.
replace_once(
    "app/src/main/java/com/focusguard/service/BlockingAccessibilityService.kt",
    """                        override suspend fun completeStrictDestination(): Boolean =\n""",
    """                        override suspend fun requestExternalBrowserRedirect(): Boolean =\n                            requestSafeRedirectThroughBrowserIntent(transition)\n\n                        override suspend fun completeStrictDestination(): Boolean =\n""",
)

service_helpers = r'''    private suspend fun restoreBlockedSurfaceForSafeIntentFallback(
        transition: WebsiteBlockTransitionHandle
    ): Boolean {
        if (!curtainReadyForTransition(transition)) return false
        if (transition.activatedAddressViewId != null ||
            transition.editorAddressViewId != null
        ) {
            if (!performTransitionBack(transition)) return false
            delay(WEBSITE_ADDRESS_BAR_FOCUS_SETTLE_MILLIS)
            if (!curtainReadyForTransition(transition)) return false
        }
        val restored = websiteTreeWorker.run {
            currentBrowserSurfaceMatchesBlockedTransition(transition)
        }
        if (!curtainReadyForTransition(transition)) return false
        transition.activatedAddressViewId = null
        transition.editorAddressViewId = null
        return restored
    }

    private suspend fun requestSafeRedirectThroughBrowserIntent(
        transition: WebsiteBlockTransitionHandle
    ): Boolean {
        val browserPackageName = transition.browserPackageName
        if (!WebsiteRedirectionPlan.ALLOW_EXTERNAL_BROWSER_INTENT_FALLBACK ||
            !supportsCapabilityBasedIntentRedirectFallback(
                knownBrowser = browserPackageName in knownBrowserPackages,
                verifiedHttpsHandler = isVerifiedHttpsHandler(browserPackageName)
            ) ||
            !transitionOwnsCurtain(transition)
        ) return false

        // Match 601f93e: the external request is allowed only after the original
        // blocked surface has been restored and re-certified in the same window.
        if (!restoreBlockedSurfaceForSafeIntentFallback(transition) ||
            !curtainReadyForTransition(transition)
        ) return false

        val requestedAt = SystemClock.uptimeMillis()
        if (!websiteBlockTransitionGuard.markSanitizationRequested(
                browserPackageName = browserPackageName,
                transitionId = transition.id,
                requestedAtUptimeMillis = requestedAt
            )
        ) return false

        return withContext(Dispatchers.Main.immediate) {
            if (!transitionOwnsCurtain(transition)) return@withContext false
            runCatching {
                startActivity(createSafeBrowserRedirectIntent(browserPackageName))
                true
            }.getOrElse { error ->
                if (transitionOwnsCurtain(transition)) {
                    FocusGuardLogger.logError(
                        "A11y",
                        "Falha ao solicitar redirecionamento seguro no navegador",
                        error
                    )
                }
                false
            }
        }
    }

'''
replace_once(
    "app/src/main/java/com/focusguard/service/BlockingAccessibilityService.kt",
    """    private suspend fun completeStrictWebsiteDestination(\n""",
    service_helpers + """    private suspend fun completeStrictWebsiteDestination(\n""",
)

replace_once(
    "app/src/main/java/com/focusguard/service/BlockingAccessibilityService.kt",
    """        private const val WEBSITE_ADDRESS_BAR_FOCUS_SETTLE_MILLIS = 32L\n        private const val WEBSITE_ADDRESS_BAR_ACTION_RETRY_MILLIS = 16L\n        private const val WEBSITE_ADDRESS_BAR_ACTION_TIMEOUT_MILLIS = 360L\n        private const val WEBSITE_FIREFOX_ADDRESS_BAR_ACTION_TIMEOUT_MILLIS = 800L\n        private const val WEBSITE_REDIRECT_SURFACE_SETTLE_MILLIS = 80L\n        internal const val WEBSITE_MIN_BLOCK_NOTICE_MILLIS = 250L\n""",
    """        private const val WEBSITE_ADDRESS_BAR_FOCUS_SETTLE_MILLIS = 48L\n        private const val WEBSITE_ADDRESS_BAR_ACTION_RETRY_MILLIS = 32L\n        private const val WEBSITE_ADDRESS_BAR_ACTION_TIMEOUT_MILLIS = 360L\n        private const val WEBSITE_FIREFOX_ADDRESS_BAR_ACTION_TIMEOUT_MILLIS = 800L\n        private const val WEBSITE_REDIRECT_SURFACE_SETTLE_MILLIS = 120L\n        internal const val WEBSITE_MIN_BLOCK_NOTICE_MILLIS = 1_000L\n""",
)

replace_once(
    "app/src/main/java/com/focusguard/service/BlockingAccessibilityService.kt",
    """        internal fun isSafeRedirectSurface(urlOrAddress: String?): Boolean =\n            WebsiteRedirectDestination.current.matchesSurface(urlOrAddress)\n\n""",
    """        internal fun isSafeRedirectSurface(urlOrAddress: String?): Boolean =\n            WebsiteRedirectDestination.current.matchesSurface(urlOrAddress)\n\n        internal fun supportsCapabilityBasedIntentRedirectFallback(\n            knownBrowser: Boolean,\n            verifiedHttpsHandler: Boolean\n        ): Boolean = knownBrowser || verifiedHttpsHandler\n\n        internal fun createSafeBrowserRedirectIntent(browserPackageName: String): Intent {\n            require(browserPackageName.isNotBlank())\n            return Intent(\n                Intent.ACTION_VIEW,\n                Uri.parse(WebsiteRedirectDestination.current.url)\n            ).apply {\n                addCategory(Intent.CATEGORY_BROWSABLE)\n                setPackage(browserPackageName)\n                addFlags(\n                    Intent.FLAG_ACTIVITY_NEW_TASK or\n                        Intent.FLAG_ACTIVITY_CLEAR_TOP or\n                        Intent.FLAG_ACTIVITY_SINGLE_TOP\n                )\n            }\n        }\n\n""",
)

# 4) Regression tests: lock the restored fallback, legacy same-window policy and
# known-good presentation timing in place.
coordinator_test = "app/src/test/java/com/focusguard/accessibility/website/redirection/WebsiteRedirectionCoordinatorTest.kt"
replace_once(
    coordinator_test,
    """    @Test\n    fun `restore failure is terminal fail closed`() = runBlocking {\n""",
    """    @Test\n    fun `same tab exhaustion uses legacy package scoped browser fallback`() = runBlocking {\n        val adapter = FakeAdapter(\n            prepareResults = ArrayDeque(listOf(false, false)),\n            externalRedirectResult = true\n        )\n        val outcome = WebsiteRedirectionCoordinator.execute(normalSession(), adapter)\n\n        assertThat(outcome).isEqualTo(WebsiteRedirectionCoordinator.Outcome.REDIRECT_CONFIRMED)\n        assertThat(adapter.prepareAttempts).containsExactly(1, 2).inOrder()\n        assertThat(adapter.externalRedirectCalls).isEqualTo(1)\n        assertThat(adapter.failClosedCalls).isEqualTo(0)\n        assertThat(adapter.releaseCalls).isEqualTo(1)\n    }\n\n    @Test\n    fun `legacy same window policy ignores unrelated transition timestamps`() {\n        val policy = WebsiteTabNeutralizationPolicy(\"com.android.chrome\", 7)\n\n        assertThat(\n            policy.mayActivateBlockedAddressBar(\n                activePackageName = \"com.android.chrome\",\n                activeWindowId = 7,\n                phaseStartedAtUptimeMillis = 100L,\n                latestWindowTransitionEventUptimeMillis = 999L\n            )\n        ).isTrue()\n        policy.markSafeAddressSet(101L)\n        assertThat(\n            policy.maySubmitSafeAddress(\n                activePackageName = \"com.android.chrome\",\n                activeWindowId = 7,\n                latestWindowTransitionEventUptimeMillis = 999L\n            )\n        ).isTrue()\n    }\n\n    @Test\n    fun `restore failure is terminal fail closed`() = runBlocking {\n""",
)
replace_once(
    coordinator_test,
    """        private val strictDestinationResult: Boolean = true,\n        private val loseOwnershipAfterPrepare: Boolean = false,\n""",
    """        private val strictDestinationResult: Boolean = true,\n        private val externalRedirectResult: Boolean = false,\n        private val loseOwnershipAfterPrepare: Boolean = false,\n""",
)
replace_once(
    coordinator_test,
    """        var releaseCalls = 0\n        var failClosedCalls = 0\n""",
    """        var releaseCalls = 0\n        var failClosedCalls = 0\n        var externalRedirectCalls = 0\n""",
)
replace_once(
    coordinator_test,
    """        override suspend fun awaitRedirectConfirmation(): Boolean {\n            confirmCalls += 1\n            return confirmResults.removeFirstOrNull() ?: false\n        }\n\n        override suspend fun completeStrictDestination(): Boolean = strictDestinationResult\n""",
    """        override suspend fun awaitRedirectConfirmation(): Boolean {\n            confirmCalls += 1\n            return confirmResults.removeFirstOrNull() ?: false\n        }\n\n        override suspend fun requestExternalBrowserRedirect(): Boolean {\n            externalRedirectCalls += 1\n            return externalRedirectResult\n        }\n\n        override suspend fun completeStrictDestination(): Boolean = strictDestinationResult\n""",
)

replace_once(
    "app/src/test/java/com/focusguard/accessibility/website/redirection/WebsiteRedirectionPlanTest.kt",
    """    @Test\n    fun `submit alternatives are independent from whole redirect attempts`() {\n""",
    """    @Test\n    fun `legacy package scoped browser intent fallback is enabled`() {\n        assertThat(WebsiteRedirectionPlan.ALLOW_EXTERNAL_BROWSER_INTENT_FALLBACK).isTrue()\n    }\n\n    @Test\n    fun `submit alternatives are independent from whole redirect attempts`() {\n""",
)

navigation_test = "app/src/test/java/com/focusguard/service/WebsiteBlockNavigationTest.kt"
replace_once(
    navigation_test,
    """    fun `website curtain remains briefly visible while redirect starts immediately`() {\n        assertThat(BlockingAccessibilityService.WEBSITE_MIN_BLOCK_NOTICE_MILLIS)\n            .isEqualTo(250L)\n    }\n""",
    """    fun `website curtain keeps the known good one second handoff window`() {\n        assertThat(BlockingAccessibilityService.WEBSITE_MIN_BLOCK_NOTICE_MILLIS)\n            .isEqualTo(1_000L)\n    }\n\n    @Test\n    fun `legacy browser intent fallback is explicit package scoped and safe`() {\n        val intent = BlockingAccessibilityService.createSafeBrowserRedirectIntent(BRAVE_PACKAGE)\n\n        assertThat(intent.action).isEqualTo(Intent.ACTION_VIEW)\n        assertThat(intent.`package`).isEqualTo(BRAVE_PACKAGE)\n        assertThat(BlockingAccessibilityService.isSafeRedirectSurface(intent.data?.toString()))\n            .isTrue()\n        assertThat(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK).isNotEqualTo(0)\n        assertThat(intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP).isNotEqualTo(0)\n        assertThat(intent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP).isNotEqualTo(0)\n        assertThat(\n            BlockingAccessibilityService.supportsCapabilityBasedIntentRedirectFallback(\n                knownBrowser = true,\n                verifiedHttpsHandler = false\n            )\n        ).isTrue()\n    }\n""",
)

print("Restored 601f93e website blocking/redirect behavior in current architecture.")
