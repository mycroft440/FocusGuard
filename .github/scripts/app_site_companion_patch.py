from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"{label} pattern not found")
    return text.replace(old, new, 1)


def replace_between(text: str, start: str, end: str, replacement: str, label: str) -> str:
    start_index = text.find(start)
    if start_index < 0:
        raise SystemExit(f"{label} start not found")
    end_index = text.find(end, start_index)
    if end_index < 0:
        raise SystemExit(f"{label} end not found")
    return text[:start_index] + replacement + text[end_index:]


create = Path("app/src/main/java/com/focusguard/ui/CreateSessionActivity.kt")
text = create.read_text()
text = text.replace("import androidx.compose.material3.RadioButton\n", "")
if "import com.focusguard.ui.compose.screens.AssociatedTargetOptionDialog\n" not in text:
    text = replace_once(
        text,
        "import com.focusguard.ui.compose.screens.AppSelectionList\n",
        "import com.focusguard.ui.compose.screens.AppSelectionList\n"
        "import com.focusguard.ui.compose.screens.AssociatedTargetOptionDialog\n",
        "shared dialog import",
    )

text = replace_once(
    text,
    "    kinds: BlockTargetPolicy.Kinds = BlockTargetPolicy.APPS_ONLY,\n"
    "    initialRules: List<String> = emptyList()\n"
    ") {",
    "    kinds: BlockTargetPolicy.Kinds = BlockTargetPolicy.APPS_ONLY,\n"
    "    initialRules: List<String> = emptyList(),\n"
    "    offerWebsiteCompanion: Boolean = kinds.websites\n"
    ") {",
    "AppSelectionStep signature",
)
text = replace_once(
    text,
    "if (!wasSelected && kinds.websites) {",
    "if (!wasSelected && offerWebsiteCompanion) {",
    "app companion condition",
)
text = replace_once(
    text,
    'title = "Bloquear site do app também?",\n'
    '                message = "Você bloqueou ${appInfo.appName}. Bloquear $domain também?",',
    "title = stringResource(R.string.associated_target_block_site_title),\n"
    "                message = stringResource(\n"
    "                    R.string.associated_target_block_site_message,\n"
    "                    appInfo.appName,\n"
    "                    domain\n"
    "                ),",
    "app-to-site resource strings",
)
text = replace_once(
    text,
    'title = "Bloquear app do site também?",\n'
    '            message = "Você bloqueou ${WebsiteBlocker.displayRule(rule)}. " +\n'
    '                "Bloquear ${appInfo.appName} também?",',
    "title = stringResource(R.string.associated_target_block_app_title),\n"
    "            message = stringResource(\n"
    "                R.string.associated_target_block_app_message,\n"
    "                WebsiteBlocker.displayRule(rule),\n"
    "                appInfo.appName\n"
    "            ),",
    "site-to-app resource strings",
)
text = replace_between(
    text,
    "\n@Composable\nprivate fun AssociatedTargetOptionDialog(",
    "\nprivate enum class BlockTargetTab",
    "",
    "local dialog",
)
create.write_text(text)

setup = Path("app/src/main/java/com/focusguard/ui/compose/screens/ProtectionSetupScreen.kt")
text = setup.read_text()
if "import com.focusguard.data.PredefinedApps\n" not in text:
    text = replace_once(
        text,
        "import com.focusguard.data.PredefinedWebsites\n",
        "import com.focusguard.data.PredefinedApps\nimport com.focusguard.data.PredefinedWebsites\n",
        "PredefinedApps import",
    )
if "import com.focusguard.utils.AssociatedBlockTargets\n" not in text:
    text = replace_once(
        text,
        "import com.focusguard.utils.FocusGuardLogger\n",
        "import com.focusguard.utils.AssociatedBlockTargets\nimport com.focusguard.utils.FocusGuardLogger\n",
        "AssociatedBlockTargets import",
    )

app_picker_start = "            ProtectionSetupPage.APP_PICKER -> AppSelectionStep("
website_picker_start = "            ProtectionSetupPage.WEBSITE_PICKER -> WebsiteRuleSelectionScreen("
new_app_picker = '''            ProtectionSetupPage.APP_PICKER -> AppSelectionStep(
                onNext = { apps, companionRules ->
                    scope.launch {
                        val latest = refreshConfiguredBlockedTargets()
                        val availableApps = apps.filterNot {
                            it.packageName in latest.unavailableAppPackageNames
                        }
                        val availableRules = companionRules.filterNot {
                            isWebsiteRuleAlreadyBlocked(it, latest.unavailableWebsiteRules)
                        }
                        if (availableApps.size != apps.size) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.app_already_blocked),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        if (availableRules.size != companionRules.size) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.site_already_blocked),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        selectedApps = availableApps
                        websiteRules = availableRules
                        returnToList()
                    }
                },
                onBack = ::returnToList,
                initialSelectedPackages = selectedApps.mapTo(linkedSetOf()) { it.packageName },
                initialRules = websiteRules,
                allowCompatibleProtection = true,
                offerWebsiteCompanion = true
            )

'''
text = replace_between(
    text,
    app_picker_start,
    website_picker_start,
    new_app_picker,
    "Unified APP_PICKER",
)

text = replace_once(
    text,
    "            ProtectionSetupPage.WEBSITE_PICKER -> WebsiteRuleSelectionScreen(\n"
    "                initialRules = websiteRules,\n"
    "                configuredBlockedRules = configuredBlockedTargets.unavailableWebsiteRules,\n"
    "                onSave = { rules ->",
    "            ProtectionSetupPage.WEBSITE_PICKER -> WebsiteRuleSelectionScreen(\n"
    "                initialRules = websiteRules,\n"
    "                configuredBlockedRules = configuredBlockedTargets.unavailableWebsiteRules,\n"
    "                selectedAppPackages = selectedApps.mapTo(linkedSetOf()) { it.packageName },\n"
    "                configuredBlockedPackages = configuredBlockedTargets.unavailableAppPackageNames,\n"
    "                onCompanionAppSelected = { appInfo ->\n"
    "                    if (selectedApps.none { it.packageName == appInfo.packageName } &&\n"
    "                        appInfo.packageName !in configuredBlockedTargets.unavailableAppPackageNames\n"
    "                    ) {\n"
    "                        selectedApps = selectedApps + SelectableAppUi(\n"
    "                            packageName = appInfo.packageName,\n"
    "                            appName = appInfo.appName,\n"
    "                            isSelected = true,\n"
    "                            isInstalled = context.packageManager\n"
    "                                .getLaunchIntentForPackage(appInfo.packageName) != null,\n"
    "                            category = appInfo.category,\n"
    "                            iconUrl = appInfo.domain?.let { domain ->\n"
    "                                \"https://www.google.com/s2/favicons?domain=$domain&sz=128\"\n"
    "                            }\n"
    "                        )\n"
    "                    }\n"
    "                },\n"
    "                onSave = { rules ->",
    "Unified WEBSITE_PICKER call",
)

text = replace_once(
    text,
    "private fun WebsiteRuleSelectionScreen(\n"
    "    initialRules: List<String>,\n"
    "    configuredBlockedRules: Set<String>,\n"
    "    onSave: (List<String>) -> Unit,\n"
    "    onBack: () -> Unit\n"
    ") {",
    "private fun WebsiteRuleSelectionScreen(\n"
    "    initialRules: List<String>,\n"
    "    configuredBlockedRules: Set<String>,\n"
    "    selectedAppPackages: Set<String>,\n"
    "    configuredBlockedPackages: Set<String>,\n"
    "    onCompanionAppSelected: (PredefinedApps.AppInfo) -> Unit,\n"
    "    onSave: (List<String>) -> Unit,\n"
    "    onBack: () -> Unit\n"
    ") {",
    "WebsiteRuleSelectionScreen signature",
)
text = replace_once(
    text,
    "    var invalidInput by remember { mutableStateOf(false) }\n\n"
    "    fun addRule(value: String) {",
    "    var invalidInput by remember { mutableStateOf(false) }\n"
    "    var pendingCompanionApp by remember {\n"
    "        mutableStateOf<Pair<String, PredefinedApps.AppInfo>?>(null)\n"
    "    }\n\n"
    "    fun maybeOfferCompanionApp(rule: String) {\n"
    "        val appInfo = AssociatedBlockTargets.appForWebsiteRule(rule) ?: return\n"
    "        if (appInfo.packageName in selectedAppPackages ||\n"
    "            appInfo.packageName in configuredBlockedPackages\n"
    "        ) return\n"
    "        pendingCompanionApp = WebsiteBlocker.normalizeRule(rule) to appInfo\n"
    "    }\n\n"
    "    fun addRule(value: String) {",
    "WebsiteRuleSelectionScreen state",
)
text = replace_once(
    text,
    "        rules = (rules + normalized).distinct()\n"
    '        input = ""\n'
    "        invalidInput = false\n"
    "    }",
    "        rules = (rules + normalized).distinct()\n"
    '        input = ""\n'
    "        invalidInput = false\n"
    "        maybeOfferCompanionApp(normalized)\n"
    "    }",
    "custom website add companion prompt",
)

toggle_start = "    fun togglePresetRule(rule: String) {"
scaffold_start = "\n\n    Scaffold("
new_toggle = '''    fun togglePresetRule(rule: String) {
        val normalized = WebsiteBlocker.normalizeRule(rule)
        if (isWebsiteRuleAlreadyBlocked(normalized, configuredBlockedRules)) {
            rules = rules.filterNot {
                WebsiteBlocker.normalizeRule(it) == normalized
            }
            Toast.makeText(
                context,
                context.getString(R.string.site_already_blocked),
                Toast.LENGTH_SHORT
            ).show()
        } else {
            val wasSelected = normalized in rules
            rules = if (wasSelected) {
                rules.filterNot { it == normalized }
            } else {
                (rules + normalized).distinct()
            }
            if (!wasSelected) maybeOfferCompanionApp(normalized)
        }
    }

    pendingCompanionApp?.let { (rule, appInfo) ->
        AssociatedTargetOptionDialog(
            title = stringResource(R.string.associated_target_block_app_title),
            message = stringResource(
                R.string.associated_target_block_app_message,
                WebsiteBlocker.displayRule(rule),
                appInfo.appName
            ),
            onDecision = { blockAlso ->
                if (blockAlso) onCompanionAppSelected(appInfo)
                pendingCompanionApp = null
            }
        )
    }'''
text = replace_between(
    text,
    toggle_start,
    scaffold_start,
    new_toggle,
    "preset website companion prompt",
)
setup.write_text(text)
