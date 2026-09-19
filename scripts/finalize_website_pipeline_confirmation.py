from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(path):
    return (ROOT / path).read_text(encoding="utf-8")


def write(path, text):
    (ROOT / path).write_text(text, encoding="utf-8")


def replace_once(path, old, new):
    text = read(path)
    if text.count(old) != 1:
        raise RuntimeError(f"{path}: expected one match, found {text.count(old)}")
    write(path, text.replace(old, new, 1))


transition = "app/src/main/java/com/focusguard/accessibility/website/redirection/WebsiteBlockTransition.kt"
replace_once(
    transition,
    '''        transition.sanitizationRequested = true
        transition.sanitizationRequestedAtUptimeMillis = minOf(
            transition.sanitizationRequestedAtUptimeMillis,
            requestedAtUptimeMillis
        )
''',
    '''        transition.sanitizationRequested = true
        // Each retry owns a fresh temporal boundary. Evidence from an older submit
        // must never certify a later attempt.
        transition.sanitizationRequestedAtUptimeMillis = requestedAtUptimeMillis
'''
)

service = "app/src/main/java/com/focusguard/service/BlockingAccessibilityService.kt"
replace_once(
    service,
    '''                        override suspend fun awaitRedirectConfirmation(): Boolean =
                            withTimeoutOrNull(WEBSITE_DESTINATION_CONFIRM_TIMEOUT_MILLIS) {
                                transition.safeRedirectConfirmed.await()
                                true
                            } == true
''',
    '''                        override suspend fun awaitRedirectConfirmation(): Boolean =
                            withTimeoutOrNull(WEBSITE_DESTINATION_CONFIRM_TIMEOUT_MILLIS) {
                                while (transitionOwnsCurtain(transition)) {
                                    if (transition.safeRedirectConfirmed.isCompleted) {
                                        return@withTimeoutOrNull true
                                    }
                                    // Event delivery can be delayed/lost on Fenix. Keep the
                                    // fallback inside this same timeout instead of creating a
                                    // second confirmation wait in the submit layer.
                                    if (curtainReadyForTransition(transition) &&
                                        websiteTreeWorker.run {
                                            confirmSafeRedirectFromFreshBrowserSurface(transition)
                                        }
                                    ) {
                                        return@withTimeoutOrNull true
                                    }
                                    delay(WEBSITE_REDIRECT_SURFACE_SETTLE_MILLIS)
                                }
                                false
                            } == true
'''
)

nav_test = "app/src/test/java/com/focusguard/service/WebsiteBlockNavigationTest.kt"
needle = '''    @Test
    fun `verified destination can rebind one recreated accessibility window`() {
'''
insert = '''    @Test
    fun `new submit attempt rejects navigation evidence from previous attempt`() {
        val guard = WebsiteBlockTransitionGuard()
        val transition = guard.tryStart(
            CHROME_PACKAGE,
            transitionId = 24L,
            destination = WebsiteRedirectionCoordinator.TerminalDestination.REDIRECT,
            expectedWindowId = 7,
            detectionEventUptimeMillis = 50L
        )!!
        assertThat(guard.markSanitizationRequested(CHROME_PACKAGE, 24L, 100L)).isTrue()
        assertThat(guard.markSanitizationRequested(CHROME_PACKAGE, 24L, 200L)).isTrue()

        assertThat(
            guard.transitionForDestinationCandidate(
                CHROME_PACKAGE,
                eventUptimeMillis = 150L,
                eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            )
        ).isNull()
        assertThat(
            guard.transitionForDestinationCandidate(
                CHROME_PACKAGE,
                eventUptimeMillis = 200L,
                eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            )
        ).isSameInstanceAs(transition)
    }

'''
if needle not in read(nav_test):
    raise RuntimeError("navigation insertion point missing")
replace_once(nav_test, needle, insert + needle)

doc = "docs/WEBSITE_BLOCKING_ARCHITECTURE.md"
text = read(doc)
old = '- Cada tentativa tem exatamente um timeout de confirmação, controlado pelo `WebsiteRedirectionCoordinator`; timeout pode restaurar a superfície e consumir o retry same-tab.\n'
new = '- Cada tentativa tem exatamente um timeout de confirmação, controlado pelo `WebsiteRedirectionCoordinator`; dentro dele, eventos e uma releitura estável bounded compartilham o mesmo orçamento. Timeout pode restaurar a superfície e consumir o retry same-tab.\n'
if old in text:
    write(doc, text.replace(old, new, 1))

print("final confirmation hardening applied")
