from pathlib import Path

SERVICE = Path("app/src/main/java/com/focusguard/service/BlockingAccessibilityService.kt")
TESTS = Path("app/src/test/java/com/focusguard/service/WebsiteBlockNavigationTest.kt")

service = SERVICE.read_text()
tests = TESTS.read_text()

if (
    "private const val WEBSITE_ADDRESS_BAR_ACTION_TIMEOUT_MILLIS = 360L" in service
    and "internal const val WEBSITE_MIN_BLOCK_NOTICE_MILLIS = 1_000L" in service
):
    print("Patch already applied.")
    raise SystemExit(0)


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


service = replace_once(
    service,
    """        fun onFailureOrTimeout(): WebsiteTransitionAction {
            check(state != State.FINISHED)
            state = State.FINISHED
            return WebsiteTransitionAction.EVACUATE_HOME
        }
""",
    """        fun onFailureOrTimeout(): WebsiteTransitionAction {
            check(state != State.FINISHED)
            state = State.FINISHED
            // Website blocking must never close the browser or evict it to HOME.
            // If same-tab sanitization cannot be confirmed, release only the curtain;
            // the still-blocked URL will be intercepted again on the next browser event.
            return WebsiteTransitionAction.HIDE_CURTAIN
        }
""",
    "website transition failure policy",
)

service = replace_once(
    service,
    """        fun mayActivateBlockedAddressBar(
            activePackageName: String,
            activeWindowId: Int,
            phaseStartedAtUptimeMillis: Long,
            latestWindowTransitionEventUptimeMillis: Long
        ): Boolean = state == State.BLOCKED_TAB &&
            BrowserUiCapabilityPolicy.isFreshExpectedSurface(
                expectedBrowserPackage = browserPackageName,
                expectedWindowId = expectedWindowId,
                activePackageName = activePackageName,
                activeWindowId = activeWindowId,
                phaseStartedAtUptimeMillis = phaseStartedAtUptimeMillis,
                latestWindowTransitionEventUptimeMillis =
                    latestWindowTransitionEventUptimeMillis
            )
""",
    """        fun mayActivateBlockedAddressBar(
            activePackageName: String,
            activeWindowId: Int,
            @Suppress("UNUSED_PARAMETER") phaseStartedAtUptimeMillis: Long,
            @Suppress("UNUSED_PARAMETER") latestWindowTransitionEventUptimeMillis: Long
        ): Boolean = state == State.BLOCKED_TAB &&
            activePackageName == browserPackageName &&
            activeWindowId == expectedWindowId
""",
    "address-bar activation policy",
)

service = replace_once(
    service,
    """        fun maySubmitSafeAddress(
            activePackageName: String,
            activeWindowId: Int,
            latestWindowTransitionEventUptimeMillis: Long
        ): Boolean =
            state == State.SAFE_ADDRESS_SET &&
                BrowserUiCapabilityPolicy.isFreshExpectedSurface(
                    expectedBrowserPackage = browserPackageName,
                    expectedWindowId = expectedWindowId,
                    activePackageName = activePackageName,
                    activeWindowId = activeWindowId,
                    phaseStartedAtUptimeMillis = safeAddressSetAtUptimeMillis,
                    latestWindowTransitionEventUptimeMillis =
                        latestWindowTransitionEventUptimeMillis
                )
""",
    """        fun maySubmitSafeAddress(
            activePackageName: String,
            activeWindowId: Int,
            @Suppress("UNUSED_PARAMETER") latestWindowTransitionEventUptimeMillis: Long
        ): Boolean =
            state == State.SAFE_ADDRESS_SET &&
                activePackageName == browserPackageName &&
                activeWindowId == expectedWindowId
""",
    "address-bar submit policy",
)

service = replace_once(
    service,
    """                // Keep the redirect in the current tab whenever the address bar is editable.
                // Otherwise restore the exact blocked surface and neutralize that tab before
                // requesting Google, so the blocked page cannot survive beside the safe page.
                if (!redirectRequested) {
                    val blockedSurfaceRestored =
                        restoreBlockedSurfaceAfterAddressEdit(transition)
                    redirectRequested = blockedSurfaceRestored &&
                        closeBlockedTabAndRequestSafeGoogle(
                            browserPackageName = browserPackageName,
                            expectedWindowId = expectedWindowId,
                            transition = transition
                        )
                }

                if (!redirectRequested) {
                    stateMachine.onFailureOrTimeout()
                    evacuateWebsiteTransition(curtainGeneration)
                    return@launch
                }
""",
    """                // Same-tab rewrite is the only website redirect path. Never close the
                // current tab/browser as a fallback: if the browser UI is temporarily late,
                // keep it open and let the next accessibility event retry the blocked URL.
                if (!redirectRequested) {
                    stateMachine.onFailureOrTimeout()
                    releaseWebsiteCurtainAfterMinimumNotice(
                        curtainGeneration = curtainGeneration,
                        curtainShownAtUptimeMillis = curtainShownAtUptimeMillis
                    )
                    return@launch
                }
""",
    "destructive redirect fallback",
)

service = replace_once(
    service,
    """                if (!googleConfirmed) {
                    stateMachine.onFailureOrTimeout()
                    evacuateWebsiteTransition(curtainGeneration)
                    return@launch
                }
""",
    """                if (!googleConfirmed) {
                    stateMachine.onFailureOrTimeout()
                    releaseWebsiteCurtainAfterMinimumNotice(
                        curtainGeneration = curtainGeneration,
                        curtainShownAtUptimeMillis = curtainShownAtUptimeMillis
                    )
                    return@launch
                }
""",
    "google confirmation timeout fallback",
)

service = replace_once(
    service,
    """                    WebsiteTransitionAction.HIDE_CURTAIN -> {
                        val visibleFor = SystemClock.uptimeMillis() -
                            curtainShownAtUptimeMillis
                        val remaining = WEBSITE_MIN_BLOCK_NOTICE_MILLIS - visibleFor
                        if (remaining > 0L) delay(remaining)
                        dismissInstantBlockCurtain(curtainGeneration)
                    }
""",
    """                    WebsiteTransitionAction.HIDE_CURTAIN -> {
                        releaseWebsiteCurtainAfterMinimumNotice(
                            curtainGeneration = curtainGeneration,
                            curtainShownAtUptimeMillis = curtainShownAtUptimeMillis
                        )
                    }
""",
    "successful curtain release",
)

old_failure = """                            stateMachine.onFailureOrTimeout()
                            evacuateWebsiteTransition(curtainGeneration)
"""
count = service.count(old_failure)
if count != 1:
    raise RuntimeError(f"pomodoro/transition failure paths: expected 1 match, found {count}")
service = service.replace(
    old_failure,
    """                            stateMachine.onFailureOrTimeout()
                            releaseWebsiteCurtainAfterMinimumNotice(
                                curtainGeneration = curtainGeneration,
                                curtainShownAtUptimeMillis = curtainShownAtUptimeMillis
                            )
""",
)

service = replace_once(
    service,
    """    private suspend fun awaitNextWebsiteRedirectFrame() {
""",
    """    private suspend fun releaseWebsiteCurtainAfterMinimumNotice(
        curtainGeneration: Long,
        curtainShownAtUptimeMillis: Long
    ) {
        val visibleFor = SystemClock.uptimeMillis() - curtainShownAtUptimeMillis
        val remaining = WEBSITE_MIN_BLOCK_NOTICE_MILLIS - visibleFor
        if (remaining > 0L) delay(remaining)
        dismissInstantBlockCurtain(curtainGeneration)
    }

    private suspend fun awaitNextWebsiteRedirectFrame() {
""",
    "minimum curtain helper",
)

service = replace_once(
    service,
    """        delay(WEBSITE_ADDRESS_BAR_FOCUS_SETTLE_MILLIS)
        if (!curtainReadyForTransition(transition) ||
            transition.latestWindowTransitionEventUptimeMillis > activationRequestedAt
        ) return 0L
        val editRoot = activeBrowserRoot(browserPackageName, expectedWindowId) ?: return 0L
        if (!policy.mayTouchBlockedTab(
                activePackageName = editRoot.packageName?.toString().orEmpty(),
                activeWindowId = editRoot.windowId
            ) || !rootStillShowsDetectedBlockedTarget(editRoot, transition)
        ) {
            recycleSafely(editRoot)
            return 0L
        }
        val arguments = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                SAFE_REDIRECT_URL
            )
        }
        val setRequestedAt = SystemClock.uptimeMillis()
        val replaced = WebsiteBlocker.performUniqueAddressBarAction(
            root = editRoot,
            browserPackageName = browserPackageName,
            expectedWindowId = expectedWindowId,
            requiredAction = BrowserUiCapabilityPolicy.NodeAction.SET_TEXT,
            arguments = arguments
        )
        recycleSafely(editRoot)
        if (!replaced.accepted) return 0L
        transition.editorAddressViewId = replaced.selectedViewId
        return setRequestedAt
""",
    """        delay(WEBSITE_ADDRESS_BAR_FOCUS_SETTLE_MILLIS)
        val editDeadline = SystemClock.uptimeMillis() +
            WEBSITE_ADDRESS_BAR_ACTION_TIMEOUT_MILLIS
        while (curtainReadyForTransition(transition) &&
            SystemClock.uptimeMillis() <= editDeadline
        ) {
            val editRoot = activeBrowserRoot(browserPackageName, expectedWindowId)
            if (editRoot != null) {
                if (!policy.mayTouchBlockedTab(
                        activePackageName = editRoot.packageName?.toString().orEmpty(),
                        activeWindowId = editRoot.windowId
                    )
                ) {
                    recycleSafely(editRoot)
                    return 0L
                }
                val arguments = Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        SAFE_REDIRECT_URL
                    )
                }
                val setRequestedAt = SystemClock.uptimeMillis()
                val replaced = WebsiteBlocker.performUniqueAddressBarAction(
                    root = editRoot,
                    browserPackageName = browserPackageName,
                    expectedWindowId = expectedWindowId,
                    requiredAction = BrowserUiCapabilityPolicy.NodeAction.SET_TEXT,
                    arguments = arguments
                )
                recycleSafely(editRoot)
                if (replaced.accepted) {
                    transition.editorAddressViewId = replaced.selectedViewId
                    return setRequestedAt
                }
            }
            delay(WEBSITE_ADDRESS_BAR_ACTION_RETRY_MILLIS)
        }
        return 0L
""",
    "address-bar set-text retry",
)

service = replace_once(
    service,
    """    private fun submitSafeAddressBar(
        browserPackageName: String,
        expectedWindowId: Int,
        policy: WebsiteTabNeutralizationPolicy,
        transition: WebsiteBlockTransitionHandle
    ): Long {
        if (!canUseCertifiableImeSubmit(Build.VERSION.SDK_INT) ||
            !curtainReadyForTransition(transition)
        ) return 0L
        val root = activeBrowserRoot(browserPackageName, expectedWindowId) ?: return 0L
        if (!policy.maySubmitSafeAddress(
                activePackageName = root.packageName?.toString().orEmpty(),
                activeWindowId = root.windowId,
                latestWindowTransitionEventUptimeMillis =
                    transition.latestWindowTransitionEventUptimeMillis
            )
        ) {
            recycleSafely(root)
            return 0L
        }
        val submitRequestedAt = SystemClock.uptimeMillis()
        val submitted = WebsiteBlocker.performUniqueAddressBarAction(
            root = root,
            browserPackageName = browserPackageName,
            expectedWindowId = expectedWindowId,
            requiredAction = BrowserUiCapabilityPolicy.NodeAction.IME_ENTER,
            textPredicate = ::isSafeGoogleRedirectSurface
        )
        recycleSafely(root)
        return if (submitted.accepted) submitRequestedAt else 0L
    }
""",
    """    private suspend fun submitSafeAddressBar(
        browserPackageName: String,
        expectedWindowId: Int,
        policy: WebsiteTabNeutralizationPolicy,
        transition: WebsiteBlockTransitionHandle
    ): Long {
        if (!canUseCertifiableImeSubmit(Build.VERSION.SDK_INT) ||
            !curtainReadyForTransition(transition)
        ) return 0L
        val submitDeadline = SystemClock.uptimeMillis() +
            WEBSITE_ADDRESS_BAR_ACTION_TIMEOUT_MILLIS
        while (curtainReadyForTransition(transition) &&
            SystemClock.uptimeMillis() <= submitDeadline
        ) {
            val root = activeBrowserRoot(browserPackageName, expectedWindowId)
            if (root != null) {
                if (!policy.maySubmitSafeAddress(
                        activePackageName = root.packageName?.toString().orEmpty(),
                        activeWindowId = root.windowId,
                        latestWindowTransitionEventUptimeMillis =
                            transition.latestWindowTransitionEventUptimeMillis
                    )
                ) {
                    recycleSafely(root)
                    return 0L
                }
                val submitRequestedAt = SystemClock.uptimeMillis()
                val submitted = WebsiteBlocker.performUniqueAddressBarAction(
                    root = root,
                    browserPackageName = browserPackageName,
                    expectedWindowId = expectedWindowId,
                    requiredAction = BrowserUiCapabilityPolicy.NodeAction.IME_ENTER,
                    textPredicate = ::isSafeGoogleRedirectSurface
                )
                recycleSafely(root)
                if (submitted.accepted) return submitRequestedAt
            }
            delay(WEBSITE_ADDRESS_BAR_ACTION_RETRY_MILLIS)
        }
        return 0L
    }
""",
    "address-bar submit retry",
)

service = replace_once(
    service,
    """        private const val WEBSITE_ADDRESS_BAR_FOCUS_SETTLE_MILLIS = 48L
        private const val WEBSITE_GOOGLE_SURFACE_SETTLE_MILLIS = 120L
        internal const val WEBSITE_MIN_BLOCK_NOTICE_MILLIS = 600L
""",
    """        private const val WEBSITE_ADDRESS_BAR_FOCUS_SETTLE_MILLIS = 48L
        private const val WEBSITE_ADDRESS_BAR_ACTION_RETRY_MILLIS = 32L
        private const val WEBSITE_ADDRESS_BAR_ACTION_TIMEOUT_MILLIS = 360L
        private const val WEBSITE_GOOGLE_SURFACE_SETTLE_MILLIS = 120L
        internal const val WEBSITE_MIN_BLOCK_NOTICE_MILLIS = 1_000L
""",
    "website redirect timing constants",
)

tests = replace_once(
    tests,
    """    fun `website curtain remains visible while redirect starts immediately`() {
        assertThat(BlockingAccessibilityService.WEBSITE_MIN_BLOCK_NOTICE_MILLIS)
            .isAtLeast(400L)
        assertThat(BlockingAccessibilityService.WEBSITE_MIN_BLOCK_NOTICE_MILLIS)
            .isAtMost(1_000L)
    }
""",
    """    fun `website curtain remains visible for one second while redirect starts immediately`() {
        assertThat(BlockingAccessibilityService.WEBSITE_MIN_BLOCK_NOTICE_MILLIS)
            .isEqualTo(1_000L)
    }
""",
    "one-second curtain test",
)

tests = replace_once(
    tests,
    """        assertThat(
            policy.mayAttemptChromiumClose(
                arbitraryChromiumPackage,
                activeWindowId = 7,
                phaseStartedAtUptimeMillis = 100L,
                latestWindowTransitionEventUptimeMillis = 101L
            )
        ).isFalse()
        assertThat(policy.mayTouchBlockedTab(arbitraryChromiumPackage, 8)).isFalse()
""",
    """        assertThat(
            policy.mayAttemptChromiumClose(
                arbitraryChromiumPackage,
                activeWindowId = 7,
                phaseStartedAtUptimeMillis = 100L,
                latestWindowTransitionEventUptimeMillis = 101L
            )
        ).isFalse()
        assertThat(
            policy.mayActivateBlockedAddressBar(
                arbitraryChromiumPackage,
                activeWindowId = 7,
                phaseStartedAtUptimeMillis = 100L,
                latestWindowTransitionEventUptimeMillis = 101L
            )
        ).isTrue()
        assertThat(policy.mayTouchBlockedTab(arbitraryChromiumPackage, 8)).isFalse()
""",
    "same-window activation test",
)

tests = replace_once(
    tests,
    """        assertThat(
            policy.maySubmitSafeAddress(
                arbitraryChromiumPackage,
                activeWindowId = 7,
                latestWindowTransitionEventUptimeMillis = 201L
            )
        ).isFalse()
""",
    """        assertThat(
            policy.maySubmitSafeAddress(
                arbitraryChromiumPackage,
                activeWindowId = 7,
                latestWindowTransitionEventUptimeMillis = 201L
            )
        ).isTrue()
""",
    "same-window submit test",
)

tests = replace_once(
    tests,
    """    fun `confirmation timeout evacuates Home without hiding directly`() {
        val machine = BlockingAccessibilityService.WebsiteBlockTransitionStateMachine(
            strict = false
        )
        machine.begin()

        assertThat(machine.onFailureOrTimeout()).isEqualTo(
            BlockingAccessibilityService.WebsiteTransitionAction.EVACUATE_HOME
        )
    }

    @Test
    fun `sanitization or destination failure evacuates Home`() {
        val sanitizationFailure = BlockingAccessibilityService.WebsiteBlockTransitionStateMachine(
            strict = false
        )
        sanitizationFailure.begin()
        assertThat(sanitizationFailure.onFailureOrTimeout()).isEqualTo(
            BlockingAccessibilityService.WebsiteTransitionAction.EVACUATE_HOME
        )

        val launchFailure = BlockingAccessibilityService.WebsiteBlockTransitionStateMachine(
            strict = false
        )
        launchFailure.begin()
        assertThat(launchFailure.onFailureOrTimeout()).isEqualTo(
            BlockingAccessibilityService.WebsiteTransitionAction.EVACUATE_HOME
        )
    }
""",
    """    fun `confirmation timeout keeps the browser open and releases only the curtain`() {
        val machine = BlockingAccessibilityService.WebsiteBlockTransitionStateMachine(
            strict = false
        )
        machine.begin()

        assertThat(machine.onFailureOrTimeout()).isEqualTo(
            BlockingAccessibilityService.WebsiteTransitionAction.HIDE_CURTAIN
        )
    }

    @Test
    fun `sanitization or destination failure never requests browser eviction`() {
        val sanitizationFailure = BlockingAccessibilityService.WebsiteBlockTransitionStateMachine(
            strict = false
        )
        sanitizationFailure.begin()
        assertThat(sanitizationFailure.onFailureOrTimeout()).isEqualTo(
            BlockingAccessibilityService.WebsiteTransitionAction.HIDE_CURTAIN
        )

        val launchFailure = BlockingAccessibilityService.WebsiteBlockTransitionStateMachine(
            strict = false
        )
        launchFailure.begin()
        assertThat(launchFailure.onFailureOrTimeout()).isEqualTo(
            BlockingAccessibilityService.WebsiteTransitionAction.HIDE_CURTAIN
        )
    }
""",
    "non-destructive failure tests",
)

SERVICE.write_text(service)
TESTS.write_text(tests)
print("Patch applied.")
