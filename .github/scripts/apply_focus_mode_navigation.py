from pathlib import Path

path = Path("app/src/main/java/com/focusguard/service/BlockingAccessibilityService.kt")
text = path.read_text(encoding="utf-8")

replacements = [
    (
        "import android.view.Gravity\nimport android.view.View\n",
        "import android.view.Gravity\nimport android.view.KeyEvent\nimport android.view.View\n",
    ),
    (
        "import com.focusguard.focusmode.FocusModePolicy\nimport com.focusguard.focusmode.FocusModeStore\n",
        "import com.focusguard.focusmode.FocusModeKioskController\nimport com.focusguard.focusmode.FocusModePolicy\nimport com.focusguard.focusmode.FocusModeStore\n",
    ),
    (
        "            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or\n"
        "                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or\n"
        "                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS\n",
        "            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or\n"
        "                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or\n"
        "                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or\n"
        "                AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS\n",
    ),
    (
        "            val focusLauncherMustReturn = focusModeFallbackActive &&\n"
        "                packageName == defaultLauncherPackage\n",
        "            val focusLauncherMustReturn = focusModeSessionActive &&\n"
        "                packageName == defaultLauncherPackage\n",
    ),
    (
        "                launcherPackage = defaultLauncherPackage,\n"
        "                focusModeBlockedPackages = focusModeBlockedAppsSet\n"
        "            )\n",
        "                launcherPackage = defaultLauncherPackage,\n"
        "                focusModeBlockedPackages = focusModeBlockedAppsSet,\n"
        "                focusModeActive = focusModeSessionActive\n"
        "            )\n",
    ),
    (
        "                    putExtra(EXTRA_CURTAIN_GENERATION, generation)\n"
        "                    putExtra(EXTRA_BLOCK_EVENT_UPTIME_MILLIS, eventUptimeMillis)\n",
        "                    putExtra(EXTRA_CURTAIN_GENERATION, generation)\n"
        "                    putExtra(EXTRA_BLOCK_EVENT_UPTIME_MILLIS, eventUptimeMillis)\n"
        "                    putExtra(FocusModeKioskController.EXTRA_RESTORE_FOCUS_MODE, true)\n",
    ),
    (
        "            \"Modo consumidor redirecionou $blockedPackage para o FocusGuard\"\n",
        "            \"Modo Foco redirecionou $blockedPackage para o HardBlock\"\n",
    ),
]

for old, new in replacements:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected exactly one match, found {count}: {old[:100]!r}")
    text = text.replace(old, new, 1)

anchor = """    override fun onInterrupt() {
        foregroundPackageName = null
        stopWebsiteTracking()
        // onInterrupt stops accessibility feedback; it does not prove that an
        // Android-owned power window disappeared. Keep shielding until the
        // controller confirms absence through its normal window recheck.
        protectedPowerMenuController?.onFeedbackInterrupted()
    }

"""
if text.count(anchor) != 1:
    raise SystemExit("Could not locate onInterrupt anchor")

key_handler = anchor + """    override fun onKeyEvent(event: KeyEvent): Boolean {
        val isBackOrHomeKey = event.keyCode == KeyEvent.KEYCODE_BACK ||
            event.keyCode == KeyEvent.KEYCODE_HOME
        if (!isBackOrHomeKey) return false

        // The device-protected store is the fail-closed source during process
        // recreation and immediately after boot; the volatile flag is the fast path.
        val focusModeActiveNow = focusModeSessionActive ||
            FocusModeStore.isActive(applicationContext)
        if (focusModeActiveNow && !focusModeSessionActive) {
            refreshFocusModeFallbackState()
        }

        return when (FocusModePolicy.focusNavigationKeyDecision(
            focusModeActive = focusModeActiveNow,
            focusGuardForeground = foregroundPackageName == packageName,
            powerMenuVisible = protectedPowerMenuController?.isVisible() == true,
            isBackOrHomeKey = true,
            actionDown = event.action == KeyEvent.ACTION_DOWN,
            repeatCount = event.repeatCount
        )) {
            FocusModePolicy.NavigationKeyDecision.PASS -> false
            FocusModePolicy.NavigationKeyDecision.CONSUME -> true
            FocusModePolicy.NavigationKeyDecision.RETURN_TO_FOCUS_GUARD -> {
                val generation = showInstantBlockCurtain(mode = CurtainMode.BLOCK_NOTICE)
                awaitingSafeSurfaceGeneration = generation
                val restored = FocusModeKioskController.launchFocusGuardHome(this)
                if (!restored) beginCurtainEvacuationBeforeHide(generation)
                true
            }
        }
    }

"""
text = text.replace(anchor, key_handler, 1)
path.write_text(text, encoding="utf-8")
