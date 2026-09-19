from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]
SERVICE = ROOT / "app/src/main/java/com/focusguard/service/BlockingAccessibilityService.kt"
STRINGS = ROOT / "app/src/main/res/values/strings.xml"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


service = SERVICE.read_text()

service = replace_once(
    service,
    "import com.focusguard.accessibility.website.redirection.WebsiteRedirectionPlan\n",
    "import com.focusguard.accessibility.website.redirection.WebsiteRedirectionPlan\n"
    "import com.focusguard.accessibility.website.redirection.WebsiteRedirectDestination\n"
    "import com.focusguard.accessibility.website.redirection.WebsiteRedirectionCoordinator\n"
    "import com.focusguard.accessibility.website.redirection.WebsiteTabNeutralizationPolicy\n",
    "redirection imports",
)

service = replace_once(
    service,
    """    internal enum class WebsiteTransitionDestination {
        GOOGLE,
        POMODORO
    }

""",
    "",
    "legacy transition destination enum",
)

service = replace_once(
    service,
    """    internal enum class WebsiteTransitionAction {
        SHOW_CURTAIN,
        NEUTRALIZE_BLOCKED_TAB,
        OPEN_POMODORO,
        HIDE_CURTAIN
    }

""",
    "",
    "legacy transition action enum",
)

service = replace_once(
    service,
    """    internal class WebsiteBlockTransitionStateMachine(private val strict: Boolean) {
        private enum class State { NEW, SANITIZATION_PENDING, POMODORO_REQUESTED, FINISHED }

        private var state = State.NEW

        fun begin(): List<WebsiteTransitionAction> {
            check(state == State.NEW)
            state = State.SANITIZATION_PENDING
            return listOf(
                WebsiteTransitionAction.SHOW_CURTAIN,
                WebsiteTransitionAction.NEUTRALIZE_BLOCKED_TAB
            )
        }

        fun afterGoogleSanitized(): WebsiteTransitionAction {
            check(state == State.SANITIZATION_PENDING)
            state = if (strict) State.POMODORO_REQUESTED else State.FINISHED
            return if (strict) WebsiteTransitionAction.OPEN_POMODORO
            else WebsiteTransitionAction.HIDE_CURTAIN
        }

        fun onPomodoroConfirmed(): WebsiteTransitionAction {
            check(state == State.POMODORO_REQUESTED)
            state = State.FINISHED
            return WebsiteTransitionAction.HIDE_CURTAIN
        }

        fun onFailureOrTimeout(): WebsiteTransitionAction {
            check(state != State.FINISHED)
            state = State.FINISHED
            // Failure is terminal for this transition, but the caller must not reveal
            // an unsafe browser surface. It routes to FocusGuard's generic fail-closed
            // notice whenever same-tab sanitization or destination confirmation fails.
            return WebsiteTransitionAction.HIDE_CURTAIN
        }
    }

""",
    "",
    "legacy redirect state machine",
)

policy_pattern = re.compile(
    r"\n    /\*\* Keeps same-tab address-bar actions bound to the detected browser window\. \*/\n"
    r"    internal class WebsiteTabNeutralizationPolicy\(.*?\n    \}\n\n"
    r"    internal class WebsiteBlockTransitionGuard \{",
    re.S,
)
service, count = policy_pattern.subn(
    "\n    internal class WebsiteBlockTransitionGuard {",
    service,
    count=1,
)
if count != 1:
    raise RuntimeError(f"legacy tab policy: expected one match, found {count}")

service = service.replace(
    "val destination: WebsiteTransitionDestination,",
    "val destination: WebsiteRedirectionCoordinator.TerminalDestination,",
)
service = service.replace(
    "destination: WebsiteTransitionDestination,",
    "destination: WebsiteRedirectionCoordinator.TerminalDestination,",
)
service = service.replace(
    "WebsiteTransitionDestination.GOOGLE",
    "WebsiteRedirectionCoordinator.TerminalDestination.REDIRECT",
)
service = service.replace(
    "WebsiteTransitionDestination.POMODORO",
    "WebsiteRedirectionCoordinator.TerminalDestination.POMODORO",
)
service = service.replace(
    "WebsiteBlockTransitionStateMachine(strict = strict)",
    "WebsiteRedirectionCoordinator.Session(strict = strict)",
)
service = service.replace(
    "WebsiteTransitionAction.SHOW_CURTAIN",
    "WebsiteRedirectionCoordinator.Action.SHOW_BLOCK_PRESENTATION",
)
service = service.replace(
    "WebsiteTransitionAction.HIDE_CURTAIN",
    "WebsiteRedirectionCoordinator.Action.HIDE_BLOCK_PRESENTATION",
)
service = service.replace(
    "WebsiteTransitionAction.OPEN_POMODORO",
    "WebsiteRedirectionCoordinator.Action.OPEN_POMODORO",
)
service = service.replace("stateMachine.afterGoogleSanitized()", "stateMachine.afterRedirectConfirmed()")
service = service.replace("stateMachine.onFailureOrTimeout()", "stateMachine.failClosed()")

service = replace_once(
    service,
    """        val curtainGeneration = if (
            WebsiteRedirectionCoordinator.Action.SHOW_BLOCK_PRESENTATION in initialActions
        ) {
            showInstantBlockCurtain(mode = CurtainMode.BLOCK_NOTICE)
        } else {
            0L
        }
""",
    """        val curtainGeneration = if (
            WebsiteRedirectionCoordinator.Action.SHOW_BLOCK_PRESENTATION in initialActions
        ) {
            showWebsiteBlockPresentation(blockedCandidate)
        } else {
            0L
        }
""",
    "normal website presentation",
)

presentation_helper = """
    /**
     * Normal website block presentation. This stays an Accessibility overlay so
     * the browser remains the active window while the same-tab redirect is being
     * executed. A foreground Activity is reserved for terminal fail-closed paths.
     */
    private fun showWebsiteBlockPresentation(blockedCandidate: String?): Long {
        val generation = showInstantBlockCurtain(mode = CurtainMode.BLOCK_NOTICE)
        val displayTarget = blockedCandidate
            ?.takeIf(String::isNotBlank)
            ?.let { candidate ->
                WebsiteBlocker.extractDomain(candidate)
                    .ifBlank { WebsiteBlocker.displayRule(candidate) }
            }
        instantBlockCurtainMessage?.apply {
            text = if (displayTarget.isNullOrBlank()) {
                getString(R.string.website_block_overlay_message_generic)
            } else {
                getString(R.string.website_block_overlay_message, displayTarget)
            }
            visibility = View.VISIBLE
        }
        return generation
    }

"""
service = replace_once(
    service,
    "    private suspend fun releaseWebsiteCurtainAfterMinimumNotice(\n",
    presentation_helper + "    private suspend fun releaseWebsiteCurtainAfterMinimumNotice(\n",
    "website presentation helper insertion",
)

old_fail_closed = """    private fun failClosedWebsiteTransition(transition: WebsiteBlockTransitionHandle) {
        if (!transitionOwnsCurtain(transition)) return
        transition.handedOff = true
        launchOpaqueBrowserFailClosedNotice(
            transition.browserPackageName, SystemClock.uptimeMillis(), transition.curtainGeneration,
            isCurrent = { transitionOwnsCurtain(transition) }
        )
    }
"""
new_fail_closed = """    private fun failClosedWebsiteTransition(transition: WebsiteBlockTransitionHandle) {
        if (!transitionOwnsCurtain(transition)) return
        transition.handedOff = true
        val blockedDomain = transition.blockedCandidate
            ?.takeIf(String::isNotBlank)
            ?.let { candidate ->
                WebsiteBlocker.extractDomain(candidate)
                    .ifBlank { WebsiteBlocker.displayRule(candidate) }
            }
        launchOpaqueBrowserFailClosedNotice(
            browserPackageName = transition.browserPackageName,
            eventUptimeMillis = SystemClock.uptimeMillis(),
            curtainGeneration = transition.curtainGeneration,
            blockedDomain = blockedDomain,
            isCurrent = { transitionOwnsCurtain(transition) }
        )
    }
"""
service = replace_once(service, old_fail_closed, new_fail_closed, "known website fail-closed handoff")

service = replace_once(
    service,
    """    private fun launchOpaqueBrowserFailClosedNotice(
        browserPackageName: String,
        eventUptimeMillis: Long,
        curtainGeneration: Long = 0L,
        isCurrent: () -> Boolean
    ) {
""",
    """    private fun launchOpaqueBrowserFailClosedNotice(
        browserPackageName: String,
        eventUptimeMillis: Long,
        curtainGeneration: Long = 0L,
        blockedDomain: String? = null,
        isCurrent: () -> Boolean
    ) {
""",
    "fail-closed signature",
)
service = replace_once(
    service,
    """            blockedPackage = null,
            blockedDomain = null,
            redirectBrowserPackage = null,
            curtainGeneration = curtainGeneration,
""",
    """            blockedPackage = null,
            blockedDomain = blockedDomain,
            redirectBrowserPackage = null,
            curtainGeneration = curtainGeneration,
""",
    "fail-closed website domain",
)

# Genericize implementation names while preserving behavior and compatibility tests.
identifier_replacements = {
    "safeGoogleConfirmed": "safeRedirectConfirmed",
    "confirmGoogleFromStableCurrentSurface": "confirmRedirectFromStableCurrentSurface",
    "confirmGoogle": "confirmRedirect",
    "rebindPostCloseGoogleWindow": "rebindPostCloseRedirectWindow",
    "isGoogleNavigationEvidenceEvent": "isRedirectNavigationEvidenceEvent",
    "stableGoogleCandidate": "stableRedirectCandidate",
    "googleConfirmed": "redirectConfirmed",
    "confirmSafeGoogleFromFreshBrowserSurface": "confirmSafeRedirectFromFreshBrowserSurface",
    "requestSafeGoogleInCurrentTab": "requestSafeRedirectInCurrentTab",
    "requestSafeGoogleThroughBrowserIntent": "requestSafeRedirectThroughBrowserIntent",
    "WEBSITE_GOOGLE_SURFACE_SETTLE_MILLIS": "WEBSITE_REDIRECT_SURFACE_SETTLE_MILLIS",
    "isSafeGoogleRedirectSurface": "isSafeRedirectSurface",
}
for old, new in identifier_replacements.items():
    service = service.replace(old, new)

service = replace_once(
    service,
    '        private const val SAFE_REDIRECT_URL = "https://www.google.com"\n',
    "",
    "legacy redirect URL constant",
)

hosts_start = service.find("        /** Exact hosts published by Google's supported-domains endpoint. */")
settings_start = service.find("        /**\n         * How long a relevant click keeps intercepting follow-up events.", hosts_start)
if hosts_start < 0 or settings_start < 0:
    raise RuntimeError("legacy Google host block not found")
service = service[:hosts_start] + service[settings_start:]
service = service.replace(
    '        private val SAFE_GOOGLE_ROOT_QUERY_PARAMETERS = setOf("gl", "gws_rd", "hl")\n',
    "",
)

surface_start = service.find("        internal fun isSafeRedirectSurface(urlOrAddress: String?): Boolean {")
surface_end_marker = "\n\n        internal fun curtainReadyForTabAction("
surface_end = service.find(surface_end_marker, surface_start)
if surface_start < 0 or surface_end < 0:
    raise RuntimeError("safe redirect surface helper not found")
service = (
    service[:surface_start]
    + "        internal fun isSafeRedirectSurface(urlOrAddress: String?): Boolean =\n"
      "            WebsiteRedirectDestination.current.matchesSurface(urlOrAddress)"
    + service[surface_end:]
)

service = service.replace("SAFE_REDIRECT_URL", "WebsiteRedirectDestination.current.url")
service = service.replace(
    "fixed to https://www.google.com",
    "bound to the configured safe redirect destination",
)
service = service.replace("safe Google", "safe redirect")
service = service.replace("Safe Google", "Safe redirect")
service = service.replace("Google WEB_CONTENT", "redirect WEB_CONTENT")
service = service.replace("Google window", "redirect window")
service = service.replace("Google surface", "redirect surface")
service = service.replace("Google was", "the redirect destination was")
service = service.replace("Google is", "the redirect destination is")
service = service.replace("Google em", "destino seguro em")

SERVICE.write_text(service)

# Keep tests compiling against the extracted redirection types and generic names.
for path in (ROOT / "app/src/test").rglob("*.kt"):
    text = path.read_text()
    updated = text
    updated = updated.replace(
        "BlockingAccessibilityService.WebsiteTransitionDestination.GOOGLE",
        "WebsiteRedirectionCoordinator.TerminalDestination.REDIRECT",
    )
    updated = updated.replace(
        "BlockingAccessibilityService.WebsiteTransitionDestination.POMODORO",
        "WebsiteRedirectionCoordinator.TerminalDestination.POMODORO",
    )
    for old, new in identifier_replacements.items():
        updated = updated.replace(old, new)
    if "WebsiteRedirectionCoordinator.TerminalDestination" in updated and \
        "import com.focusguard.accessibility.website.redirection.WebsiteRedirectionCoordinator" not in updated:
        updated = updated.replace(
            "package com.focusguard.service\n",
            "package com.focusguard.service\n\n"
            "import com.focusguard.accessibility.website.redirection.WebsiteRedirectionCoordinator\n",
            1,
        )
    if updated != text:
        path.write_text(updated)

strings = STRINGS.read_text()
if 'name="website_block_overlay_message"' not in strings:
    insertion = (
        '    <string name="website_block_overlay_message">Site bloqueado: %1$s</string>\n'
        '    <string name="website_block_overlay_message_generic">Site bloqueado pelo FocusGuard</string>\n'
    )
    strings = strings.replace("</resources>", insertion + "</resources>")
    STRINGS.write_text(strings)

# Dedicated unit tests for the new ownership boundaries.
redirect_test = ROOT / "app/src/test/java/com/focusguard/accessibility/website/redirection/WebsiteRedirectDestinationTest.kt"
redirect_test.parent.mkdir(parents=True, exist_ok=True)
redirect_test.write_text('''package com.focusguard.accessibility.website.redirection

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WebsiteRedirectDestinationTest {
    @Test
    fun `initial destination is Google root`() {
        assertThat(WebsiteRedirectDestination.current.url).isEqualTo("https://www.google.com")
        assertThat(WebsiteRedirectDestination.current.matchesSurface("https://www.google.com/"))
            .isTrue()
        assertThat(WebsiteRedirectDestination.current.matchesSurface("https://www.google.com.br/?hl=pt-BR"))
            .isTrue()
    }

    @Test
    fun `destination rejects search paths and lookalikes`() {
        assertThat(WebsiteRedirectDestination.current.matchesSurface("https://www.google.com/search?q=x"))
            .isFalse()
        assertThat(WebsiteRedirectDestination.current.matchesSurface("https://google.com.evil.example/"))
            .isFalse()
    }

    @Test
    fun `custom destination contract is independent from service`() {
        val destination = WebsiteRedirectDestination(
            url = "https://example.org",
            acceptedRootHosts = setOf("example.org")
        )
        assertThat(destination.matchesSurface("https://example.org/" )).isTrue()
        assertThat(destination.matchesSurface("https://www.google.com/" )).isFalse()
    }
}
''')

coordinator_test = ROOT / "app/src/test/java/com/focusguard/accessibility/website/redirection/WebsiteRedirectionCoordinatorTest.kt"
coordinator_test.write_text('''package com.focusguard.accessibility.website.redirection

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WebsiteRedirectionCoordinatorTest {
    @Test
    fun `normal website redirect owns presentation then hides it after confirmation`() {
        val session = WebsiteRedirectionCoordinator.Session(strict = false)
        assertThat(session.terminalDestination)
            .isEqualTo(WebsiteRedirectionCoordinator.TerminalDestination.REDIRECT)
        assertThat(session.begin()).containsExactly(
            WebsiteRedirectionCoordinator.Action.SHOW_BLOCK_PRESENTATION,
            WebsiteRedirectionCoordinator.Action.NEUTRALIZE_BLOCKED_TAB
        ).inOrder()
        assertThat(session.afterRedirectConfirmed())
            .isEqualTo(WebsiteRedirectionCoordinator.Action.HIDE_BLOCK_PRESENTATION)
    }

    @Test
    fun `strict website redirect sanitizes browser before Pomodoro`() {
        val session = WebsiteRedirectionCoordinator.Session(strict = true)
        assertThat(session.terminalDestination)
            .isEqualTo(WebsiteRedirectionCoordinator.TerminalDestination.POMODORO)
        session.begin()
        assertThat(session.afterRedirectConfirmed())
            .isEqualTo(WebsiteRedirectionCoordinator.Action.OPEN_POMODORO)
        assertThat(session.onPomodoroConfirmed())
            .isEqualTo(WebsiteRedirectionCoordinator.Action.HIDE_BLOCK_PRESENTATION)
    }
}
''')

print("Website blocking refactor codemod applied successfully")
