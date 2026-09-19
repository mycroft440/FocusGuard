from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
path = ROOT / "app/src/main/java/com/focusguard/service/BlockingAccessibilityService.kt"
text = path.read_text(encoding="utf-8")

old = '''                            val setRequestedAt = websiteTreeWorker.run {
                                prepareSafeAddressBar(
                                    browserPackageName = browserPackageName,
                                    expectedWindowId = transition.expectedWindowId,
                                    policy = policy,
                                    transition = transition,
                                    phaseStartedAtUptimeMillis = transition.detectionEventUptimeMillis
                                )
                            }
'''
new = '''                            val phaseStartedAtUptimeMillis = SystemClock.uptimeMillis()
                            val setRequestedAt = websiteTreeWorker.run {
                                prepareSafeAddressBar(
                                    browserPackageName = browserPackageName,
                                    expectedWindowId = transition.expectedWindowId,
                                    policy = policy,
                                    transition = transition,
                                    phaseStartedAtUptimeMillis = phaseStartedAtUptimeMillis
                                )
                            }
'''
if text.count(old) != 1:
    raise RuntimeError(f"attempt phase block match count={text.count(old)}")
text = text.replace(old, new, 1)

old = '''                val editRoot = activeBrowserRoot(browserPackageName, expectedWindowId) ?: return 0L
                val writtenAt = SystemClock.uptimeMillis()
                val written = try {
'''
new = '''                val editRoot = activeBrowserRoot(browserPackageName, expectedWindowId) ?: return 0L
                val written = try {
'''
if text.count(old) != 1:
    raise RuntimeError(f"writtenAt block match count={text.count(old)}")
text = text.replace(old, new, 1)

old = '''                        BrowserCompatibilityStore.recordWriteSuccess(browserPackageName, written.selectedViewId, method, WebsiteRedirectDestination.current.url)
                        return writtenAt
'''
new = '''                        BrowserCompatibilityStore.recordWriteSuccess(browserPackageName, written.selectedViewId, method, WebsiteRedirectDestination.current.url)
                        // The submission epoch starts from a positively re-read safe editor,
                        // not from the earlier mutation request.
                        return SystemClock.uptimeMillis()
'''
if text.count(old) != 1:
    raise RuntimeError(f"verified return block match count={text.count(old)}")
text = text.replace(old, new, 1)

path.write_text(text, encoding="utf-8")
print("redirect attempt epoch fixed")
