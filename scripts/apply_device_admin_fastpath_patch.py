from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    if new in text:
        return
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected exactly one block in {path}, got {count}: {old[:120]!r}")
    p.write_text(text.replace(old, new, 1))


service = "app/src/main/java/com/focusguard/service/BlockingAccessibilityService.kt"
master = "app/src/main/java/com/focusguard/ui/MasterRemovalActivity.kt"
managed = "app/src/main/java/com/focusguard/security/ManagedSelfProtectionPolicy.kt"
reset_test = "app/src/test/java/com/focusguard/security/ProtectedSettingsResetWindowTest.kt"
immediate_test = "app/src/test/java/com/focusguard/security/ImmediateInterceptionPolicyTest.kt"

replace_once(
    service,
    '''    // One strong app-identity query per ambiguous click; broad localized terms stay in policy fallbacks.\n    private val clickInterceptionSearchTerms = listOf("FocusGuard")\n\n    private var pendingSettingsProtectionUntilElapsed = 0L\n''',
    '''    // One strong app-identity query per ambiguous click; broad localized terms stay in policy fallbacks.\n    private val clickInterceptionSearchTerms = listOf("FocusGuard")\n    // Device Admin is a revocation gateway of its own. These short locator prefixes\n    // are intentionally separate from the broad localized dictionaries so an\n    // ambiguous Settings click can be resolved from the clicked row/parent before\n    // falling back to a root-window scan. One UI normally matches the first term.\n    private val deviceAdminClickSearchTerms =\n        ManagedSelfProtectionPolicy.deviceAdminNodeSearchTerms\n\n    private var pendingSettingsProtectionUntilElapsed = 0L\n'''
)

replace_once(
    service,
    '''        if (!isSystemUi &&\n            ProtectedSettingsResetWindow.isActive(\n                curtainGeneration = awaitingSafeSurfaceGeneration,\n                nowElapsed = nowElapsed\n            )\n        ) return true\n''',
    '''        // The reset window exists only for the programmatic ACTION_SETTINGS\n        // transition created by MasterRemovalActivity. It must NEVER swallow a real\n        // TYPE_VIEW_CLICKED: returning true from this callback does not cancel the\n        // Android click, so the old code created a short re-entry bypass.\n        if (!isSystemUi &&\n            event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED &&\n            ProtectedSettingsResetWindow.isActive(\n                curtainGeneration = awaitingSafeSurfaceGeneration,\n                nowElapsed = nowElapsed\n            )\n        ) return true\n'''
)

replace_once(
    service,
    '''        // The cached inactive state costs no Preferences/Settings read. A DPM\n        // binder call happens only while FocusGuard itself opened enrollment.\n        val deviceAdminActivationAuthorized =\n            DeviceAdminActivationWindow.isPotentiallyAuthorized(this) &&\n                DeviceAdminActivationWindow.isAuthorized(this)\n        val className = event.className?.toString().orEmpty()\n\n        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {\n''',
    '''        // The cached inactive state costs no Preferences/Settings read. A DPM\n        // binder call happens only while FocusGuard itself opened enrollment.\n        val deviceAdminActivationAuthorized =\n            DeviceAdminActivationWindow.isPotentiallyAuthorized(this) &&\n                DeviceAdminActivationWindow.isAuthorized(this)\n        val className = event.className?.toString().orEmpty()\n\n        // Destination fallback for OEMs whose menu-row click exposes no usable text.\n        // Once Android reports a Device Admin Activity/class, bounce immediately —\n        // before source/root reads — and clear the Settings task through the same\n        // master-gate path used by the faster revocation gateways. Legitimate\n        // enrollment initiated by FocusGuard keeps its short authorization window.\n        if (!isSystemUi &&\n            !deviceAdminActivationAuthorized &&\n            event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED &&\n            ManagedSelfProtectionPolicy.classTargetsDeviceAdmin(className)\n        ) {\n            pendingSettingsProtectionUntilElapsed =\n                nowElapsed + SETTINGS_TRANSITION_GUARD_MILLIS\n            val generation = executeProtectionAction(\n                eventTimeUptimeMillis = event.eventTime,\n                eventDeliveredAtUptimeMillis = eventDeliveredAtUptimeMillis,\n                eventDetectedAtNanos = eventDetectedAtNanos,\n                holdUntilSafeSurface = true,\n                forceLauncherFallback = true\n            )\n            launchMasterRemovalGate(MasterRemovalActivity.Target.DEVICE_ADMIN, generation)\n            return true\n        }\n\n        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {\n'''
)

replace_once(
    service,
    '''            val direct = if (isSystemUi) {\n                ImmediateInterceptionPolicy.classifySystemUiClickWithContext(\n                    className = className,\n                    directValues = directValues,\n                    contextualValues = {\n                        eventTextValues(event, forceExpandClickContext = true)\n                    }\n                )\n            } else {\n                ImmediateInterceptionPolicy.classifySettingsClick(\n                    packageName = packageName,\n                    className = className,\n                    values = directValues\n                )\n            }\n''',
    '''            val direct = if (isSystemUi) {\n                ImmediateInterceptionPolicy.classifySystemUiClickWithContext(\n                    className = className,\n                    directValues = directValues,\n                    contextualValues = {\n                        eventTextValues(event, forceExpandClickContext = true)\n                    }\n                )\n            } else {\n                val directResult = ImmediateInterceptionPolicy.classifySettingsClick(\n                    packageName = packageName,\n                    className = className,\n                    values = directValues\n                )\n                if (directResult.decision == DirectDecision.NEED_TREE &&\n                    fastDeviceAdminClickConfirmed(event)\n                ) {\n                    ImmediateInterceptionPolicy.SettingsClickDecision(\n                        DirectDecision.PROTECT,\n                        SettingsSurface.DEVICE_ADMIN\n                    )\n                } else {\n                    directResult\n                }\n            }\n'''
)

replace_once(
    service,
    '''                val generation = executeProtectionAction(\n                    eventTimeUptimeMillis = event.eventTime,\n                    eventDeliveredAtUptimeMillis = eventDeliveredAtUptimeMillis,\n                    eventDetectedAtNanos = eventDetectedAtNanos,\n                    holdUntilSafeSurface = true\n                )\n                launchMasterRemovalGate(target, generation)\n''',
    '''                val generation = executeProtectionAction(\n                    eventTimeUptimeMillis = event.eventTime,\n                    eventDeliveredAtUptimeMillis = eventDeliveredAtUptimeMillis,\n                    eventDetectedAtNanos = eventDetectedAtNanos,\n                    holdUntilSafeSurface = true,\n                    forceLauncherFallback = target == MasterRemovalActivity.Target.DEVICE_ADMIN\n                )\n                launchMasterRemovalGate(target, generation)\n'''
)

replace_once(
    service,
    '''        val masterRemovalTarget = if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {\n            when {\n                signals.textMentionsDeviceAdmin || classTargetsDeviceAdmin ->\n                    MasterRemovalActivity.Target.DEVICE_ADMIN\n''',
    '''        val masterRemovalTarget = when {\n            // A destination-class fallback is still a confirmed Device Admin\n            // removal gateway. Treat it exactly like the click path so Settings is\n            // cleared instead of merely receiving HOME and remaining ready behind it.\n            classTargetsDeviceAdmin && !deviceAdminActivationAuthorized ->\n                MasterRemovalActivity.Target.DEVICE_ADMIN\n            event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED -> when {\n                signals.textMentionsDeviceAdmin || classTargetsDeviceAdmin ->\n                    MasterRemovalActivity.Target.DEVICE_ADMIN\n'''
)

replace_once(
    service,
    '''                signals.textMentionsFocusGuard -> MasterRemovalActivity.Target.APP_INFO\n                else -> null\n            }\n        } else {\n            null\n        }\n\n        val decision = SettingsInterceptionPolicy.decide(\n''',
    '''                signals.textMentionsFocusGuard -> MasterRemovalActivity.Target.APP_INFO\n                else -> null\n            }\n            else -> null\n        }\n\n        val decision = SettingsInterceptionPolicy.decide(\n'''
)

old_policy_call = '''                    eventDetectedAtNanos = eventDetectedAtNanos,\n                    holdUntilSafeSurface = masterRemovalTarget != null\n                )\n'''
new_policy_call = '''                    eventDetectedAtNanos = eventDetectedAtNanos,\n                    holdUntilSafeSurface = masterRemovalTarget != null,\n                    forceLauncherFallback =\n                        masterRemovalTarget == MasterRemovalActivity.Target.DEVICE_ADMIN\n                )\n'''
p = Path(service)
text = p.read_text()
if new_policy_call not in text:
    count = text.count(old_policy_call)
    if count != 2:
        raise SystemExit(f"Expected two policy execute calls, got {count}")
    text = text.replace(old_policy_call, new_policy_call)
    p.write_text(text)

replace_once(
    service,
    '''    private fun sourceNodeForEvent(event: AccessibilityEvent): AccessibilityNodeInfo? =\n        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {\n            event.getSource(0)\n        } else {\n            event.source\n        }\n\n    private fun eventTextValues(\n''',
    '''    private fun sourceNodeForEvent(event: AccessibilityEvent): AccessibilityNodeInfo? =\n        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {\n            event.getSource(0)\n        } else {\n            event.source\n        }\n\n    /**\n     * Bounded Device Admin row probe for textless/generic Settings clicks.\n     *\n     * One UI often reports the clicked row container instead of its visible label.\n     * Search only that subtree and its immediate parent with the short locator\n     * prefixes, stopping at the first confirmed Device Admin match. This avoids the\n     * old broad root scan in the common Samsung path while preserving the general\n     * OEM fallback if neither local probe is enough.\n     */\n    private fun fastDeviceAdminClickConfirmed(event: AccessibilityEvent): Boolean {\n        if (event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED) return false\n        val source = sourceNodeForEvent(event) ?: return false\n        return try {\n            if (nodeTreeMentionsDeviceAdmin(source)) {\n                true\n            } else {\n                val parent = runCatching { source.parent }.getOrNull()\n                try {\n                    parent?.let(::nodeTreeMentionsDeviceAdmin) == true\n                } finally {\n                    recycleSafely(parent)\n                }\n            }\n        } finally {\n            recycleSafely(source)\n        }\n    }\n\n    private fun nodeTreeMentionsDeviceAdmin(node: AccessibilityNodeInfo): Boolean {\n        if (ManagedSelfProtectionPolicy.textTargetsDeviceAdmin(\n                listOf(node.text, node.contentDescription, node.viewIdResourceName)\n            )\n        ) return true\n\n        return deviceAdminClickSearchTerms.any { term ->\n            val nodes = runCatching {\n                node.findAccessibilityNodeInfosByText(term)\n            }.getOrDefault(emptyList())\n            try {\n                nodes.any { candidate ->\n                    ManagedSelfProtectionPolicy.textTargetsDeviceAdmin(\n                        listOf(\n                            candidate.text,\n                            candidate.contentDescription,\n                            candidate.viewIdResourceName\n                        )\n                    )\n                }\n            } finally {\n                nodes.forEach(::recycleSafely)\n            }\n        }\n    }\n\n    private fun eventTextValues(\n'''
)

replace_once(
    service,
    '''    private fun executeProtectionAction(\n        eventTimeUptimeMillis: Long,\n        eventDeliveredAtUptimeMillis: Long,\n        eventDetectedAtNanos: Long,\n        holdUntilSafeSurface: Boolean = false\n    ): Long {\n''',
    '''    private fun executeProtectionAction(\n        eventTimeUptimeMillis: Long,\n        eventDeliveredAtUptimeMillis: Long,\n        eventDetectedAtNanos: Long,\n        holdUntilSafeSurface: Boolean = false,\n        forceLauncherFallback: Boolean = false\n    ): Long {\n'''
)

replace_once(
    service,
    '''        if (shouldEvictForProtectionAttempt(alreadyAwaitingSafeSurface)) {\n            evictBlockedAppFromForeground()\n        }\n''',
    '''        if (shouldEvictForProtectionAttempt(alreadyAwaitingSafeSurface)) {\n            evictBlockedAppFromForeground(forceLauncherFallback = forceLauncherFallback)\n        }\n'''
)

replace_once(
    master,
    '''    private fun notifyCredentialReady(curtainGeneration: Long) {\n        if (curtainGeneration <= 0L) return\n        CurtainDestinationReadyCoordinator.notifyReady(curtainGeneration)\n    }\n\n    private fun cancelRemovalAttempt() {\n        runCatching { startActivity(createHomeIntent()) }\n        finish()\n    }\n''',
    '''    private fun notifyCredentialReady(curtainGeneration: Long) {\n        if (curtainGeneration <= 0L) return\n        // The internal Settings reset is finished as soon as this safe credential\n        // surface is really on screen. Keeping the reset exemption alive for its\n        // old 3-second timeout let a second real Settings click pass through.\n        ProtectedSettingsResetWindow.close(curtainGeneration)\n        CurtainDestinationReadyCoordinator.notifyReady(curtainGeneration)\n    }\n\n    private fun cancelRemovalAttempt() {\n        // If cancellation races the first drawn credential frame, revoke the reset\n        // exemption before HOME so no user click can inherit it.\n        ProtectedSettingsResetWindow.close(pendingCurtainGeneration)\n        runCatching { startActivity(createHomeIntent()) }\n        finish()\n    }\n'''
)

replace_once(
    managed,
    '''    internal val deviceAdminSearchTerms = listOf(\n        "Apps do administrador do aparelho",\n        "Apps administradores do sistema",\n''',
    '''    internal val deviceAdminSearchTerms = listOf(\n        // One UI pt-BR: keep the observed gateway first so the rare broad root\n        // fallback short-circuits on its first query.\n        "Apps administradores do sistema",\n        "Apps do administrador do aparelho",\n'''
)

replace_once(
    managed,
    '''    internal val deviceAdminNodeSearchTerms = listOf(\n        "Apps do administr",\n        "Apps administradores",\n        "Device admin"\n    )\n''',
    '''    internal val deviceAdminNodeSearchTerms = listOf(\n        // Current One UI pt-BR wording first; these are locators only and the\n        // returned node still passes textTargetsDeviceAdmin before any block.\n        "Apps administradores",\n        "Apps do administr",\n        "Device admin"\n    )\n'''
)

replace_once(
    reset_test,
    '''    @Test\n    fun `only the live matching curtain generation suppresses internal reset events`() {\n''',
    '''    @Test\n    fun `close revokes reset exemption immediately`() {\n        ProtectedSettingsResetWindow.open(91L)\n        assertThat(ProtectedSettingsResetWindow.isActive(91L)).isTrue()\n\n        ProtectedSettingsResetWindow.close(91L)\n\n        assertThat(ProtectedSettingsResetWindow.isActive(91L)).isFalse()\n    }\n\n    @Test\n    fun `only the live matching curtain generation suppresses internal reset events`() {\n'''
)

replace_once(
    immediate_test,
    '''        assertThat(admin.decision).isEqualTo(DirectDecision.PROTECT)\n        assertThat(admin.surface).isEqualTo(SettingsSurface.DEVICE_ADMIN)\n''',
    '''        assertThat(admin.decision).isEqualTo(DirectDecision.PROTECT)\n        assertThat(admin.surface).isEqualTo(SettingsSurface.DEVICE_ADMIN)\n        val samsungAdmin = ImmediateInterceptionPolicy.classifySettingsClick(\n            "com.android.settings",\n            "android.widget.TextView",\n            listOf("Apps administradores do sistema")\n        )\n        assertThat(samsungAdmin.decision).isEqualTo(DirectDecision.PROTECT)\n        assertThat(samsungAdmin.surface).isEqualTo(SettingsSurface.DEVICE_ADMIN)\n'''
)

print("Device Admin fast-path patch applied")
