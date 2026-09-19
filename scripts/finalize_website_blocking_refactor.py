#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
SERVICE = ROOT / "app/src/main/java/com/focusguard/service/BlockingAccessibilityService.kt"


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def write(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def replace_required(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise RuntimeError(f"missing expected fragment: {label}")
    return text.replace(old, new, 1)


def remove_between(text: str, start: str, end: str, label: str) -> str:
    start_at = text.find(start)
    if start_at < 0:
        raise RuntimeError(f"missing start marker: {label}")
    end_at = text.find(end, start_at)
    if end_at < 0:
        raise RuntimeError(f"missing end marker: {label}")
    return text[:start_at] + end + text[end_at + len(end):]


def remove_test_functions_containing(path: Path, tokens: tuple[str, ...]) -> None:
    text = read(path)
    search_at = 0
    ranges: list[tuple[int, int]] = []
    while True:
        test_at = text.find("    @Test", search_at)
        if test_at < 0:
            break
        fun_at = text.find("    fun ", test_at)
        if fun_at < 0:
            break
        next_test = text.find("    @Test", fun_at + 1)
        brace_at = text.find("{", fun_at)
        if brace_at < 0 or (next_test >= 0 and brace_at > next_test):
            search_at = fun_at + 1
            continue
        depth = 0
        i = brace_at
        in_string = False
        escape = False
        while i < len(text):
            ch = text[i]
            if in_string:
                if escape:
                    escape = False
                elif ch == "\\":
                    escape = True
                elif ch == '"':
                    in_string = False
            else:
                if ch == '"':
                    in_string = True
                elif ch == "{":
                    depth += 1
                elif ch == "}":
                    depth -= 1
                    if depth == 0:
                        end = i + 1
                        block = text[test_at:end]
                        if any(token in block for token in tokens):
                            while end < len(text) and text[end] in "\r\n":
                                end += 1
                            ranges.append((test_at, end))
                        search_at = end
                        break
            i += 1
        else:
            raise RuntimeError(f"unbalanced test function in {path}")
    for start, end in reversed(ranges):
        text = text[:start] + text[end:]
    write(path, text)


# 1. Service becomes Android adapter; transition state belongs to redirection package.
text = read(SERVICE)
text = replace_required(
    text,
    "import com.focusguard.accessibility.website.redirection.WebsiteRedirectionPlan\n",
    "",
    "remove service WebsiteRedirectionPlan import",
)
text = replace_required(
    text,
    "import com.focusguard.accessibility.website.redirection.WebsiteRedirectionCoordinator\n",
    "import com.focusguard.accessibility.website.redirection.WebsiteRedirectionCoordinator\n"
    "import com.focusguard.accessibility.website.redirection.WebsiteBlockTransitionGuard\n"
    "import com.focusguard.accessibility.website.redirection.WebsiteBlockTransitionHandle\n",
    "transition imports",
)
text = text.replace("import kotlinx.coroutines.CompletableDeferred\n", "")
text = remove_between(
    text,
    "    internal enum class WebsiteCloseFollowUp {",
    "    @Inject lateinit var authManager: AuthManager",
    "nested website transition state",
)

text = text.replace(
    "            // An explicit ACTION_VIEW fallback may make the same browser expose a new\n"
    "            // Android window. Hand the first post-intent browser window to the existing\n"
    "            // transition before generic window retirement. This is provisional only:\n"
    "            // the curtain remains until a stable safe redirect surface is confirmed.\n",
    "            // Keep events bound to the browser transition that owns the opaque curtain.\n"
    "            // Same-tab redirection never transfers ownership to an unproven window.\n",
)
text = text.replace(
    "                // Do not bind an external redirect to the first browser-owned window.\n"
    "                // Chromium and Fenix can expose suggestion/native/transient windows before\n"
    "                // the destination page. Only a positively identified redirect WEB_CONTENT\n"
    "                // inspection is allowed to rebind the transition later.\n",
    "                // Track window transitions for staleness only; ownership remains bound\n"
    "                // to the original browser window throughout the same-tab transaction.\n",
)

external_rebind = """        // ACTION_VIEW may pass through several browser-owned Accessibility windows.\n        // Rebind only after this exact inspected window has already proved that it is\n        // the safe redirect web surface; an arbitrary first post-intent window must never\n        // consume the one allowed external handoff.\n        if (transition.externalRedirectRequested &&\n            token.windowId != transition.expectedWindowId\n        ) {\n            if (!websiteBlockTransitionGuard.rebindExternalRedirectWindow(\n                    browserPackageName = token.packageName,\n                    transitionId = transition.id,\n                    windowId = token.windowId,\n                    inspectionGeneration = token.generation,\n                    eventUptimeMillis = outcome.eventUptimeMillis\n                )\n            ) return\n        }\n"""
text = replace_required(text, external_rebind, "", "external redirect rebind")

start_flow = """        val stateMachine = WebsiteRedirectionCoordinator.Session(strict = strict)\n        val initialActions = stateMachine.begin()\n        val curtainGeneration = if (\n            WebsiteRedirectionCoordinator.Action.SHOW_BLOCK_PRESENTATION in initialActions\n        ) {\n            showWebsiteBlockPresentation(blockedCandidate)\n        } else {\n            0L\n        }\n"""
text = replace_required(
    text,
    start_flow,
    """        val stateMachine = WebsiteRedirectionCoordinator.Session(strict = strict)\n        stateMachine.begin()\n        val curtainGeneration = showWebsiteBlockPresentation(blockedCandidate)\n""",
    "coordinator begin contract",
)

external_adapter = """                        override suspend fun requestExternalFallback(): Boolean {\n                            if (!transitionOwnsCurtain(transition) ||\n                                !supportsCapabilityBasedIntentRedirectFallback(\n                                    knownBrowser = browserPackageName in knownBrowserPackages,\n                                    verifiedHttpsHandler = isVerifiedHttpsHandler(browserPackageName)\n                                )\n                            ) return false\n                            FocusGuardLogger.log(\n                                \"A11y\",\n                                \"Usando fallback por intent para destino seguro em $browserPackageName\"\n                            )\n                            return requestSafeRedirectThroughBrowserIntent(transition)\n                        }\n\n"""
text = replace_required(text, external_adapter, "", "external adapter fallback")

text = remove_between(
    text,
    "    private suspend fun restoreBlockedSurfaceForSafeIntentFallback(",
    "    private suspend fun completeStrictWebsiteDestination(",
    "dead external redirect helpers",
)

text = text.replace(
    "            // Browser navigation itself can replace the Accessibility window. While the\n"
    "            // transition still owns the opaque curtain, keep it alive long enough to hand\n"
    "            // off to a confirmed redirect window or to the explicit browser intent fallback.\n",
    "            // A same-tab navigation can invalidate the Accessibility window. Keep the\n"
    "            // curtain fail-closed while this transition still owns its generation.\n",
)

text = re.sub(
    r"\n        internal fun supportsCapabilityBasedIntentRedirectFallback\(\n"
    r"            knownBrowser: Boolean,\n"
    r"            verifiedHttpsHandler: Boolean\n"
    r"        \): Boolean = knownBrowser \|\| verifiedHttpsHandler\n",
    "\n",
    text,
    count=1,
)
text = remove_between(
    text,
    "        internal fun afterChromiumCloseAttempt(",
    "        internal fun isRedirectNavigationEvidenceEvent(",
    "dead close/rebind helpers",
)
text = remove_between(
    text,
    "        internal fun createSafeBrowserRedirectIntent(",
    "        internal fun createBlockNoticeIntent(",
    "dead ACTION_VIEW intent factory",
)

# Remove browser redirect extra from all service calls/contracts.
text = re.sub(r"^\s*redirectBrowserPackage\s*=\s*[^\n]+,\n", "", text, flags=re.MULTILINE)
text = text.replace("        redirectBrowserPackage: String? = null,\n", "")
text = text.replace("            redirectBrowserPackage: String?,\n", "")
text = text.replace('        const val EXTRA_REDIRECT_BROWSER_PACKAGE = "REDIRECT_BROWSER_PACKAGE"\n', "")
text = re.sub(
    r"\n            redirectBrowserPackage\n"
    r"                \?\.takeIf\(String::isNotBlank\)\n"
    r"                \?\.let \{ putExtra\(EXTRA_REDIRECT_BROWSER_PACKAGE, it\) \}",
    "",
    text,
    count=1,
)
write(SERVICE, text)

# 2. Coordinator: only certifiable same-tab attempts; begin() no longer emits phantom actions.
coordinator = ROOT / "app/src/main/java/com/focusguard/accessibility/website/redirection/WebsiteRedirectionCoordinator.kt"
write(coordinator, '''package com.focusguard.accessibility.website.redirection

import com.focusguard.utils.BrowserUiCapabilityPolicy

/**
 * Coordinator for one website redirection transaction.
 *
 * Android window/tree operations stay behind [Adapter], while ordering, retries,
 * redirect confirmation, strict terminal routing and fail-closed ownership live here.
 * The presentation is shown by the service adapter before [execute], because it owns
 * the Android overlay generation token.
 */
internal object WebsiteRedirectionCoordinator {
    enum class TerminalDestination { REDIRECT, POMODORO }

    enum class Action { OPEN_POMODORO, HIDE_BLOCK_PRESENTATION }

    enum class Outcome {
        REDIRECT_CONFIRMED,
        STRICT_DESTINATION_CONFIRMED,
        FAIL_CLOSED,
        ABORTED
    }

    interface Adapter {
        suspend fun awaitPresentationFrame(): Boolean
        fun ownsProtection(): Boolean
        suspend fun requestSameTabRedirect(attemptNumber: Int): Boolean
        suspend fun restoreBlockedSurfaceForRetry(): Boolean
        suspend fun beforeRetry(nextAttemptNumber: Int)
        suspend fun awaitRedirectConfirmation(): Boolean
        suspend fun completeStrictDestination(): Boolean
        suspend fun releasePresentation()
        fun failClosed()
    }

    class Session(private val strict: Boolean) {
        private enum class State { NEW, SANITIZATION_PENDING, POMODORO_REQUESTED, FINISHED }
        private var state = State.NEW

        val terminalDestination: TerminalDestination = if (strict) {
            TerminalDestination.POMODORO
        } else {
            TerminalDestination.REDIRECT
        }

        fun begin() {
            check(state == State.NEW)
            state = State.SANITIZATION_PENDING
        }

        fun afterRedirectConfirmed(): Action {
            check(state == State.SANITIZATION_PENDING)
            state = if (strict) State.POMODORO_REQUESTED else State.FINISHED
            return if (strict) Action.OPEN_POMODORO else Action.HIDE_BLOCK_PRESENTATION
        }

        fun onPomodoroConfirmed(): Action {
            check(state == State.POMODORO_REQUESTED)
            state = State.FINISHED
            return Action.HIDE_BLOCK_PRESENTATION
        }

        fun failClosed() {
            check(state != State.FINISHED)
            state = State.FINISHED
        }
    }

    suspend fun execute(session: Session, adapter: Adapter): Outcome {
        if (!adapter.awaitPresentationFrame() || !adapter.ownsProtection()) {
            return Outcome.ABORTED
        }

        var redirectRequested = false
        var attemptNumber = 1
        while (adapter.ownsProtection() && attemptNumber <= WebsiteRedirectionPlan.MAX_SAME_TAB_ATTEMPTS) {
            redirectRequested = adapter.requestSameTabRedirect(attemptNumber)
            if (redirectRequested) break
            if (!adapter.ownsProtection()) return Outcome.ABORTED
            if (!WebsiteRedirectionPlan.canRetry(attemptNumber)) break
            if (!adapter.restoreBlockedSurfaceForRetry()) break
            if (!adapter.ownsProtection()) return Outcome.ABORTED
            attemptNumber += 1
            adapter.beforeRetry(attemptNumber)
        }

        if (!adapter.ownsProtection()) return Outcome.ABORTED
        if (!redirectRequested) return failClosed(session, adapter)
        if (!adapter.awaitRedirectConfirmation()) {
            if (!adapter.ownsProtection()) return Outcome.ABORTED
            return failClosed(session, adapter)
        }
        if (!adapter.ownsProtection()) return Outcome.ABORTED

        return when (session.afterRedirectConfirmed()) {
            Action.HIDE_BLOCK_PRESENTATION -> {
                adapter.releasePresentation()
                Outcome.REDIRECT_CONFIRMED
            }
            Action.OPEN_POMODORO -> {
                if (adapter.completeStrictDestination()) {
                    session.onPomodoroConfirmed()
                    adapter.releasePresentation()
                    Outcome.STRICT_DESTINATION_CONFIRMED
                } else if (!adapter.ownsProtection()) {
                    Outcome.ABORTED
                } else {
                    failClosed(session, adapter)
                }
            }
        }
    }

    private fun failClosed(session: Session, adapter: Adapter): Outcome {
        session.failClosed()
        adapter.failClosed()
        return Outcome.FAIL_CLOSED
    }
}

/** Keeps same-tab address-bar actions bound to the browser window that was blocked. */
internal class WebsiteTabNeutralizationPolicy(
    private val browserPackageName: String,
    private val expectedWindowId: Int
) {
    private enum class State { BLOCKED_TAB, SAFE_ADDRESS_SET, REDIRECT_REQUESTED }
    private var state = State.BLOCKED_TAB

    fun mayTouchBlockedTab(activePackageName: String, activeWindowId: Int): Boolean =
        state == State.BLOCKED_TAB && activePackageName == browserPackageName && activeWindowId == expectedWindowId

    fun mayAttemptChromiumClose(
        activePackageName: String,
        activeWindowId: Int,
        phaseStartedAtUptimeMillis: Long,
        latestWindowTransitionEventUptimeMillis: Long
    ): Boolean = state == State.BLOCKED_TAB && BrowserUiCapabilityPolicy.isFreshExpectedSurface(
        expectedBrowserPackage = browserPackageName,
        expectedWindowId = expectedWindowId,
        activePackageName = activePackageName,
        activeWindowId = activeWindowId,
        phaseStartedAtUptimeMillis = phaseStartedAtUptimeMillis,
        latestWindowTransitionEventUptimeMillis = latestWindowTransitionEventUptimeMillis
    )

    fun mayActivateBlockedAddressBar(
        activePackageName: String,
        activeWindowId: Int,
        @Suppress("UNUSED_PARAMETER") phaseStartedAtUptimeMillis: Long,
        @Suppress("UNUSED_PARAMETER") latestWindowTransitionEventUptimeMillis: Long
    ): Boolean = state == State.BLOCKED_TAB && activePackageName == browserPackageName && activeWindowId == expectedWindowId

    fun markSafeAddressSet(setAtUptimeMillis: Long) {
        check(state == State.BLOCKED_TAB)
        check(setAtUptimeMillis > 0L)
        state = State.SAFE_ADDRESS_SET
    }

    fun maySubmitSafeAddress(
        activePackageName: String,
        activeWindowId: Int,
        @Suppress("UNUSED_PARAMETER") latestWindowTransitionEventUptimeMillis: Long
    ): Boolean = state == State.SAFE_ADDRESS_SET && activePackageName == browserPackageName && activeWindowId == expectedWindowId

    fun markRedirectRequested() {
        check(state == State.SAFE_ADDRESS_SET)
        state = State.REDIRECT_REQUESTED
    }
}
''')

plan = ROOT / "app/src/main/java/com/focusguard/accessibility/website/redirection/WebsiteRedirectionPlan.kt"
plan_text = read(plan)
plan_text = re.sub(
    r"\n    /\*\*\n     \* Website blocking must stay bound[\s\S]*?\n    const val ALLOW_EXTERNAL_BROWSER_INTENT_FALLBACK = false\n",
    "\n",
    plan_text,
    count=1,
)
write(plan, plan_text)

# 3. Harden destination contract.
destination = ROOT / "app/src/main/java/com/focusguard/accessibility/website/redirection/WebsiteRedirectDestination.kt"
dest_text = read(destination)
dest_text = dest_text.replace(
    '        require(url.startsWith("https://")) { "Website redirect destination must use HTTPS" }\n'
    '        require(acceptedRootHosts.isNotEmpty()) { "At least one destination host is required" }\n',
    '''        require(acceptedRootHosts.isNotEmpty()) { "At least one destination host is required" }\n        val configured = runCatching { URI(url) }.getOrNull()\n            ?: throw IllegalArgumentException("Website redirect destination must be a valid URI")\n        val configuredHost = configured.host?.lowercase(Locale.US)?.removePrefix("www.")\n            ?: throw IllegalArgumentException("Website redirect destination must have a host")\n        require(configured.scheme.equals("https", ignoreCase = true)) {\n            "Website redirect destination must use HTTPS"\n        }\n        require(configured.userInfo == null) { "Website redirect destination cannot contain user info" }\n        require(configured.port == -1 || configured.port == 443) {\n            "Website redirect destination must use the default HTTPS port"\n        }\n        require(configuredHost in acceptedRootHosts.map { it.lowercase(Locale.US).removePrefix("www.") }) {\n            "Configured destination host must be accepted by its validation policy"\n        }\n        require(configured.rawFragment.isNullOrEmpty()) {\n            "Website redirect destination cannot contain a fragment"\n        }\n''',
)
dest_text = dest_text.replace(
    '        val host = uri.host?.lowercase(Locale.US)?.removePrefix("www.") ?: return false\n',
    '        if (!uri.scheme.equals("https", ignoreCase = true) || uri.userInfo != null ||\n            (uri.port != -1 && uri.port != 443)\n        ) return false\n        val host = uri.host?.lowercase(Locale.US)?.removePrefix("www.") ?: return false\n',
)
write(destination, dest_text)

# 4. Localize website terminal UI and overlay strings.
activity = ROOT / "app/src/main/java/com/focusguard/ui/WebsiteBlockNoticeActivity.kt"
activity_text = read(activity)
activity_text = replace_required(
    activity_text,
    '                text = "Site bloqueado pelo FocusGuard",\n',
    '                text = stringResource(R.string.website_block_overlay_message_generic),\n',
    "website activity title resource",
)
activity_text = replace_required(
    activity_text,
    '                text = blockedDomain ?: "O acesso a este site foi bloqueado.",\n',
    '                text = blockedDomain ?: stringResource(R.string.website_block_notice_description),\n',
    "website activity description resource",
)
write(activity, activity_text)

translations = {
    "values-en": ("Website blocked: %1$s", "Website blocked by FocusGuard", "Access to this website has been blocked."),
    "values-pt": ("Site bloqueado: %1$s", "Site bloqueado pelo FocusGuard", "O acesso a este site foi bloqueado."),
    "values-de": ("Website blockiert: %1$s", "Website von FocusGuard blockiert", "Der Zugriff auf diese Website wurde blockiert."),
    "values-hi": ("वेबसाइट ब्लॉक की गई: %1$s", "FocusGuard ने वेबसाइट ब्लॉक की", "इस वेबसाइट तक पहुँच ब्लॉक कर दी गई है।"),
    "values-ru": ("Сайт заблокирован: %1$s", "Сайт заблокирован FocusGuard", "Доступ к этому сайту заблокирован."),
    "values-sw": ("Tovuti imezuiwa: %1$s", "Tovuti imezuiwa na FocusGuard", "Ufikiaji wa tovuti hii umezuiwa."),
    "values-mr": ("वेबसाइट ब्लॉक केली: %1$s", "FocusGuard ने वेबसाइट ब्लॉक केली", "या वेबसाइटचा प्रवेश ब्लॉक केला आहे."),
    "values-bn": ("ওয়েবসাইট ব্লক করা হয়েছে: %1$s", "FocusGuard ওয়েবসাইটটি ব্লক করেছে", "এই ওয়েবসাইটে প্রবেশ ব্লক করা হয়েছে।"),
    "values-fr": ("Site bloqué : %1$s", "Site bloqué par FocusGuard", "L’accès à ce site a été bloqué."),
    "values-es": ("Sitio bloqueado: %1$s", "Sitio bloqueado por FocusGuard", "Se bloqueó el acceso a este sitio."),
    "values-ar": ("تم حظر الموقع: %1$s", "تم حظر الموقع بواسطة FocusGuard", "تم حظر الوصول إلى هذا الموقع."),
    "values-te": ("వెబ్‌సైట్ బ్లాక్ చేయబడింది: %1$s", "FocusGuard వెబ్‌సైట్‌ను బ్లాక్ చేసింది", "ఈ వెబ్‌సైట్‌కు ప్రాప్యత బ్లాక్ చేయబడింది."),
    "values-vi": ("Trang web đã bị chặn: %1$s", "Trang web đã bị FocusGuard chặn", "Quyền truy cập trang web này đã bị chặn."),
    "values-ja": ("ウェブサイトをブロックしました: %1$s", "FocusGuard がウェブサイトをブロックしました", "このウェブサイトへのアクセスはブロックされています。"),
    "values-ha": ("An toshe shafin yanar gizo: %1$s", "FocusGuard ya toshe shafin yanar gizo", "An toshe damar shiga wannan shafin yanar gizo."),
    "values-id": ("Situs diblokir: %1$s", "Situs diblokir oleh FocusGuard", "Akses ke situs ini telah diblokir."),
    "values-ur": ("ویب سائٹ بلاک کر دی گئی: %1$s", "FocusGuard نے ویب سائٹ بلاک کر دی", "اس ویب سائٹ تک رسائی بلاک کر دی گئی ہے۔"),
    "values-b+zh+Hans": ("网站已屏蔽：%1$s", "FocusGuard 已屏蔽网站", "对此网站的访问已被屏蔽。"),
    "values-b+arz": ("الموقع اتحظر: %1$s", "FocusGuard حظر الموقع", "الوصول للموقع ده اتحظر."),
    "values-b+pcm": ("Website don block: %1$s", "FocusGuard don block website", "Access to dis website don block."),
}
res = ROOT / "app/src/main/res"
def esc_xml(value: str) -> str:
    return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

# Default resources remain Portuguese, matching the existing FocusGuard fallback.
default_strings = res / "values/strings.xml"
default_text = read(default_strings)
if 'name="website_block_notice_description"' not in default_text:
    default_text = default_text.replace(
        "</resources>",
        '    <string name="website_block_notice_description">O acesso a este site foi bloqueado.</string>\n</resources>',
    )
write(default_strings, default_text)

for directory, values in translations.items():
    path = res / directory / "strings.xml"
    if not path.exists():
        continue
    locale_text = read(path)
    additions = []
    for key, value in zip(
        ("website_block_overlay_message", "website_block_overlay_message_generic", "website_block_notice_description"),
        values,
    ):
        if f'name="{key}"' not in locale_text:
            additions.append(f'    <string name="{key}">{esc_xml(value)}</string>')
    if additions:
        locale_text = locale_text.replace("</resources>", "\n".join(additions) + "\n</resources>")
        write(path, locale_text)

# 5. Migrate tests to extracted transition types and remove tests for dead fallbacks.
for path in (ROOT / "app/src/test/java").rglob("*.kt"):
    test_text = read(path)
    needs_guard = "BlockingAccessibilityService.WebsiteBlockTransitionGuard" in test_text
    needs_handle = "BlockingAccessibilityService.WebsiteBlockTransitionHandle" in test_text
    test_text = test_text.replace("BlockingAccessibilityService.WebsiteBlockTransitionGuard", "WebsiteBlockTransitionGuard")
    test_text = test_text.replace("BlockingAccessibilityService.WebsiteBlockTransitionHandle", "WebsiteBlockTransitionHandle")
    if needs_guard and "import com.focusguard.accessibility.website.redirection.WebsiteBlockTransitionGuard" not in test_text:
        test_text = test_text.replace(
            "\n\n",
            "\n\nimport com.focusguard.accessibility.website.redirection.WebsiteBlockTransitionGuard\n",
            1,
        )
    if needs_handle and "import com.focusguard.accessibility.website.redirection.WebsiteBlockTransitionHandle" not in test_text:
        test_text = test_text.replace(
            "\n\n",
            "\n\nimport com.focusguard.accessibility.website.redirection.WebsiteBlockTransitionHandle\n",
            1,
        )
    test_text = re.sub(r"^\s*redirectBrowserPackage\s*=\s*[^\n]+,\n", "", test_text, flags=re.MULTILINE)
    write(path, test_text)

for dead_file in (
    ROOT / "app/src/test/java/com/focusguard/service/ExternalRedirectHandoffTest.kt",
    ROOT / "app/src/test/java/com/focusguard/service/ExternalRedirectNeutralizationTest.kt",
):
    if dead_file.exists():
        dead_file.unlink()

for path in (ROOT / "app/src/test/java").rglob("*.kt"):
    remove_test_functions_containing(
        path,
        (
            "requestSafeRedirectThroughBrowserIntent",
            "supportsCapabilityBasedIntentRedirectFallback",
            "createSafeBrowserRedirectIntent",
            "WebsiteCloseFollowUp",
            "afterChromiumCloseAttempt",
            "isClosedSurfaceConfirmed",
            "markCloseClicked",
            "markCloseConfirmed",
            "rebindPostCloseRedirectWindow",
            "rebindExternalRedirectWindow",
            "externalRedirectRequested",
            "ALLOW_EXTERNAL_BROWSER_INTENT_FALLBACK",
            "EXTRA_REDIRECT_BROWSER_PACKAGE",
        ),
    )

# Remove now-unused Intent import in BrowserTransitionRaceTest if applicable.
race = ROOT / "app/src/test/java/com/focusguard/service/BrowserTransitionRaceTest.kt"
if race.exists():
    race_text = read(race).replace("import android.content.Intent\n", "")
    write(race, race_text)

# Dedicated coordinator tests for its final public contract.
coord_test = ROOT / "app/src/test/java/com/focusguard/accessibility/website/redirection/WebsiteRedirectionCoordinatorTest.kt"
write(coord_test, '''package com.focusguard.accessibility.website.redirection

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

class WebsiteRedirectionCoordinatorTest {
    @Test
    fun `normal session redirects then hides presentation`() {
        val session = WebsiteRedirectionCoordinator.Session(strict = false)
        assertThat(session.terminalDestination)
            .isEqualTo(WebsiteRedirectionCoordinator.TerminalDestination.REDIRECT)
        session.begin()
        assertThat(session.afterRedirectConfirmed())
            .isEqualTo(WebsiteRedirectionCoordinator.Action.HIDE_BLOCK_PRESENTATION)
    }

    @Test
    fun `strict session sanitizes browser before Pomodoro`() {
        val session = WebsiteRedirectionCoordinator.Session(strict = true)
        assertThat(session.terminalDestination)
            .isEqualTo(WebsiteRedirectionCoordinator.TerminalDestination.POMODORO)
        session.begin()
        assertThat(session.afterRedirectConfirmed())
            .isEqualTo(WebsiteRedirectionCoordinator.Action.OPEN_POMODORO)
        assertThat(session.onPomodoroConfirmed())
            .isEqualTo(WebsiteRedirectionCoordinator.Action.HIDE_BLOCK_PRESENTATION)
    }

    @Test
    fun `execution retries same tab once and releases only after confirmation`() = runBlocking {
        val session = WebsiteRedirectionCoordinator.Session(strict = false).also { it.begin() }
        val adapter = FakeAdapter(ArrayDeque(listOf(false, true)), redirectConfirmed = true)
        val outcome = WebsiteRedirectionCoordinator.execute(session, adapter)
        assertThat(outcome).isEqualTo(WebsiteRedirectionCoordinator.Outcome.REDIRECT_CONFIRMED)
        assertThat(adapter.sameTabAttempts).containsExactly(1, 2).inOrder()
        assertThat(adapter.restoreCalls).isEqualTo(1)
        assertThat(adapter.releaseCalls).isEqualTo(1)
        assertThat(adapter.failClosedCalls).isEqualTo(0)
    }

    @Test
    fun `execution fails closed after bounded same tab attempts`() = runBlocking {
        val session = WebsiteRedirectionCoordinator.Session(strict = false).also { it.begin() }
        val adapter = FakeAdapter(ArrayDeque(listOf(false, false)), redirectConfirmed = false)
        val outcome = WebsiteRedirectionCoordinator.execute(session, adapter)
        assertThat(outcome).isEqualTo(WebsiteRedirectionCoordinator.Outcome.FAIL_CLOSED)
        assertThat(adapter.sameTabAttempts).containsExactly(1, 2).inOrder()
        assertThat(adapter.restoreCalls).isEqualTo(1)
        assertThat(adapter.releaseCalls).isEqualTo(0)
        assertThat(adapter.failClosedCalls).isEqualTo(1)
    }

    private class FakeAdapter(
        private val sameTabResults: ArrayDeque<Boolean>,
        private val redirectConfirmed: Boolean
    ) : WebsiteRedirectionCoordinator.Adapter {
        val sameTabAttempts = mutableListOf<Int>()
        var restoreCalls = 0
        var releaseCalls = 0
        var failClosedCalls = 0
        override suspend fun awaitPresentationFrame(): Boolean = true
        override fun ownsProtection(): Boolean = true
        override suspend fun requestSameTabRedirect(attemptNumber: Int): Boolean {
            sameTabAttempts += attemptNumber
            return sameTabResults.removeFirstOrNull() ?: false
        }
        override suspend fun restoreBlockedSurfaceForRetry(): Boolean {
            restoreCalls += 1
            return true
        }
        override suspend fun beforeRetry(nextAttemptNumber: Int) = Unit
        override suspend fun awaitRedirectConfirmation(): Boolean = redirectConfirmed
        override suspend fun completeStrictDestination(): Boolean = true
        override suspend fun releasePresentation() { releaseCalls += 1 }
        override fun failClosed() { failClosedCalls += 1 }
    }
}
''')

# Destination tests also verify malformed/mismatched contracts fail early.
dest_test = ROOT / "app/src/test/java/com/focusguard/accessibility/website/redirection/WebsiteRedirectDestinationTest.kt"
dest_test_text = read(dest_test)
if "configured host must belong" not in dest_test_text:
    insertion = '''\n    @Test(expected = IllegalArgumentException::class)\n    fun `configured host must belong to validation policy`() {\n        WebsiteRedirectDestination(\n            url = "https://example.org",\n            acceptedRootHosts = setOf("example.com")\n        )\n    }\n\n    @Test\n    fun `destination rejects non https and user info surfaces`() {\n        assertThat(WebsiteRedirectDestination.current.matchesSurface("http://www.google.com/"))\n            .isFalse()\n        assertThat(WebsiteRedirectDestination.current.matchesSurface("https://user@www.google.com/"))\n            .isFalse()\n    }\n'''
    dest_test_text = dest_test_text.replace("\n}", insertion + "\n}")
    write(dest_test, dest_test_text)

# Direct decision-policy coverage.
decision_test = ROOT / "app/src/test/java/com/focusguard/accessibility/website/blocking/WebsiteBlockDecisionPolicyTest.kt"
write(decision_test, '''package com.focusguard.accessibility.website.blocking

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WebsiteBlockDecisionPolicyTest {
    @Test
    fun `stronger protection wins when hard and password both match`() {
        val resolution = WebsiteBlockDecisionPolicy.resolve(
            candidate = "https://m.example.com/path",
            passwordRules = setOf("example.com"),
            strongerRules = setOf("example.com")
        )
        assertThat(resolution.owner).isEqualTo(WebsiteBlockDecisionPolicy.Owner.HARD)
        assertThat(resolution.matchedRule).isEqualTo("example.com")
    }

    @Test
    fun `password owns candidate when no stronger rule matches`() {
        val resolution = WebsiteBlockDecisionPolicy.resolve(
            candidate = "https://example.com/path",
            passwordRules = setOf("example.com"),
            strongerRules = setOf("other.example")
        )
        assertThat(resolution.owner).isEqualTo(WebsiteBlockDecisionPolicy.Owner.PASSWORD)
    }

    @Test
    fun `unmatched candidate has no owner`() {
        val resolution = WebsiteBlockDecisionPolicy.resolve(
            candidate = "https://allowed.example/",
            passwordRules = setOf("locked.example"),
            strongerRules = setOf("hard.example")
        )
        assertThat(resolution.owner).isEqualTo(WebsiteBlockDecisionPolicy.Owner.NONE)
        assertThat(resolution.matchedRule).isNull()
    }
}
''')

# Router test no longer transports an unused browser package extra.
routing = ROOT / "app/src/test/java/com/focusguard/ui/BlockNoticeRoutingTest.kt"
routing_text = read(routing)
routing_text = re.sub(
    r"\n\s*putExtra\(BlockingAccessibilityService\.EXTRA_REDIRECT_BROWSER_PACKAGE, \"com\.android\.chrome\"\)",
    "",
    routing_text,
)
routing_text = re.sub(
    r"\n\s*assertThat\(\n\s*routed\.getStringExtra\(BlockingAccessibilityService\.EXTRA_REDIRECT_BROWSER_PACKAGE\)\n\s*\)\.isEqualTo\(\"com\.android\.chrome\"\)",
    "",
    routing_text,
)
write(routing, routing_text)

# Documentation: transaction guard now belongs to the redirection package.
project_map = ROOT / ".agents/project_map.md"
project_text = read(project_map)
project_text = project_text.replace(
    "- `accessibility/website/redirection/WebsiteRedirectionCoordinator`: dono da transação de redirecionamento após a apresentação; controla tentativas na mesma aba, retry limitado, confirmação, terminal estrito e fail-closed. Também contém `WebsiteTabNeutralizationPolicy`.\n",
    "- `accessibility/website/redirection/WebsiteRedirectionCoordinator`: dono da sequência de redirecionamento após a apresentação; controla tentativas na mesma aba, retry limitado, confirmação, terminal estrito e fail-closed. `WebsiteBlockTransitionHandle`/`WebsiteBlockTransitionGuard` mantêm o estado da transação no mesmo módulo, e `WebsiteTabNeutralizationPolicy` fixa as ações à janela original.\n",
)
write(project_map, project_text)

# Final invariant scan. No production/test code may retain the removed paths.
forbidden = (
    "EXTRA_REDIRECT_BROWSER_PACKAGE",
    "requestSafeRedirectThroughBrowserIntent",
    "supportsCapabilityBasedIntentRedirectFallback",
    "createSafeBrowserRedirectIntent",
    "WebsiteCloseFollowUp",
    "afterChromiumCloseAttempt",
    "isClosedSurfaceConfirmed",
    "markCloseClicked",
    "markCloseConfirmed",
    "rebindPostCloseRedirectWindow",
    "rebindExternalRedirectWindow",
    "externalRedirectRequested",
    "ALLOW_EXTERNAL_BROWSER_INTENT_FALLBACK",
    "BlockingAccessibilityService.WebsiteBlockTransitionGuard",
    "BlockingAccessibilityService.WebsiteBlockTransitionHandle",
)
violations = []
for base in (ROOT / "app/src/main/java", ROOT / "app/src/test/java"):
    for path in base.rglob("*.kt"):
        body = read(path)
        for token in forbidden:
            if token in body:
                violations.append(f"{path.relative_to(ROOT)}: {token}")
if violations:
    raise RuntimeError("forbidden remnants remain:\n" + "\n".join(violations))

print("Website blocking finalization codemod completed successfully.")
