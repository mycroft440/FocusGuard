#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def load(path):
    return (ROOT / path).read_text(encoding="utf-8")


def save(path, text):
    (ROOT / path).write_text(text, encoding="utf-8")
    print(f"patched {path}")


def require(text, needle, path):
    if needle not in text:
        raise RuntimeError(f"Expected pattern not found in {path}: {needle!r}")


def replace_once(path, old, new):
    text = load(path)
    if new in text:
        return
    require(text, old, path)
    text = text.replace(old, new, 1)
    save(path, text)


def add_import(path, import_line):
    text = load(path)
    line = f"import {import_line}"
    if line in text:
        return
    package_end = text.find("\n\n")
    if package_end < 0:
        raise RuntimeError(f"No package header in {path}")
    insert_at = package_end + 2
    text = text[:insert_at] + line + "\n" + text[insert_at:]
    save(path, text)


def matching_brace(text, opening):
    depth = 0
    for i in range(opening, len(text)):
        c = text[i]
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return i
    raise RuntimeError("Unbalanced braces")


def wrap_callback(path, anchor, gate_builder, split_before=None):
    text = load(path)
    start = text.find(anchor)
    if start < 0:
        # Already-patched files contain the coordinator near the original callback.
        if "RewardedGateCoordinator.launch" in text:
            return
        raise RuntimeError(f"Callback anchor not found in {path}: {anchor}")
    opening = text.find("{", start, start + len(anchor))
    closing = matching_brace(text, opening)
    arrow = text.find("->", opening, closing)
    if arrow < 0:
        raise RuntimeError(f"Lambda arrow not found in {path}")
    header = text[start:arrow + 2]
    body = text[arrow + 2:closing]
    line_start = text.rfind("\n", 0, start) + 1
    indent = text[line_start:start]
    inner = indent + "    "

    preserved = ""
    action_body = body
    if split_before:
        split_at = body.find(split_before)
        if split_at < 0:
            raise RuntimeError(f"Split marker not found in {path}: {split_before!r}")
        preserved = body[:split_at]
        action_body = body[split_at:]

    gate = gate_builder(inner)
    replacement = (
        header
        + preserved
        + "\n"
        + inner + "val monetizedAction: () -> Unit = {"
        + action_body
        + "\n" + inner + "}\n"
        + gate
        + "\n" + indent + "}"
    )
    text = text[:start] + replacement + text[closing + 1:]
    save(path, text)


# GMA Next-Gen SDK dependency.
versions = "gradle/libs.versions.toml"
text = load(versions)
if "googleMobileAds =" not in text:
    require(text, "[versions]\n", versions)
    text = text.replace("[versions]\n", "[versions]\ngoogleMobileAds = \"1.4.0\"\n", 1)
if "google-mobile-ads =" not in text:
    require(text, "[libraries]\n", versions)
    text = text.replace(
        "[libraries]\n",
        "[libraries]\ngoogle-mobile-ads = { module = \"com.google.android.libraries.ads.mobile.sdk:ads-mobile-sdk\", version.ref = \"googleMobileAds\" }\n",
        1,
    )
save(versions, text)

build = "app/build.gradle.kts"
text = load(build)
if "implementation(libs.google.mobile.ads)" not in text:
    require(text, "dependencies {", build)
    text = text.replace(
        "dependencies {",
        "dependencies {\n    implementation(libs.google.mobile.ads)",
        1,
    )
    save(build, text)

# Warm ads only after credential-protected storage is available.
app = "app/src/main/java/com/focusguard/FocusGuardApplication.kt"
add_import(app, "com.focusguard.monetization.FocusGuardAds")
replace_once(
    app,
    "        if (userUnlocked) {\n            // Reaplica as políticas oficiais",
    "        if (userUnlocked) {\n            FocusGuardAds.warmUp(this)\n            // Reaplica as políticas oficiais",
)

# Persist exactly one pending interstitial when the entire Pomodoro plan finishes.
pomodoro = "app/src/main/java/com/focusguard/manager/PomodoroManager.kt"
add_import(pomodoro, "com.focusguard.monetization.MonetizationStateStore")
text = load(pomodoro)
if "markPomodoroCompletionAdPending" not in text:
    require(text, "_onSessionFinished.tryEmit(Unit)", pomodoro)
    text = text.replace(
        "_onSessionFinished.tryEmit(Unit)",
        "MonetizationStateStore.markPomodoroCompletionAdPending(context)\n            _onSessionFinished.tryEmit(Unit)",
        1,
    )
    save(pomodoro, text)

# Show pending Pomodoro interstitial in foreground, including completion while already open.
main = "app/src/main/java/com/focusguard/MainActivity.kt"
add_import(main, "com.focusguard.monetization.FocusGuardAds")
text = load(main)
collector = """        lifecycleScope.launch {\n            repeatOnLifecycle(Lifecycle.State.RESUMED) {\n                pomodoroManager.onSessionFinished.collect {\n                    FocusGuardAds.showPendingPomodoroCompletion(this@MainActivity)\n                }\n            }\n        }\n"""
if "pomodoroManager.onSessionFinished.collect" not in text:
    marker = "        pomodoroManager = PomodoroManager.getInstance(applicationContext)\n"
    require(text, marker, main)
    text = text.replace(marker, marker + collector, 1)
if "showPendingPomodoroCompletion(this@MainActivity) }" not in text:
    marker = "        activityResumed = true\n"
    require(text, marker, main)
    text = text.replace(
        marker,
        marker + "        window.decorView.post { FocusGuardAds.showPendingPomodoroCompletion(this@MainActivity) }\n",
        1,
    )
save(main, text)

# One rewarded ad for every app limit beyond the first app.
limits = "app/src/main/java/com/focusguard/ui/compose/screens/UsageLimitsScreen.kt"
add_import(limits, "com.focusguard.monetization.MonetizationPolicy")
add_import(limits, "com.focusguard.monetization.RewardedGateCoordinator")


def app_limit_gate(i):
    return f"""{i}val targetAlreadyConfigured = selectedApp!!.currentLimitMinutes != null\n{i}val isCreatingLimit = minutes != null && minutes > 0 && !targetAlreadyConfigured\n{i}val configuredCount = apps.count {{ it.currentLimitMinutes != null }}\n{i}if (isCreatingLimit && MonetizationPolicy.requiresExtraUsageLimitAd(configuredCount, targetAlreadyConfigured)) {{\n{i}    RewardedGateCoordinator.launch(\n{i}        context = context,\n{i}        requiredAds = 1,\n{i}        title = \"Adicionar mais um aplicativo\",\n{i}        description = \"Assista a 1 anúncio para adicionar este aplicativo ao limite diário.\",\n{i}        action = monetizedAction\n{i}    )\n{i}}} else {{\n{i}    monetizedAction()\n{i}}}"""

wrap_callback(
    limits,
    "onSave = { minutes, enabled, lockMode, lockPassword, lockUntil ->",
    app_limit_gate,
)

# One rewarded ad for every website limit beyond the first website.
def site_limit_gate(i):
    return f"""{i}val normalizedTarget = WebsiteBlocker.normalizeRule(domain)\n{i}val targetAlreadyConfigured = sites.any {{ WebsiteBlocker.normalizeRule(it.domain) == normalizedTarget }}\n{i}val isCreatingLimit = normalizedTarget.isNotBlank() && minutes > 0 && !targetAlreadyConfigured\n{i}if (isCreatingLimit && MonetizationPolicy.requiresExtraUsageLimitAd(sites.size, targetAlreadyConfigured)) {{\n{i}    RewardedGateCoordinator.launch(\n{i}        context = context,\n{i}        requiredAds = 1,\n{i}        title = \"Adicionar mais um site\",\n{i}        description = \"Assista a 1 anúncio para adicionar este site ao limite diário.\",\n{i}        action = monetizedAction\n{i}    )\n{i}}} else {{\n{i}    monetizedAction()\n{i}}}"""

wrap_callback(
    limits,
    "onSave = { domain, minutes, lockMode, _, lockUntil ->",
    site_limit_gate,
)

# Three explicit rewarded ads per new no-password timed block.
time_screen = "app/src/main/java/com/focusguard/ui/compose/screens/TimeSessionConfigScreen.kt"
add_import(time_screen, "com.focusguard.monetization.MonetizationPolicy")
add_import(time_screen, "com.focusguard.monetization.RewardedGateCoordinator")


def time_block_gate(i):
    return f"""{i}if (config.limitType == LimitType.HARD_BLOCK_NO_PASSWORD) {{\n{i}    RewardedGateCoordinator.launch(\n{i}        context = context,\n{i}        requiredAds = MonetizationPolicy.TIME_BLOCK_REWARDED_ADS,\n{i}        title = \"Ativar bloqueio sem senha\",\n{i}        description = \"Assista a 3 anúncios para criar este bloqueio por tempo.\",\n{i}        action = monetizedAction\n{i}    )\n{i}}} else {{\n{i}    monetizedAction()\n{i}}}"""

wrap_callback(
    time_screen,
    "onSave = { config ->",
    time_block_gate,
    split_before="\n\n            scope.launch(Dispatchers.IO) {",
)

# Route app-limit hits to before/after usage stats after the block surface is safely drawn.
notice = "app/src/main/java/com/focusguard/ui/BlockNoticeActivity.kt"
add_import(notice, "com.focusguard.usage.UsageImpactRouter")
text = load(notice)
if "onGoToUsageImpact = ::goToUsageImpact" not in text:
    old = "                    onGoToPomodoroLock = ::goToPomodoroLock\n"
    require(text, old, notice)
    text = text.replace(
        old,
        "                    onGoToPomodoroLock = ::goToPomodoroLock,\n                    onGoToUsageImpact = ::goToUsageImpact\n",
        1,
    )
if "private fun goToUsageImpact(packageName: String)" not in text:
    marker = "    private fun goHome() {\n"
    require(text, marker, notice)
    method = """    private fun goToUsageImpact(packageName: String) {\n        startActivity(UsageImpactActivity.createIntent(this, packageName))\n        finish()\n    }\n\n"""
    text = text.replace(marker, method + marker, 1)
if "onGoToUsageImpact: (String) -> Unit" not in text:
    old = "    onGoToPomodoroLock: () -> Unit\n) {"
    require(text, old, notice)
    text = text.replace(
        old,
        "    onGoToPomodoroLock: () -> Unit,\n    onGoToUsageImpact: (String) -> Unit\n) {",
        1,
    )
if "UsageImpactRouter.shouldShowForBlockedApp" not in text:
    marker = "    val context = LocalContext.current\n"
    require(text, marker, notice)
    effect = """    LaunchedEffect(strictBlock, blockedPackage, blockedDomain) {\n        val packageToInspect = blockedPackage\n        if (!strictBlock && blockedDomain == null && !packageToInspect.isNullOrBlank() &&\n            UsageImpactRouter.shouldShowForBlockedApp(context, packageToInspect)\n        ) {\n            delay(650L)\n            onGoToUsageImpact(packageToInspect)\n        }\n    }\n"""
    text = text.replace(marker, marker + effect, 1)
save(notice, text)

# Register the dedicated rewarded gate and usage-impact activities.
manifest = "app/src/main/AndroidManifest.xml"
text = load(manifest)
if ".ui.RewardedGateActivity" not in text:
    anchor = "        <activity\n            android:name=\".ui.BlockNoticeActivity\""
    require(text, anchor, manifest)
    activities = """        <activity\n            android:name=\".ui.RewardedGateActivity\"\n            android:exported=\"false\"\n            android:excludeFromRecents=\"true\"\n            android:theme=\"@style/Theme.FocusGuard\" />\n\n        <activity\n            android:name=\".ui.UsageImpactActivity\"\n            android:exported=\"false\"\n            android:excludeFromRecents=\"true\"\n            android:theme=\"@style/Theme.FocusGuard\" />\n\n"""
    text = text.replace(anchor, activities + anchor, 1)
    save(manifest, text)

print("Monetization integration patch completed successfully.")
