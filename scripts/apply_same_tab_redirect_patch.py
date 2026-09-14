from pathlib import Path

SERVICE = Path("app/src/main/java/com/focusguard/service/BlockingAccessibilityService.kt")
TESTS = Path("app/src/test/java/com/focusguard/service/WebsiteBlockNavigationTest.kt")

service = SERVICE.read_text()
tests = TESTS.read_text()


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


service = replace_once(
    service,
    """    internal enum class WebsiteTransitionAction {
        SHOW_CURTAIN,
        NEUTRALIZE_BLOCKED_TAB,
        OPEN_POMODORO,
        HIDE_CURTAIN,
        EVACUATE_HOME
    }

    internal enum class WebsiteSanitizationDecision {
        SUBMIT_ADDRESS_BAR,
        AWAIT_GOOGLE_CONFIRMATION,
        EVACUATE_HOME
    }
""",
    """    internal enum class WebsiteTransitionAction {
        SHOW_CURTAIN,
        NEUTRALIZE_BLOCKED_TAB,
        OPEN_POMODORO,
        HIDE_CURTAIN
    }

    internal enum class WebsiteSanitizationDecision {
        SUBMIT_ADDRESS_BAR,
        AWAIT_GOOGLE_CONFIRMATION,
        ABORT_REDIRECT
    }
""",
    "website transition enums",
)

service = replace_once(
    service,
    """    /** Keeps destructive browser UI actions ahead of the safe redirect request. */
""",
    """    /** Keeps same-tab address-bar actions bound to the detected browser window. */
""",
    "website rewrite policy comment",
)

service = replace_once(
    service,
    """        private var state = State.BLOCKED_TAB
        private var safeAddressSetAtUptimeMillis = 0L
""",
    """        private var state = State.BLOCKED_TAB
""",
    "unused safe-address timestamp",
)

service = replace_once(
    service,
    """        fun markSafeAddressSet(setAtUptimeMillis: Long) {
            check(state == State.BLOCKED_TAB)
            check(setAtUptimeMillis > 0L)
            safeAddressSetAtUptimeMillis = setAtUptimeMillis
            state = State.SAFE_ADDRESS_SET
        }
""",
    """        fun markSafeAddressSet(setAtUptimeMillis: Long) {
            check(state == State.BLOCKED_TAB)
            check(setAtUptimeMillis > 0L)
            state = State.SAFE_ADDRESS_SET
        }
""",
    "safe-address state transition",
)

service = replace_once(
    service,
    """                        ) {
                            stateMachine.onPomodoroConfirmed()
                            dismissInstantBlockCurtain(curtainGeneration)
                        } else {
""",
    """                        ) {
                            stateMachine.onPomodoroConfirmed()
                            releaseWebsiteCurtainAfterMinimumNotice(
                                curtainGeneration = curtainGeneration,
                                curtainShownAtUptimeMillis = curtainShownAtUptimeMillis
                            )
                        } else {
""",
    "strict destination curtain timing",
)

service = replace_once(
    service,
    """                    else -> {
                        stateMachine.onFailureOrTimeout()
                        evacuateWebsiteTransition(curtainGeneration)
                    }
""",
    """                    else -> {
                        stateMachine.onFailureOrTimeout()
                        releaseWebsiteCurtainAfterMinimumNotice(
                            curtainGeneration = curtainGeneration,
                            curtainShownAtUptimeMillis = curtainShownAtUptimeMillis
                        )
                    }
""",
    "last website transition fallback",
)

service = replace_once(
    service,
    """        val editDeadline = SystemClock.uptimeMillis() +
            WEBSITE_ADDRESS_BAR_ACTION_TIMEOUT_MILLIS
""",
    """        val editDeadline = activationRequestedAt +
            WEBSITE_ADDRESS_BAR_ACTION_TIMEOUT_MILLIS
""",
    "address-bar retry deadline",
)

service = replace_once(
    service,
    """    private suspend fun evacuateWebsiteTransition(expectedGeneration: Long) {
        if (curtainReadyForTabAction(
                attached = instantBlockCurtainAttached,
                visible = instantBlockCurtainVisible,
                currentGeneration = instantBlockCurtainGeneration,
                expectedGeneration = expectedGeneration
            )
        ) {
            beginCurtainEvacuationBeforeHide(expectedGeneration)
            awaitWebsiteCurtainEvacuation(expectedGeneration)
        } else {
            evictBlockedAppFromForeground(forceLauncherFallback = true)
        }
    }

    private suspend fun awaitWebsiteCurtainEvacuation(expectedGeneration: Long) {
        // The failsafe keeps requesting HOME/launcher while the browser remains a
        // visible unsafe window. Keep its per-browser guard alive until that exact
        // curtain is hidden or superseded; clearing it on a fixed timer could make
        // a late browser frame look safe.
        while (instantBlockCurtainGeneration == expectedGeneration &&
            instantBlockCurtainVisible
        ) {
            delay(UNSAFE_WINDOW_RECHECK_MILLIS)
        }
    }

""",
    "",
    "website browser-evacuation helpers",
)

service = replace_once(
    service,
    """        internal fun afterSafeAddressSet(
            accepted: Boolean
        ): WebsiteSanitizationDecision = if (accepted) {
            WebsiteSanitizationDecision.SUBMIT_ADDRESS_BAR
        } else {
            WebsiteSanitizationDecision.EVACUATE_HOME
        }

        internal fun afterSafeAddressSubmit(
            accepted: Boolean
        ): WebsiteSanitizationDecision = if (accepted) {
            WebsiteSanitizationDecision.AWAIT_GOOGLE_CONFIRMATION
        } else {
            WebsiteSanitizationDecision.EVACUATE_HOME
        }
""",
    """        internal fun afterSafeAddressSet(
            accepted: Boolean
        ): WebsiteSanitizationDecision = if (accepted) {
            WebsiteSanitizationDecision.SUBMIT_ADDRESS_BAR
        } else {
            WebsiteSanitizationDecision.ABORT_REDIRECT
        }

        internal fun afterSafeAddressSubmit(
            accepted: Boolean
        ): WebsiteSanitizationDecision = if (accepted) {
            WebsiteSanitizationDecision.AWAIT_GOOGLE_CONFIRMATION
        } else {
            WebsiteSanitizationDecision.ABORT_REDIRECT
        }
""",
    "website sanitization failure decision",
)

tests = replace_once(
    tests,
    """    fun `set or submit failure evacuates Home instead of ACTION_VIEW`() {
        assertThat(BlockingAccessibilityService.afterSafeAddressSet(false)).isEqualTo(
            BlockingAccessibilityService.WebsiteSanitizationDecision.EVACUATE_HOME
        )
        assertThat(BlockingAccessibilityService.afterSafeAddressSubmit(false)).isEqualTo(
            BlockingAccessibilityService.WebsiteSanitizationDecision.EVACUATE_HOME
        )
        assertThat(BlockingAccessibilityService.canUseCertifiableImeSubmit(29)).isFalse()
        assertThat(BlockingAccessibilityService.canUseCertifiableImeSubmit(30)).isTrue()
    }
""",
    """    fun `set or submit failure aborts redirect without closing or leaving browser`() {
        assertThat(BlockingAccessibilityService.afterSafeAddressSet(false)).isEqualTo(
            BlockingAccessibilityService.WebsiteSanitizationDecision.ABORT_REDIRECT
        )
        assertThat(BlockingAccessibilityService.afterSafeAddressSubmit(false)).isEqualTo(
            BlockingAccessibilityService.WebsiteSanitizationDecision.ABORT_REDIRECT
        )
        assertThat(BlockingAccessibilityService.canUseCertifiableImeSubmit(29)).isFalse()
        assertThat(BlockingAccessibilityService.canUseCertifiableImeSubmit(30)).isTrue()
    }

    @Test
    fun `website transition actions contain no browser eviction action`() {
        assertThat(
            BlockingAccessibilityService.WebsiteTransitionAction.values().map { it.name }
        ).doesNotContain("EVACUATE_HOME")
    }
""",
    "non-destructive sanitization test",
)

SERVICE.write_text(service)
TESTS.write_text(tests)
print("Final redirect cleanup applied.")
