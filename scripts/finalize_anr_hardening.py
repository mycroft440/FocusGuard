from pathlib import Path

path = Path("app/src/main/java/com/focusguard/service/BlockingAccessibilityService.kt")
text = path.read_text(encoding="utf-8")

old_constants = '''        internal const val UNSAFE_WINDOW_RECHECK_MILLIS = 240L
        internal const val EVENT_NOTIFICATION_TIMEOUT_MILLIS = 0L
'''
new_constants = '''        internal const val UNSAFE_WINDOW_RECHECK_MILLIS = 240L
        private const val SLOW_ACCESSIBILITY_CALLBACK_MILLIS = 250L
        private const val SLOW_CALLBACK_LOG_INTERVAL_MILLIS = 5_000L
        private const val UNKNOWN_BROWSER_DISCOVERY_RETRY_MILLIS = 750L
        private const val MAX_BROWSER_DISCOVERY_MISSES = 32
        internal const val EVENT_NOTIFICATION_TIMEOUT_MILLIS = 0L
'''
if text.count(old_constants) != 1:
    raise SystemExit("Expected constants insertion point exactly once")
text = text.replace(old_constants, new_constants, 1)

old_cleanup = '''                            if (
                                blockedWebsiteDomains.isEmpty() &&
                                hardConfiguredWebsiteDomains.isEmpty()
                            ) {
                                opaqueBrowserFirstSeenElapsed.clear()
                                opaqueBrowserWindowIds.clear()
                                opaqueBrowserVerificationScheduled.clear()
                                opaqueBrowserRecoveriesRunning.clear()
                            }
                            activeAppLimitsByPackage = activeAppLimits.associateBy { it.packageName }
'''
new_cleanup = '''                            if (
                                blockedWebsiteDomains.isEmpty() &&
                                hardConfiguredWebsiteDomains.isEmpty()
                            ) {
                                opaqueBrowserFirstSeenElapsed.clear()
                                opaqueBrowserWindowIds.clear()
                                opaqueBrowserVerificationScheduled.clear()
                                opaqueBrowserRecoveriesRunning.clear()
                            }
                            if (blockedWebsiteDomains.isEmpty() && configuredWebsiteDomains.isEmpty()) {
                                browserDiscoveryMisses.clear()
                            }
                            activeAppLimitsByPackage = activeAppLimits.associateBy { it.packageName }
'''
if text.count(old_cleanup) != 1:
    raise SystemExit("Expected browser-discovery cleanup insertion point exactly once")
text = text.replace(old_cleanup, new_cleanup, 1)

path.write_text(text, encoding="utf-8")
print("Final ANR hardening consistency fix applied")
