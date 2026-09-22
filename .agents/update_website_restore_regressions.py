from pathlib import Path

path = Path("app/src/test/java/com/focusguard/service/WebsiteBlockNavigationTest.kt")
text = path.read_text(encoding="utf-8")
old = '''        assertThat(\n            policy.mayActivateBlockedAddressBar(\n                browserPackage,\n                activeWindowId = 7,\n                phaseStartedAtUptimeMillis = 100L,\n                latestWindowTransitionEventUptimeMillis = 101L\n            )\n        ).isFalse()\n'''
new = '''        assertThat(\n            policy.mayActivateBlockedAddressBar(\n                browserPackage,\n                activeWindowId = 7,\n                phaseStartedAtUptimeMillis = 100L,\n                latestWindowTransitionEventUptimeMillis = 101L\n            )\n        ).isTrue()\n'''
if text.count(old) != 1:
    raise RuntimeError(f"expected one legacy timestamp assertion, found {text.count(old)}")
path.write_text(text.replace(old, new, 1), encoding="utf-8")
