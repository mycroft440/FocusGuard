from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]
TEST_ROOT = ROOT / "app/src/test/java/com/focusguard/service"


def update(path: Path, fn):
    text = path.read_text()
    new = fn(text)
    if new == text:
        raise RuntimeError(f"No changes applied to {path}")
    path.write_text(new)


def browser_transition(text: str) -> str:
    if "WebsiteTabNeutralizationPolicy" not in text.split("class BrowserTransitionRaceTest", 1)[0]:
        text = text.replace(
            "import com.focusguard.accessibility.website.redirection.AddressBarRedirectionActions\n",
            "import com.focusguard.accessibility.website.redirection.AddressBarRedirectionActions\n"
            "import com.focusguard.accessibility.website.redirection.WebsiteTabNeutralizationPolicy\n",
            1,
        )
    return text.replace(
        "BlockingAccessibilityService.WebsiteTabNeutralizationPolicy",
        "WebsiteTabNeutralizationPolicy",
    )


def external_neutralization(text: str) -> str:
    text = text.replace(
        "import com.focusguard.service.BlockingAccessibilityService.WebsiteTransitionDestination\n",
        "import com.focusguard.accessibility.website.redirection.WebsiteRedirectionCoordinator\n",
        1,
    )
    text = text.replace(
        "WebsiteTransitionDestination.GOOGLE",
        "WebsiteRedirectionCoordinator.TerminalDestination.REDIRECT",
    )
    text = text.replace(
        "WebsiteTransitionDestination.POMODORO",
        "WebsiteRedirectionCoordinator.TerminalDestination.POMODORO",
    )
    return text


def navigation(text: str) -> str:
    prefix, rest = text.split("class WebsiteBlockNavigationTest", 1)
    if "WebsiteTabNeutralizationPolicy" not in prefix:
        prefix = prefix.replace(
            "import com.focusguard.accessibility.website.redirection.WebsiteRedirectionCoordinator\n",
            "import com.focusguard.accessibility.website.redirection.WebsiteRedirectionCoordinator\n"
            "import com.focusguard.accessibility.website.redirection.WebsiteTabNeutralizationPolicy\n",
            1,
        )
    text = prefix + "class WebsiteBlockNavigationTest" + rest

    text = text.replace(
        "BlockingAccessibilityService.WebsiteTabNeutralizationPolicy",
        "WebsiteTabNeutralizationPolicy",
    )
    text = text.replace(
        "BlockingAccessibilityService.WebsiteTransitionAction.values()",
        "WebsiteRedirectionCoordinator.Action.values()",
    )
    text = text.replace(
        "BlockingAccessibilityService.WebsiteBlockTransitionStateMachine(",
        "WebsiteRedirectionCoordinator.Session(",
    )
    text = text.replace(
        "BlockingAccessibilityService.WebsiteTransitionAction.SHOW_CURTAIN",
        "WebsiteRedirectionCoordinator.Action.SHOW_BLOCK_PRESENTATION",
    )
    text = text.replace(
        "BlockingAccessibilityService.WebsiteTransitionAction.NEUTRALIZE_BLOCKED_TAB",
        "WebsiteRedirectionCoordinator.Action.NEUTRALIZE_BLOCKED_TAB",
    )
    text = text.replace(
        "BlockingAccessibilityService.WebsiteTransitionAction.HIDE_CURTAIN",
        "WebsiteRedirectionCoordinator.Action.HIDE_BLOCK_PRESENTATION",
    )
    text = text.replace(
        "BlockingAccessibilityService.WebsiteTransitionAction.OPEN_POMODORO",
        "WebsiteRedirectionCoordinator.Action.OPEN_POMODORO",
    )
    text = text.replace("afterGoogleSanitized()", "afterRedirectConfirmed()")

    # These old service-state-machine tests assert a failure return action that no
    # longer exists: fail-closed is now exercised end-to-end by the coordinator's
    # Adapter in WebsiteRedirectionCoordinatorTest.
    obsolete_names = [
        "confirmation timeout keeps the browser open and releases only the curtain",
        "sanitization or destination failure never requests browser eviction",
    ]
    for name in obsolete_names:
        pattern = re.compile(
            r'\n    @Test\n    fun `' + re.escape(name) + r'`\(\) \{.*?\n    \}\n',
            re.S,
        )
        text, count = pattern.subn("\n", text, count=1)
        if count != 1:
            raise RuntimeError(f"Could not remove obsolete test: {name}")

    return text


update(TEST_ROOT / "BrowserTransitionRaceTest.kt", browser_transition)
update(TEST_ROOT / "ExternalRedirectNeutralizationTest.kt", external_neutralization)
update(TEST_ROOT / "WebsiteBlockNavigationTest.kt", navigation)

print("Website redirect tests migrated")
