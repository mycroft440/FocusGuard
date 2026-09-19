from pathlib import Path


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(
            f"Expected exactly one match in {path}, found {count}: {old[:120]!r}"
        )
    path.write_text(text.replace(old, new, 1))


actions = Path("app/src/main/java/com/focusguard/accessibility/website/redirection/AddressBarRedirectionActions.kt")
coordinator = Path("app/src/main/java/com/focusguard/accessibility/website/redirection/WebsiteRedirectionCoordinator.kt")
plan = Path("app/src/main/java/com/focusguard/accessibility/website/redirection/WebsiteRedirectionPlan.kt")
service = Path("app/src/main/java/com/focusguard/service/BlockingAccessibilityService.kt")
actions_test = Path("app/src/test/java/com/focusguard/accessibility/website/redirection/AddressBarRedirectionActionsPolicyTest.kt")
coordinator_test = Path("app/src/test/java/com/focusguard/accessibility/website/redirection/WebsiteRedirectionCoordinatorTest.kt")
plan_test = Path("app/src/test/java/com/focusguard/accessibility/website/redirection/WebsiteRedirectionPlanTest.kt")

replace_once(
    actions,
    """    internal fun canUseEditorForCertifiedSubmission(
        editable: Boolean,
        focused: Boolean,
        textCertified: Boolean
    ): Boolean = editable && (focused || textCertified)
""",
    """    internal fun canUseEditorForCertifiedSubmission(
        editable: Boolean,
        focused: Boolean,
        textCertified: Boolean
    ): Boolean = editable && (focused || textCertified)

    internal fun isActiveAddressEdit(
        editable: Boolean,
        focused: Boolean
    ): Boolean = editable && focused
""",
)
replace_once(
    actions,
    """    /**
     * The historical name is retained because callers use this as their submitter
     * guard. With no text predicate it still requires focus. When an exact redirect
     * predicate is supplied, an unfocused editor is accepted only while it still
     * contains that certified replacement address.
     */
""",
    """    /** Active editing requires real focus, regardless of the current address text. */
""",
)
replace_once(
    actions,
    """                val exactTextCertified = textPredicate != null && textMatches
                canUseEditorForCertifiedSubmission(
                    editable = fact.editable,
                    focused = fact.focused,
                    textCertified = exactTextCertified
                ) &&
""",
    """                isActiveAddressEdit(
                    editable = fact.editable,
                    focused = fact.focused
                ) &&
""",
)
replace_once(
    actions,
    """    fun selectAll(
""",
    """    /**
     * Submission may continue after a browser collapses focus only when the exact
     * safe replacement address is still present in a unique actionable editor.
     */
    fun hasCertifiedAddressEditor(
        root: AccessibilityNodeInfo,
        browserPackageName: String,
        expectedWindowId: Int,
        httpsHandlerRecognized: Boolean,
        textPredicate: (String?) -> Boolean,
        requireUnique: Boolean = true
    ): Boolean {
        val nodes = collectAddressBarNodes(
            root, browserPackageName, expectedWindowId, httpsHandlerRecognized
        )
        return try {
            nodes.count { node ->
                val fact = node.toFact()
                val textMatches = textPredicate(fact.text)
                canUseEditorForCertifiedSubmission(
                    editable = fact.editable,
                    focused = fact.focused,
                    textCertified = textMatches
                ) &&
                    BrowserUiCapabilityPolicy.isActionableAddressBarNode(
                        fact, browserPackageName, expectedWindowId, httpsHandlerRecognized
                    ) && textMatches
            }.let { count -> if (requireUnique) count == 1 else count > 0 }
        } finally { nodes.forEach(::recycleSafely) }
    }

    fun selectAll(
""",
)

replace_once(
    plan,
    """internal object WebsiteRedirectionPlan {
    const val MAX_SAME_TAB_ATTEMPTS = 2
""",
    """internal object WebsiteRedirectionPlan {
    const val MAX_SAME_TAB_ATTEMPTS = 2
    const val MAX_SUBMIT_ALTERNATIVES = 3
    const val DESTINATION_CONFIRM_TIMEOUT_MILLIS = 2_000L

    private const val PREPARE_AND_RECOVERY_BUDGET_PER_ATTEMPT_MILLIS = 4_000L
    private const val CURTAIN_FAILSAFE_MARGIN_MILLIS = 1_000L

    const val CURTAIN_FAILSAFE_MILLIS =
        MAX_SAME_TAB_ATTEMPTS * (
            MAX_SUBMIT_ALTERNATIVES * DESTINATION_CONFIRM_TIMEOUT_MILLIS +
                PREPARE_AND_RECOVERY_BUDGET_PER_ATTEMPT_MILLIS
            ) + CURTAIN_FAILSAFE_MARGIN_MILLIS
""",
)

replace_once(
    coordinator,
    """            val submitted = prepared && adapter.submitSameTabRedirect(attemptNumber)
            if (!adapter.ownsProtection()) return Outcome.ABORTED

            if (submitted && adapter.awaitRedirectConfirmation()) {
                if (!adapter.ownsProtection()) return Outcome.ABORTED
                return completeConfirmedRedirect(session, adapter)
            }
            if (!adapter.ownsProtection()) return Outcome.ABORTED
""",
    """            if (prepared) {
                var submitAlternativeNumber = 1
                while (submitAlternativeNumber <= WebsiteRedirectionPlan.MAX_SUBMIT_ALTERNATIVES) {
                    val submitted = adapter.submitSameTabRedirect(attemptNumber)
                    if (!adapter.ownsProtection()) return Outcome.ABORTED

                    if (submitted && adapter.awaitRedirectConfirmation()) {
                        if (!adapter.ownsProtection()) return Outcome.ABORTED
                        return completeConfirmedRedirect(session, adapter)
                    }
                    if (!adapter.ownsProtection()) return Outcome.ABORTED
                    submitAlternativeNumber += 1
                }
            }
""",
)
replace_once(
    coordinator,
    """            if (!adapter.restoreBlockedSurfaceForRetry()) {
                if (!adapter.ownsProtection()) return Outcome.ABORTED
                return failClosed(session, adapter)
            }
""",
    """            if (!adapter.restoreBlockedSurfaceForRetry()) {
                if (!adapter.ownsProtection()) return Outcome.ABORTED
                if (adapter.awaitRedirectConfirmation()) {
                    if (!adapter.ownsProtection()) return Outcome.ABORTED
                    return completeConfirmedRedirect(session, adapter)
                }
                if (!adapter.ownsProtection()) return Outcome.ABORTED
                return failClosed(session, adapter)
            }
""",
)
replace_once(
    coordinator,
    """    fun maySubmitSafeAddress(
        activePackageName: String,
        activeWindowId: Int,
        latestWindowTransitionEventUptimeMillis: Long
    ): Boolean = state == State.SAFE_ADDRESS_SET &&
        safeAddressSetAtUptimeMillis > 0L &&
        latestWindowTransitionEventUptimeMillis <= safeAddressSetAtUptimeMillis &&
        activePackageName == browserPackageName &&
        activeWindowId == expectedWindowId

    fun markRedirectRequested() {
        check(state == State.SAFE_ADDRESS_SET)
        state = State.REDIRECT_REQUESTED
    }
""",
    """    fun maySubmitSafeAddress(
        activePackageName: String,
        activeWindowId: Int,
        latestWindowTransitionEventUptimeMillis: Long
    ): Boolean = safeAddressSetAtUptimeMillis > 0L &&
        activePackageName == browserPackageName &&
        activeWindowId == expectedWindowId &&
        when (state) {
            State.SAFE_ADDRESS_SET ->
                latestWindowTransitionEventUptimeMillis <= safeAddressSetAtUptimeMillis
            State.REDIRECT_REQUESTED -> true
            State.BLOCKED_TAB -> false
        }

    fun markRedirectRequested() {
        check(state == State.SAFE_ADDRESS_SET || state == State.REDIRECT_REQUESTED)
        state = State.REDIRECT_REQUESTED
    }
""",
)

replace_once(
    service,
    "import com.focusguard.accessibility.website.redirection.WebsiteRedirectionCoordinator\n",
    "import com.focusguard.accessibility.website.redirection.WebsiteRedirectionCoordinator\n"
    "import com.focusguard.accessibility.website.redirection.WebsiteRedirectionPlan\n",
)
replace_once(
    service,
    """                        AddressBarRedirectionActions.hasFocusedAddressEditor(fresh, browserPackageName,
                            expectedWindowId, https, ::isSafeRedirectSurface)
""",
    """                        AddressBarRedirectionActions.hasCertifiedAddressEditor(fresh, browserPackageName,
                            expectedWindowId, https, ::isSafeRedirectSurface)
""",
)
replace_once(
    service,
    """                    !AddressBarRedirectionActions.hasFocusedAddressEditor(
                        root, browserPackageName, expectedWindowId, https, ::isSafeRedirectSurface
                    )
""",
    """                    !AddressBarRedirectionActions.hasCertifiedAddressEditor(
                        root, browserPackageName, expectedWindowId, https, ::isSafeRedirectSurface
                    )
""",
)
replace_once(
    service,
    """        if (transition.activatedAddressViewId == null && transition.editorAddressViewId == null) return true

        // BACK is allowed only while the exact safe URL is still being edited.
""",
    """        if (transition.activatedAddressViewId == null && transition.editorAddressViewId == null) return true
        if (transition.safeRedirectConfirmed.isCompleted ||
            websiteTreeWorker.run { confirmSafeRedirectFromFreshBrowserSurface(transition) }
        ) return false
        if (!curtainReadyForTransition(transition)) return false

        // BACK is allowed only while the exact safe URL is still being edited.
""",
)
replace_once(
    service,
    """        if (!performTransitionBack(transition)) return false
""",
    """        if (transition.safeRedirectConfirmed.isCompleted ||
            websiteTreeWorker.run { confirmSafeRedirectFromFreshBrowserSurface(transition) }
        ) return false
        if (!curtainReadyForTransition(transition)) return false
        if (!performTransitionBack(transition)) return false
""",
)
replace_once(
    service,
    """    private fun showWebsiteBlockPresentation(blockedCandidate: String?): Long {
        val generation = showInstantBlockCurtain(mode = CurtainMode.BLOCK_NOTICE)
""",
    """    private fun showWebsiteBlockPresentation(blockedCandidate: String?): Long {
        val generation = showInstantBlockCurtain(mode = CurtainMode.BLOCK_NOTICE)
        renewInstantCurtainFailsafe(WebsiteRedirectionPlan.CURTAIN_FAILSAFE_MILLIS)
""",
)
replace_once(
    service,
    """    private fun renewInstantCurtainFailsafe() {
        mainHandler.removeCallbacks(instantCurtainFailsafe)
        mainHandler.postDelayed(instantCurtainFailsafe, INSTANT_CURTAIN_FAILSAFE_MILLIS)
    }
""",
    """    private fun renewInstantCurtainFailsafe(
        timeoutMillis: Long = INSTANT_CURTAIN_FAILSAFE_MILLIS
    ) {
        mainHandler.removeCallbacks(instantCurtainFailsafe)
        mainHandler.postDelayed(instantCurtainFailsafe, timeoutMillis)
    }
""",
)
replace_once(
    service,
    "        internal const val WEBSITE_DESTINATION_CONFIRM_TIMEOUT_MILLIS = 2_000L\n",
    "        internal const val WEBSITE_DESTINATION_CONFIRM_TIMEOUT_MILLIS =\n"
    "            WebsiteRedirectionPlan.DESTINATION_CONFIRM_TIMEOUT_MILLIS\n",
)

replace_once(
    actions_test,
    """    @Test
    fun `non editable node is rejected even when redirect text is certified`() {
""",
    """    @Test
    fun `active editing requires actual focus`() {
        assertThat(
            AddressBarRedirectionActions.isActiveAddressEdit(
                editable = true,
                focused = false
            )
        ).isFalse()
        assertThat(
            AddressBarRedirectionActions.isActiveAddressEdit(
                editable = true,
                focused = true
            )
        ).isTrue()
    }

    @Test
    fun `non editable node is rejected even when redirect text is certified`() {
""",
)

replace_once(
    coordinator_test,
    """    @Test
    fun `confirmation timeout retries whole same tab transaction once`() = runBlocking {
        val adapter = FakeAdapter(confirmResults = ArrayDeque(listOf(false, true)))
        val outcome = WebsiteRedirectionCoordinator.execute(normalSession(), adapter)

        assertThat(outcome).isEqualTo(WebsiteRedirectionCoordinator.Outcome.REDIRECT_CONFIRMED)
        assertThat(adapter.prepareAttempts).containsExactly(1, 2).inOrder()
        assertThat(adapter.submitAttempts).containsExactly(1, 2).inOrder()
        assertThat(adapter.confirmCalls).isEqualTo(2)
        assertThat(adapter.restoreCalls).isEqualTo(1)
        assertThat(adapter.failClosedCalls).isEqualTo(0)
    }
""",
    """    @Test
    fun `confirmation timeout advances submit alternative before whole retry`() = runBlocking {
        val adapter = FakeAdapter(confirmResults = ArrayDeque(listOf(false, true)))
        val outcome = WebsiteRedirectionCoordinator.execute(normalSession(), adapter)

        assertThat(outcome).isEqualTo(WebsiteRedirectionCoordinator.Outcome.REDIRECT_CONFIRMED)
        assertThat(adapter.prepareAttempts).containsExactly(1)
        assertThat(adapter.submitAttempts).containsExactly(1, 1).inOrder()
        assertThat(adapter.confirmCalls).isEqualTo(2)
        assertThat(adapter.restoreCalls).isEqualTo(0)
        assertThat(adapter.failClosedCalls).isEqualTo(0)
    }
""",
)
replace_once(
    coordinator_test,
    """    @Test
    fun `submit failure retries from restored blocked surface`() = runBlocking {
        val adapter = FakeAdapter(submitResults = ArrayDeque(listOf(false, true)))
        val outcome = WebsiteRedirectionCoordinator.execute(normalSession(), adapter)

        assertThat(outcome).isEqualTo(WebsiteRedirectionCoordinator.Outcome.REDIRECT_CONFIRMED)
        assertThat(adapter.submitAttempts).containsExactly(1, 2).inOrder()
        assertThat(adapter.restoreCalls).isEqualTo(1)
    }
""",
    """    @Test
    fun `submit failure advances another submit alternative before restoring`() = runBlocking {
        val adapter = FakeAdapter(submitResults = ArrayDeque(listOf(false, true)))
        val outcome = WebsiteRedirectionCoordinator.execute(normalSession(), adapter)

        assertThat(outcome).isEqualTo(WebsiteRedirectionCoordinator.Outcome.REDIRECT_CONFIRMED)
        assertThat(adapter.submitAttempts).containsExactly(1, 1).inOrder()
        assertThat(adapter.restoreCalls).isEqualTo(0)
    }

    @Test
    fun `all three submit alternatives are exhausted before consuming whole retry`() = runBlocking {
        val adapter = FakeAdapter(
            submitResults = ArrayDeque(listOf(true, true, true, true)),
            confirmResults = ArrayDeque(listOf(false, false, false, true))
        )
        val outcome = WebsiteRedirectionCoordinator.execute(normalSession(), adapter)

        assertThat(outcome).isEqualTo(WebsiteRedirectionCoordinator.Outcome.REDIRECT_CONFIRMED)
        assertThat(adapter.prepareAttempts).containsExactly(1, 2).inOrder()
        assertThat(adapter.submitAttempts).containsExactly(1, 1, 1, 2).inOrder()
        assertThat(adapter.confirmCalls).isEqualTo(4)
        assertThat(adapter.restoreCalls).isEqualTo(1)
    }

    @Test
    fun `delayed confirmation discovered during recovery completes redirect`() = runBlocking {
        val adapter = FakeAdapter(
            submitResults = ArrayDeque(listOf(true, true, true)),
            confirmResults = ArrayDeque(listOf(false, false, false, true)),
            restoreResult = false
        )
        val outcome = WebsiteRedirectionCoordinator.execute(normalSession(), adapter)

        assertThat(outcome).isEqualTo(WebsiteRedirectionCoordinator.Outcome.REDIRECT_CONFIRMED)
        assertThat(adapter.restoreCalls).isEqualTo(1)
        assertThat(adapter.confirmCalls).isEqualTo(4)
        assertThat(adapter.failClosedCalls).isEqualTo(0)
    }

    @Test
    fun `redirect requested state allows the next certified submit alternative`() {
        val policy = WebsiteTabNeutralizationPolicy("org.mozilla.firefox", 7)
        policy.markSafeAddressSet(100L)
        assertThat(policy.maySubmitSafeAddress("org.mozilla.firefox", 7, 90L)).isTrue()

        policy.markRedirectRequested()

        assertThat(policy.maySubmitSafeAddress("org.mozilla.firefox", 7, 150L)).isTrue()
        policy.markRedirectRequested()
    }
""",
)

replace_once(
    plan_test,
    """    @Test
    fun `only one same tab retry is allowed before fail closed`() {
        assertThat(WebsiteRedirectionPlan.MAX_SAME_TAB_ATTEMPTS).isEqualTo(2)
        assertThat(WebsiteRedirectionPlan.canRetry(1)).isTrue()
        assertThat(WebsiteRedirectionPlan.canRetry(2)).isFalse()
    }
""",
    """    @Test
    fun `only one same tab retry is allowed before fail closed`() {
        assertThat(WebsiteRedirectionPlan.MAX_SAME_TAB_ATTEMPTS).isEqualTo(2)
        assertThat(WebsiteRedirectionPlan.canRetry(1)).isTrue()
        assertThat(WebsiteRedirectionPlan.canRetry(2)).isFalse()
    }

    @Test
    fun `submit alternatives are independent from whole redirect attempts`() {
        assertThat(WebsiteRedirectionPlan.MAX_SUBMIT_ALTERNATIVES).isEqualTo(3)
    }

    @Test
    fun `website curtain budget exceeds all confirmation windows`() {
        val confirmationBudget =
            WebsiteRedirectionPlan.MAX_SAME_TAB_ATTEMPTS *
                WebsiteRedirectionPlan.MAX_SUBMIT_ALTERNATIVES *
                WebsiteRedirectionPlan.DESTINATION_CONFIRM_TIMEOUT_MILLIS

        assertThat(WebsiteRedirectionPlan.CURTAIN_FAILSAFE_MILLIS)
            .isGreaterThan(confirmationBudget)
        assertThat(WebsiteRedirectionPlan.CURTAIN_FAILSAFE_MILLIS).isGreaterThan(5_000L)
    }
""",
)
