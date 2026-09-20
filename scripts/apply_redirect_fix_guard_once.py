from pathlib import Path

coordinator_path = Path("app/src/main/java/com/focusguard/accessibility/website/redirection/WebsiteRedirectionCoordinator.kt")
text = coordinator_path.read_text()

old = """            if (prepared) {
                var submitAlternativeNumber = 1
                while (submitAlternativeNumber <= WebsiteRedirectionPlan.MAX_SUBMIT_ALTERNATIVES) {
                    val submitted = adapter.submitSameTabRedirect(attemptNumber)
                    if (!adapter.ownsProtection()) return Outcome.ABORTED

                    if (submitted && adapter.awaitRedirectConfirmation()) {
"""
new = """            var submittedAtLeastOnce = false
            if (prepared) {
                var submitAlternativeNumber = 1
                while (submitAlternativeNumber <= WebsiteRedirectionPlan.MAX_SUBMIT_ALTERNATIVES) {
                    val submitted = adapter.submitSameTabRedirect(attemptNumber)
                    submittedAtLeastOnce = submittedAtLeastOnce || submitted
                    if (!adapter.ownsProtection()) return Outcome.ABORTED

                    if (submitted && adapter.awaitRedirectConfirmation()) {
"""
if text.count(old) != 1:
    raise SystemExit(f"Expected coordinator submit block once, found {text.count(old)}")
text = text.replace(old, new, 1)

old = """                if (adapter.awaitRedirectConfirmation()) {
                    if (!adapter.ownsProtection()) return Outcome.ABORTED
                    return completeConfirmedRedirect(session, adapter)
                }
"""
new = """                if (submittedAtLeastOnce && adapter.awaitRedirectConfirmation()) {
                    if (!adapter.ownsProtection()) return Outcome.ABORTED
                    return completeConfirmedRedirect(session, adapter)
                }
"""
if text.count(old) != 1:
    raise SystemExit(f"Expected delayed confirmation block once, found {text.count(old)}")
coordinator_path.write_text(text.replace(old, new, 1))

plan_path = Path("app/src/main/java/com/focusguard/accessibility/website/redirection/WebsiteRedirectionPlan.kt")
text = plan_path.read_text()
old = """    private const val PREPARE_AND_RECOVERY_BUDGET_PER_ATTEMPT_MILLIS = 4_000L
    private const val CURTAIN_FAILSAFE_MARGIN_MILLIS = 1_000L

    const val CURTAIN_FAILSAFE_MILLIS =
        MAX_SAME_TAB_ATTEMPTS * (
            MAX_SUBMIT_ALTERNATIVES * DESTINATION_CONFIRM_TIMEOUT_MILLIS +
                PREPARE_AND_RECOVERY_BUDGET_PER_ATTEMPT_MILLIS
            ) + CURTAIN_FAILSAFE_MARGIN_MILLIS
"""
new = """    private const val PREPARE_AND_RECOVERY_BUDGET_PER_ATTEMPT_MILLIS = 4_000L
    private const val POST_REDIRECT_DESTINATION_BUDGET_MILLIS = DESTINATION_CONFIRM_TIMEOUT_MILLIS
    private const val CURTAIN_FAILSAFE_MARGIN_MILLIS = 1_000L

    const val CURTAIN_FAILSAFE_MILLIS =
        MAX_SAME_TAB_ATTEMPTS * (
            MAX_SUBMIT_ALTERNATIVES * DESTINATION_CONFIRM_TIMEOUT_MILLIS +
                PREPARE_AND_RECOVERY_BUDGET_PER_ATTEMPT_MILLIS
            ) + POST_REDIRECT_DESTINATION_BUDGET_MILLIS + CURTAIN_FAILSAFE_MARGIN_MILLIS
"""
if text.count(old) != 1:
    raise SystemExit(f"Expected curtain budget block once, found {text.count(old)}")
plan_path.write_text(text.replace(old, new, 1))

navigation_test_path = Path("app/src/test/java/com/focusguard/service/WebsiteBlockNavigationTest.kt")
text = navigation_test_path.read_text()
old = """        policy.markRedirectRequested()
        assertThat(
            policy.maySubmitSafeAddress(
                browserPackage,
                activeWindowId = 7,
                latestWindowTransitionEventUptimeMillis = 201L
            )
        ).isFalse()
"""
new = """        policy.markRedirectRequested()
        assertThat(
            policy.maySubmitSafeAddress(
                browserPackage,
                activeWindowId = 7,
                latestWindowTransitionEventUptimeMillis = 201L
            )
        ).isTrue()
"""
if text.count(old) != 1:
    raise SystemExit(f"Expected same-tab retry assertion once, found {text.count(old)}")
navigation_test_path.write_text(text.replace(old, new, 1))

plan_test_path = Path("app/src/test/java/com/focusguard/accessibility/website/redirection/WebsiteRedirectionPlanTest.kt")
text = plan_test_path.read_text()
old = """        assertThat(WebsiteRedirectionPlan.CURTAIN_FAILSAFE_MILLIS)
            .isGreaterThan(confirmationBudget)
        assertThat(WebsiteRedirectionPlan.CURTAIN_FAILSAFE_MILLIS).isGreaterThan(5_000L)
"""
new = """        val fullRedirectConfirmationBudget =
            confirmationBudget + WebsiteRedirectionPlan.DESTINATION_CONFIRM_TIMEOUT_MILLIS

        assertThat(WebsiteRedirectionPlan.CURTAIN_FAILSAFE_MILLIS)
            .isGreaterThan(fullRedirectConfirmationBudget)
        assertThat(WebsiteRedirectionPlan.CURTAIN_FAILSAFE_MILLIS).isGreaterThan(5_000L)
"""
if text.count(old) != 1:
    raise SystemExit(f"Expected timing assertion once, found {text.count(old)}")
plan_test_path.write_text(text.replace(old, new, 1))
