from pathlib import Path

PATH = Path("app/src/main/java/com/focusguard/service/BlockingAccessibilityService.kt")
text = PATH.read_text(encoding="utf-8")


def replace_once(old: str, new: str, label: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    text = text.replace(old, new, 1)


def replace_between(start: str, end: str, new: str, label: str) -> None:
    global text
    start_i = text.find(start)
    if start_i < 0:
        raise SystemExit(f"{label}: start marker not found")
    end_i = text.find(end, start_i)
    if end_i < 0:
        raise SystemExit(f"{label}: end marker not found")
    text = text[:start_i] + new + text[end_i:]


replace_once(
    "import com.focusguard.security.AccessibilitySettingsPolicy\n",
    "import com.focusguard.security.AccessibilityHotPathTelemetry\n"
    "import com.focusguard.security.AccessibilitySettingsPolicy\n",
    "telemetry import",
)
replace_once(
    "import com.focusguard.security.DeviceAdminActivationWindow\n",
    "import com.focusguard.security.DeviceAdminActivationWindow\n"
    "import com.focusguard.security.DeviceOwnerMaintenanceGate\n"
    "import com.focusguard.security.EventTextNormalizer\n",
    "admin imports",
)
replace_once(
    "import com.focusguard.security.ProtectedSettingsResetWindow\n",
    "import com.focusguard.security.ProtectedSettingsResetWindow\n"
    "import com.focusguard.security.ProtectionFastSnapshot\n",
    "snapshot import",
)
replace_once(
    "import com.focusguard.security.SettingsInterceptionPolicy\n",
    "import com.focusguard.security.SettingsFastPathResolver\n"
    "import com.focusguard.security.SettingsInterceptionPolicy\n"
    "import com.focusguard.security.SettingsLocalFastPathPolicy\n",
    "fast path imports",
)

replace_once(
    "    @Volatile private var deviceOwnerActiveCached = false\n",
    "    @Volatile private var deviceOwnerActiveCached = false\n"
    "    @Volatile private var deviceAdminActiveCached = false\n"
    "    @Volatile private var protectionFastSnapshot = ProtectionFastSnapshot()\n"
    "    private val protectionFastGenerationCounter = AtomicLong(0L)\n"
    "    private val telemetryDrainScheduled = AtomicBoolean(false)\n"
    "    private var focusGuardDisplayLabel = \"HardBlock\"\n",
    "fast fields",
)

replace_once(
    '''    private val directClickContextSufficientTerms =
        (listOf("FocusGuard", "Focus Guard", "com.focusguard") +
            AccessibilitySettingsPolicy.accessibilityDisclosureNodeSearchTerms +
            AccessibilitySettingsPolicy.installedAccessibilityAppsNodeSearchTerms).distinct()
    private val clickInterceptionSearchTerms =
        (directClickContextSufficientTerms +
            listOf("admin", "Informações do app", "Informações do aplicativo", "App info"))
            .distinct()
''',
    '''    private val directClickContextSufficientTerms =
        (listOf("HardBlock", "Hard Block", "FocusGuard", "Focus Guard", "com.focusguard") +
            AccessibilitySettingsPolicy.accessibilityDisclosureNodeSearchTerms +
            AccessibilitySettingsPolicy.installedAccessibilityAppsNodeSearchTerms).distinct()
    private val clickInterceptionSearchTerms =
        (directClickContextSufficientTerms +
            listOf("admin", "Informações do app", "Informações do aplicativo", "App info"))
            .distinct()
''',
    "locator terms",
)

replace_once(
    '''        deviceOwnerManager = DeviceOwnerManager.getInstance(this)
        deviceOwnerActiveCached = deviceOwnerManager.isDeviceOwnerActive()
        refreshSynchronousProtectionState()
        usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
''',
    '''        deviceOwnerManager = DeviceOwnerManager.getInstance(this)
        deviceOwnerActiveCached = deviceOwnerManager.isDeviceOwnerActive()
        deviceAdminActiveCached = deviceOwnerManager.isDeviceAdminActive()
        DeviceOwnerMaintenanceGate.preload(this)
        DeviceAdminActivationWindow.preload(this)
        refreshSynchronousProtectionState()
        usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
''',
    "onCreate administrative preload",
)
replace_once(
    '''        protectedPowerMenuController = ProtectedPowerMenuController(this)
        prepareInstantBlockCurtain()
        AuthenticatedRemovalWindow.preload(this)
        DeviceAdminActivationWindow.preload(this)
''',
    '''        protectedPowerMenuController = ProtectedPowerMenuController(this)
        prepareInstantBlockCurtain()
        focusGuardDisplayLabel = runCatching {
            applicationInfo.loadLabel(packageManager).toString()
        }.getOrDefault("HardBlock").ifBlank { "HardBlock" }
        AuthenticatedRemovalWindow.preload(this)
''',
    "onCreate label preload",
)

replace_once(
    '''        deviceOwnerActiveCached = deviceOwnerManager.isDeviceOwnerActive()
        refreshSynchronousProtectionState()
        prepareInstantBlockCurtain()
''',
    '''        deviceOwnerActiveCached = deviceOwnerManager.isDeviceOwnerActive()
        deviceAdminActiveCached = deviceOwnerManager.isDeviceAdminActive()
        DeviceOwnerMaintenanceGate.refreshCache(this)
        DeviceAdminActivationWindow.preload(this)
        refreshSynchronousProtectionState()
        prepareInstantBlockCurtain()
''',
    "onServiceConnected cache refresh",
)

replace_once(
    '''        isBlockingSessionActive = intent.getBooleanExtra(
            EXTRA_BLOCKING_ACTIVE_SNAPSHOT,
            apps.isNotEmpty() || sites.isNotEmpty()
        )
        syncWarmOverlays()
''',
    '''        isBlockingSessionActive = intent.getBooleanExtra(
            EXTRA_BLOCKING_ACTIVE_SNAPSHOT,
            apps.isNotEmpty() || sites.isNotEmpty()
        )
        publishProtectionFastSnapshot()
        syncWarmOverlays()
''',
    "immediate snapshot publish",
)

replace_once(
    '''                        val deviceOwnerActiveNow = deviceOwnerManager.isDeviceOwnerActive()
                        deviceOwnerActiveCached = deviceOwnerActiveNow
                        val adultFilterEnabled = authManager.isAdultFilterEnabled()
''',
    '''                        val deviceOwnerActiveNow = deviceOwnerManager.isDeviceOwnerActive()
                        val deviceAdminActiveNow = deviceOwnerManager.isDeviceAdminActive()
                        deviceOwnerActiveCached = deviceOwnerActiveNow
                        deviceAdminActiveCached = deviceAdminActiveNow
                        DeviceOwnerMaintenanceGate.refreshCache(this@BlockingAccessibilityService)
                        DeviceAdminActivationWindow.preload(this@BlockingAccessibilityService)
                        val adultFilterEnabled = authManager.isAdultFilterEnabled()
''',
    "async administrative refresh",
)
replace_once(
    '''                            lastEnforcementFingerprint = enforcementFingerprint
                            lastLoadTime = System.currentTimeMillis()
                            syncWarmOverlays()
''',
    '''                            lastEnforcementFingerprint = enforcementFingerprint
                            lastLoadTime = System.currentTimeMillis()
                            publishProtectionFastSnapshot()
                            syncWarmOverlays()
''',
    "async snapshot publish",
)

replace_once(
    '''    private fun isSelfProtectionEngagedNow(): Boolean =
        isBlockingSessionActive ||
            focusModeSessionActive ||
            SelfProtectionStateStore.isArmed(applicationContext) ||
            (deviceOwnerActiveCached && deviceOwnerManager.isArmoredProtectionArmed())
''',
    '''    private fun isSelfProtectionEngagedNow(): Boolean = protectionFastSnapshot.engaged
''',
    "cache self protection read",
)
replace_once(
    '''        if (AuthenticatedRemovalWindow.isActive(this) || !isSelfProtectionEngagedNow()) {
            return false
        }
        if (deviceOwnerActiveCached && deviceOwnerManager.isMaintenanceActive()) return false
''',
    '''        val nowElapsed = SystemClock.elapsedRealtime()
        val snapshot = protectionFastSnapshot
        if (AuthenticatedRemovalWindow.isActive(this) || !snapshot.engaged) return false
        if (snapshot.deviceOwnerActive &&
            DeviceOwnerMaintenanceGate.isTemporarilyUnlockedCached(nowElapsed)
        ) return false
''',
    "launcher App Info cache path",
)

NEW_HANDLE = r'''    private fun handleSettingsInterception(
        event: AccessibilityEvent,
        packageName: String,
        eventDetectedAtNanos: Long,
        eventDeliveredAtUptimeMillis: Long
    ): Boolean {
        if (packageName !in interceptionPackages) return false
        if (packageName in SettingsInterceptionPolicy.systemUiPackages &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED
        ) return false
        if (AuthenticatedRemovalWindow.isActive(this)) return false

        val nowElapsed = SystemClock.elapsedRealtime()
        val baseSnapshot = protectionFastSnapshot
        if (!baseSnapshot.engaged) return false
        val effectiveSnapshot = baseSnapshot.copy(
            maintenanceWindowActive = baseSnapshot.deviceOwnerActive &&
                DeviceOwnerMaintenanceGate.isTemporarilyUnlockedCached(nowElapsed),
            adminEnrollmentAuthorized = DeviceAdminActivationWindow.isAuthorizedCached(
                deviceAdminActive = baseSnapshot.deviceAdminActive,
                nowElapsedMillis = nowElapsed
            )
        )
        if (effectiveSnapshot.maintenanceWindowActive) return false

        val isSystemUi = packageName in SettingsInterceptionPolicy.systemUiPackages
        if (!isSystemUi &&
            ProtectedSettingsResetWindow.isActive(
                curtainGeneration = awaitingSafeSurfaceGeneration,
                nowElapsed = nowElapsed
            )
        ) return true

        if (!isSystemUi &&
            effectiveSnapshot.strictPomodoro &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED
        ) {
            performGlobalAction(GLOBAL_ACTION_BACK)
            launchPomodoroLockScreen()
            return true
        }

        if (!isSystemUi &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED &&
            nowElapsed <= pendingSettingsProtectionUntilElapsed
        ) {
            val attempt = AccessibilityHotPathTelemetry.Attempt(
                generation = effectiveSnapshot.generation,
                eventReceivedNanos = eventDetectedAtNanos
            )
            attempt.decisionCompleteNanos = SystemClock.elapsedRealtimeNanos()
            executeProtectionAction(
                eventTimeUptimeMillis = event.eventTime,
                eventDeliveredAtUptimeMillis = eventDeliveredAtUptimeMillis,
                eventDetectedAtNanos = eventDetectedAtNanos,
                telemetryAttempt = attempt
            )
            return true
        }

        val className = event.className?.toString().orEmpty()
        val directValues = directEventTextValues(event)
        val attempt = AccessibilityHotPathTelemetry.Attempt(
            generation = effectiveSnapshot.generation,
            eventReceivedNanos = eventDetectedAtNanos
        )
        attempt.decisionStartNanos = SystemClock.elapsedRealtimeNanos()
        val localInput = SettingsLocalFastPathPolicy.Input(
            packageName = packageName,
            eventType = event.eventType,
            className = className,
            directText = EventTextNormalizer.prepare(directValues),
            snapshot = effectiveSnapshot,
            transitionGuardActive = nowElapsed <= pendingSettingsProtectionUntilElapsed
        )

        var remoteValues: List<CharSequence?>? = null
        val fastDecision = AccessibilityHotPathTelemetry.trace("HB.event_to_decision") {
            SettingsFastPathResolver.resolve(localInput) {
                val values = eventTextValues(
                    event = event,
                    forceExpandClickContext = isSystemUi,
                    telemetryAttempt = attempt
                )
                remoteValues = values
                SettingsLocalFastPathPolicy.decide(
                    localInput.copy(directText = EventTextNormalizer.prepare(values))
                )
            }
        }

        fun protect(surface: SettingsSurface?, armGuard: Boolean): Boolean {
            if (armGuard) {
                pendingSettingsProtectionUntilElapsed =
                    nowElapsed + SETTINGS_TRANSITION_GUARD_MILLIS
            }
            attempt.decisionCompleteNanos = SystemClock.elapsedRealtimeNanos()
            val target = surface?.toMasterRemovalTarget()
            val generation = executeProtectionAction(
                eventTimeUptimeMillis = event.eventTime,
                eventDeliveredAtUptimeMillis = eventDeliveredAtUptimeMillis,
                eventDetectedAtNanos = eventDetectedAtNanos,
                holdUntilSafeSurface = target != null,
                telemetryAttempt = attempt
            )
            target?.let { launchMasterRemovalGate(it, generation, attempt) }
            return true
        }

        when (fastDecision.action) {
            SettingsLocalFastPathPolicy.Action.PROTECT ->
                return protect(fastDecision.surface, fastDecision.armTransitionGuard)
            SettingsLocalFastPathPolicy.Action.IGNORE -> {
                if (!effectiveSnapshot.strictPomodoro) return false
            }
            SettingsLocalFastPathPolicy.Action.NEED_REMOTE -> Unit
        }

        // System UI is intentionally restricted to exact disclosure/admin clicks.
        // L0 already handled conclusive events; this is its one bounded remote pass.
        if (isSystemUi && event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            val contextual = remoteValues ?: eventTextValues(
                event = event,
                forceExpandClickContext = true,
                telemetryAttempt = attempt
            ).also { remoteValues = it }
            val systemUiDecision = ImmediateInterceptionPolicy.classifySystemUiClickWithContext(
                className = className,
                directValues = directValues,
                contextualValues = { contextual }
            )
            if (systemUiDecision.decision == DirectDecision.PROTECT) {
                return protect(systemUiDecision.surface, armGuard = true)
            }
            return false
        }

        val eventValues = remoteValues ?: eventTextValues(
            event = event,
            telemetryAttempt = attempt
        ).also { remoteValues = it }
        val classTargetsAccessibilityServiceToggle =
            AccessibilitySettingsPolicy.classTargetsAccessibilityServiceToggle(className)
        val classTargetsAccessibilityList =
            AccessibilitySettingsPolicy.classTargetsAccessibilityList(className)
        val classTargetsDeviceAdmin =
            ManagedSelfProtectionPolicy.classTargetsDeviceAdmin(className)
        val classTargetsAppDetails =
            ManagedSelfProtectionPolicy.classTargetsAppDetails(className)
        val classTargetsUninstall =
            ManagedSelfProtectionPolicy.classTargetsUninstall(className)
        val classTargetsEssentialSpecialAccess =
            ManagedSelfProtectionPolicy.classTargetsEssentialSpecialAccess(className)
        val isGenericSubSettings = className.contains("SubSettings", ignoreCase = true)
        val accessibilityTextSignals = AccessibilitySettingsPolicy.classifyText(eventValues)
        val accessibilityContextConfirmed = confirmAccessibilityContextForInstalledEntry(
            directAccessibility = accessibilityTextSignals.accessibility,
            installedAccessibilityApps = accessibilityTextSignals.installedAccessibilityApps,
            rootMentionsAccessibility = { rootMentionsAccessibilityBudgeted(attempt) }
        )
        val managedTextSignals = ManagedSelfProtectionPolicy.classifyText(eventValues)

        val signals = SettingsInterceptionPolicy.EventSignals(
            packageName = packageName,
            isViewClickedEvent = event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED,
            isWindowTransitionEvent =
                event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                    event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            guardArmed = nowElapsed <= pendingSettingsProtectionUntilElapsed,
            classTargetsAccessibilityServiceToggle = classTargetsAccessibilityServiceToggle,
            classTargetsAccessibilityList = classTargetsAccessibilityList,
            classTargetsDeviceAdmin = classTargetsDeviceAdmin,
            classTargetsAppDetails = classTargetsAppDetails,
            classTargetsUninstall = classTargetsUninstall,
            classTargetsEssentialSpecialAccess = classTargetsEssentialSpecialAccess,
            isGenericSubSettings = isGenericSubSettings,
            textMentionsAccessibility = accessibilityContextConfirmed,
            textMentionsInstalledAccessibilityApps =
                accessibilityTextSignals.installedAccessibilityApps,
            textMentionsAccessibilityDisclosure =
                accessibilityTextSignals.accessibilityDisclosure,
            textMentionsDeviceAdmin = managedTextSignals.deviceAdmin,
            textMentionsFocusGuard = managedTextSignals.focusGuard,
            textMentionsDestructiveControl = managedTextSignals.destructiveControl,
            textMentionsEssentialSpecialAccess = managedTextSignals.essentialSpecialAccess,
            textMentionsAppInfoGateway = managedTextSignals.appInfoGateway
        )

        val masterRemovalTarget = if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            when {
                signals.textMentionsDeviceAdmin || classTargetsDeviceAdmin ->
                    MasterRemovalActivity.Target.DEVICE_ADMIN
                (signals.textMentionsInstalledAccessibilityApps && signals.textMentionsAccessibility) ||
                    classTargetsAccessibilityServiceToggle ||
                    (signals.textMentionsAccessibilityDisclosure && signals.textMentionsFocusGuard) ->
                    MasterRemovalActivity.Target.ACCESSIBILITY
                signals.textMentionsAppInfoGateway || classTargetsAppDetails ->
                    MasterRemovalActivity.Target.APP_INFO
                classTargetsUninstall ||
                    packageName in SettingsInterceptionPolicy.packageInstallerPackages ||
                    (signals.textMentionsDestructiveControl && signals.textMentionsFocusGuard) ->
                    MasterRemovalActivity.Target.UNINSTALL
                signals.textMentionsFocusGuard -> MasterRemovalActivity.Target.APP_INFO
                else -> null
            }
        } else null

        // The only root query left in the hot path is the explicit Accessibility
        // context confirmation above. Reuse already classified values here instead
        // of issuing five independent root/findByText scans.
        val decision = SettingsInterceptionPolicy.decide(
            signals = signals,
            selfProtectionEngaged = true,
            strictPomodoroActive = effectiveSnapshot.strictPomodoro,
            deviceAdminActivationAuthorized = effectiveSnapshot.adminEnrollmentAuthorized,
            rootSignals = SettingsInterceptionPolicy.RootSignals(
                mentionsAccessibility = { accessibilityContextConfirmed },
                mentionsDeviceAdmin = { managedTextSignals.deviceAdmin },
                mentionsFocusGuard = { managedTextSignals.focusGuard },
                mentionsDestructiveControl = { managedTextSignals.destructiveControl },
                mentionsEssentialSpecialAccess = { managedTextSignals.essentialSpecialAccess }
            )
        )

        return when (decision) {
            SettingsInterceptionPolicy.Decision.IGNORE -> false
            SettingsInterceptionPolicy.Decision.POMODORO_LOCK -> {
                performGlobalAction(GLOBAL_ACTION_BACK)
                launchPomodoroLockScreen()
                true
            }
            SettingsInterceptionPolicy.Decision.PROTECT -> {
                if (masterRemovalTarget != null) {
                    protect(masterRemovalTarget.toSettingsSurface(), armGuard = false)
                } else {
                    protect(null, armGuard = false)
                }
            }
            SettingsInterceptionPolicy.Decision.PROTECT_AND_ARM_GUARD -> {
                if (masterRemovalTarget != null) {
                    protect(masterRemovalTarget.toSettingsSurface(), armGuard = true)
                } else {
                    protect(null, armGuard = true)
                }
            }
        }
    }

'''
replace_between(
    "    private fun handleSettingsInterception(\n",
    "    private fun SettingsSurface.toMasterRemovalTarget()",
    NEW_HANDLE,
    "settings handler",
)

replace_once(
    '''    private fun SettingsSurface.toMasterRemovalTarget(): MasterRemovalActivity.Target = when (this) {
        SettingsSurface.APP_INFO -> MasterRemovalActivity.Target.APP_INFO
        SettingsSurface.DEVICE_ADMIN -> MasterRemovalActivity.Target.DEVICE_ADMIN
        SettingsSurface.ACCESSIBILITY -> MasterRemovalActivity.Target.ACCESSIBILITY
        SettingsSurface.UNINSTALL -> MasterRemovalActivity.Target.UNINSTALL
    }
''',
    '''    private fun SettingsSurface.toMasterRemovalTarget(): MasterRemovalActivity.Target = when (this) {
        SettingsSurface.APP_INFO -> MasterRemovalActivity.Target.APP_INFO
        SettingsSurface.DEVICE_ADMIN -> MasterRemovalActivity.Target.DEVICE_ADMIN
        SettingsSurface.ACCESSIBILITY -> MasterRemovalActivity.Target.ACCESSIBILITY
        SettingsSurface.UNINSTALL -> MasterRemovalActivity.Target.UNINSTALL
    }

    private fun MasterRemovalActivity.Target.toSettingsSurface(): SettingsSurface = when (this) {
        MasterRemovalActivity.Target.APP_INFO -> SettingsSurface.APP_INFO
        MasterRemovalActivity.Target.DEVICE_ADMIN -> SettingsSurface.DEVICE_ADMIN
        MasterRemovalActivity.Target.ACCESSIBILITY -> SettingsSurface.ACCESSIBILITY
        MasterRemovalActivity.Target.UNINSTALL -> SettingsSurface.UNINSTALL
    }
''',
    "target conversion",
)

replace_between(
    "    private fun launchMasterRemovalGate(\n",
    "    private fun eventTextValues(\n",
    r'''    private fun launchMasterRemovalGate(
        target: MasterRemovalActivity.Target,
        curtainGeneration: Long,
        telemetryAttempt: AccessibilityHotPathTelemetry.Attempt? = null
    ) {
        telemetryAttempt?.gateRequestedNanos = SystemClock.elapsedRealtimeNanos()
        val intent = MasterRemovalActivity.createIntent(
            context = this,
            target = target,
            curtainGeneration = curtainGeneration
        ).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION
            )
        }
        runCatching {
            startActivity(intent)
        }.onFailure { error ->
            FocusGuardLogger.logError("MasterRemoval", "Falha ao abrir senha mestre", error)
        }
    }

''',
    "master gate telemetry",
)

replace_between(
    "    private fun eventTextValues(\n",
    "    private fun refreshSynchronousProtectionState()",
    r'''    private fun eventTextValues(
        event: AccessibilityEvent,
        forceExpandClickContext: Boolean = false,
        telemetryAttempt: AccessibilityHotPathTelemetry.Attempt? = null
    ): List<CharSequence?> {
        return buildList {
            addAll(event.text.orEmpty())
            add(event.contentDescription)
            telemetryAttempt?.sourceRequestedNanos = SystemClock.elapsedRealtimeNanos()
            val source = AccessibilityHotPathTelemetry.trace("HB.source_query") { event.source }
            telemetryAttempt?.sourceReturnedNanos = SystemClock.elapsedRealtimeNanos()
            source?.let { node ->
                add(node.text)
                add(node.contentDescription)
                add(node.viewIdResourceName)
                if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED &&
                    (forceExpandClickContext || shouldExpandClickContext(this))
                ) {
                    telemetryAttempt?.treeFallbackStartNanos = SystemClock.elapsedRealtimeNanos()
                    AccessibilityHotPathTelemetry.trace("HB.tree_fallback") {
                        // One generic text query maximum. The actual installed label
                        // is loaded once at service startup, so this works for
                        // HardBlock/FocusGuard renames without a term-by-term loop.
                        val matches = runCatching {
                            node.findAccessibilityNodeInfosByText(focusGuardDisplayLabel)
                        }.getOrDefault(emptyList())
                        matches.forEach { match ->
                            add(match.text)
                            add(match.contentDescription)
                            add(match.viewIdResourceName)
                            recycleSafely(match)
                        }
                        addAll(boundedAncestorContextValues(node))
                    }
                    telemetryAttempt?.treeFallbackEndNanos = SystemClock.elapsedRealtimeNanos()
                }
                recycleSafely(node)
            }
        }
    }

    private fun boundedAncestorContextValues(source: AccessibilityNodeInfo): List<CharSequence?> {
        val values = ArrayList<CharSequence?>(24)
        var current = runCatching { source.parent }.getOrNull()
        var depth = 0
        while (current != null && depth < 2) {
            val node = current
            values += node.text
            values += node.contentDescription
            values += node.viewIdResourceName
            val childCount = minOf(node.childCount, 8)
            for (index in 0 until childCount) {
                val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
                values += child.text
                values += child.contentDescription
                values += child.viewIdResourceName
                recycleSafely(child)
            }
            current = runCatching { node.parent }.getOrNull()
            recycleSafely(node)
            depth++
        }
        return values
    }

    private fun publishProtectionFastSnapshot() {
        val nowElapsed = SystemClock.elapsedRealtime()
        protectionFastSnapshot = ProtectionFastSnapshot(
            generation = protectionFastGenerationCounter.incrementAndGet(),
            engaged = isBlockingSessionActive || focusModeSessionActive,
            strictPomodoro = isPomodoroStrictActive,
            focusModeActive = focusModeSessionActive,
            deviceOwnerActive = deviceOwnerActiveCached,
            maintenanceWindowActive = deviceOwnerActiveCached &&
                DeviceOwnerMaintenanceGate.isTemporarilyUnlockedCached(nowElapsed),
            deviceAdminActive = deviceAdminActiveCached,
            adminEnrollmentAuthorized = DeviceAdminActivationWindow.isAuthorizedCached(
                deviceAdminActive = deviceAdminActiveCached,
                nowElapsedMillis = nowElapsed
            )
        )
    }

    private fun refreshSynchronousProtectionState() {
        refreshFocusModeFallbackState()
        val snapshot = SelfProtectionStateStore.read(applicationContext)
        blockedAppsSet = snapshot.blockedApps
        blockedWebsitesDomainSet = WebsiteBlocker.normalizeRules(snapshot.blockedSites)
        blockedWebsiteAppDomains = WebsiteBlocker.appPackageDomainsFor(blockedWebsitesDomainSet)
        isPomodoroStrictActive = snapshot.strictPomodoro
        isBlockingSessionActive = isSelfProtectionEngaged(
            cachedActive = isBlockingSessionActive,
            persistedActive = snapshot.armed,
            focusModeActive = focusModeSessionActive,
            armoredDeviceOwnerActive = deviceOwnerActiveCached &&
                deviceOwnerManager.isArmoredProtectionArmed()
        )
        publishProtectionFastSnapshot()
    }

''',
    "bounded event values and snapshot publisher",
)

replace_between(
    "    private fun sameRowClickTextValues(source: AccessibilityNodeInfo): List<CharSequence?> {\n",
    "    private fun rootMentionsAccessibility(): Boolean {\n",
    r'''    private fun sameRowClickTextValues(source: AccessibilityNodeInfo): List<CharSequence?> {
        val sourceBounds = Rect().also(source::getBoundsInScreen)
        if (sourceBounds.isEmpty || source.isScrollable) return emptyList()
        val root = rootInActiveWindow ?: return emptyList()
        return try {
            val rootBounds = Rect().also(root::getBoundsInScreen)
            if (!shouldSearchSameRowMarkers(sourceBounds, rootBounds)) return emptyList()
            val nodes = runCatching {
                root.findAccessibilityNodeInfosByText(focusGuardDisplayLabel)
            }.getOrDefault(emptyList())
            buildList {
                nodes.forEach { node ->
                    val nodeBounds = Rect().also(node::getBoundsInScreen)
                    if (boundsShareHorizontalRow(sourceBounds, nodeBounds)) {
                        add(node.text)
                        add(node.contentDescription)
                        add(node.viewIdResourceName)
                    }
                    recycleSafely(node)
                }
            }
        } finally {
            recycleSafely(root)
        }
    }

    private fun rootMentionsAccessibilityBudgeted(
        telemetryAttempt: AccessibilityHotPathTelemetry.Attempt?
    ): Boolean {
        telemetryAttempt?.treeFallbackStartNanos =
            telemetryAttempt?.treeFallbackStartNanos?.takeIf { it > 0L }
                ?: SystemClock.elapsedRealtimeNanos()
        return AccessibilityHotPathTelemetry.trace("HB.tree_fallback") {
            val root = rootInActiveWindow ?: return@trace false
            try {
                val locale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    resources.configuration.locales[0]
                } else {
                    @Suppress("DEPRECATION")
                    resources.configuration.locale
                }
                val term = if (locale.language.equals("pt", ignoreCase = true)) {
                    "Acessibilidade"
                } else {
                    "Accessibility"
                }
                val nodes = runCatching {
                    root.findAccessibilityNodeInfosByText(term)
                }.getOrDefault(emptyList())
                val found = nodes.any { node ->
                    AccessibilitySettingsPolicy.textTargetsAccessibility(
                        listOf(node.text, node.contentDescription, node.viewIdResourceName)
                    )
                }
                nodes.forEach(::recycleSafely)
                found
            } finally {
                recycleSafely(root)
                telemetryAttempt?.treeFallbackEndNanos = SystemClock.elapsedRealtimeNanos()
            }
        }
    }

''',
    "bounded same-row and accessibility root",
)

replace_between(
    "    private fun rootContainsAny(\n",
    "    private fun executeProtectionAction(\n",
    r'''    private fun rootContainsAny(
        searchTerms: Iterable<String>,
        classifier: (Iterable<CharSequence?>) -> Boolean,
        screenLabel: String
    ): Boolean {
        val root = rootInActiveWindow ?: return false
        return try {
            // Legacy fallback is deliberately capped at one generic query. All
            // normal protected routes should have been handled by L0/source first.
            val term = searchTerms.firstOrNull() ?: return false
            val nodes = root.findAccessibilityNodeInfosByText(term)
            val found = nodes.any { node ->
                classifier(listOf(node.text, node.contentDescription, node.viewIdResourceName))
            }
            nodes.forEach(::recycleSafely)
            found
        } catch (error: RuntimeException) {
            FocusGuardLogger.logError(
                "A11y",
                "Falha ao identificar tela de $screenLabel",
                error
            )
            false
        } finally {
            recycleSafely(root)
        }
    }

''',
    "one-query root fallback",
)

replace_between(
    "    private fun executeProtectionAction(\n",
    "    private fun handleBrowserEvent(event: AccessibilityEvent) {\n",
    r'''    private fun executeProtectionAction(
        eventTimeUptimeMillis: Long,
        eventDeliveredAtUptimeMillis: Long,
        eventDetectedAtNanos: Long,
        holdUntilSafeSurface: Boolean = false,
        telemetryAttempt: AccessibilityHotPathTelemetry.Attempt? = null
    ): Long {
        val nowElapsed = SystemClock.elapsedRealtime()
        val shouldReport = shouldExecuteProtectionAction(
            protectionActionUntilElapsed,
            nowElapsed
        )
        if (shouldReport) {
            protectionActionUntilElapsed = nowElapsed + SELF_PROTECTION_ACTION_DEBOUNCE_MILLIS
        }
        val alreadyAwaitingSafeSurface = shouldReuseAwaitedCurtain(
            holdUntilSafeSurface = holdUntilSafeSurface,
            awaitingGeneration = awaitingSafeSurfaceGeneration,
            curtainVisible = instantBlockCurtainVisible
        )

        telemetryAttempt?.overlayRequestedNanos = SystemClock.elapsedRealtimeNanos()
        var frameCallbackRegistered = false
        if (telemetryAttempt != null &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            !alreadyAwaitingSafeSurface
        ) {
            val curtain = instantBlockCurtain
            val observer = curtain?.viewTreeObserver
            if (observer != null && observer.isAlive) {
                frameCallbackRegistered = true
                observer.registerFrameCommitCallback {
                    telemetryAttempt.overlayFrameCommittedNanos =
                        SystemClock.elapsedRealtimeNanos()
                    finishHotPathTelemetry(telemetryAttempt)
                }
            }
        }

        val generation = AccessibilityHotPathTelemetry.trace("HB.overlay_show") {
            if (!holdUntilSafeSurface && alreadyAwaitingSafeSurface) {
                renewInstantCurtainFailsafe()
                awaitingSafeSurfaceGeneration
            } else {
                showInstantBlockCurtain(
                    mode = CurtainMode.SELF_PROTECTION,
                    messageRes = R.string.accessibility_protection_blocked_notice
                )
            }
        }
        telemetryAttempt?.overlayUpdatedNanos = SystemClock.elapsedRealtimeNanos()
        if (holdUntilSafeSurface) awaitingSafeSurfaceGeneration = generation
        val curtainReadyAtNanos = SystemClock.elapsedRealtimeNanos()
        mainHandler.removeCallbacks(protectionCurtainDismiss)
        if (!holdUntilSafeSurface && !alreadyAwaitingSafeSurface) {
            mainHandler.postDelayed(
                protectionCurtainDismiss,
                SELF_PROTECTION_NOTICE_DURATION_MILLIS
            )
        }

        telemetryAttempt?.homeCallStartNanos = SystemClock.elapsedRealtimeNanos()
        if (shouldEvictForProtectionAttempt(alreadyAwaitingSafeSurface)) {
            AccessibilityHotPathTelemetry.trace("HB.home_action") {
                evictBlockedAppFromForeground()
            }
        }
        telemetryAttempt?.homeCallReturnedNanos = SystemClock.elapsedRealtimeNanos()
        val homeRequestedAtNanos = SystemClock.elapsedRealtimeNanos()

        if (telemetryAttempt != null && !frameCallbackRegistered) {
            telemetryAttempt.overlayFrameCommittedNanos =
                telemetryAttempt.overlayUpdatedNanos
            finishHotPathTelemetry(telemetryAttempt)
        }

        if (shouldReport) {
            showToastThrottled(getString(R.string.accessibility_protection_blocked_toast))
            recordSelfProtectionLatency(
                eventTimeUptimeMillis = eventTimeUptimeMillis,
                eventDeliveredAtUptimeMillis = eventDeliveredAtUptimeMillis,
                eventDetectedAtNanos = eventDetectedAtNanos,
                curtainReadyAtNanos = curtainReadyAtNanos,
                homeRequestedAtNanos = homeRequestedAtNanos
            )
        }
        return generation
    }

    private fun finishHotPathTelemetry(attempt: AccessibilityHotPathTelemetry.Attempt) {
        if (attempt.decisionCompleteNanos <= 0L) {
            attempt.decisionCompleteNanos = SystemClock.elapsedRealtimeNanos()
        }
        AccessibilityHotPathTelemetry.record(attempt.snapshot())
        scheduleHotPathTelemetryDrain()
    }

    private fun scheduleHotPathTelemetryDrain() {
        if (!telemetryDrainScheduled.compareAndSet(false, true)) return
        scope.launch {
            delay(200L)
            try {
                AccessibilityHotPathTelemetry.drain().forEach { sample ->
                    fun micros(start: Long, end: Long): Long =
                        if (start > 0L && end >= start) (end - start) / 1_000L else 0L
                    FocusGuardLogger.log(
                        "A11yPerf",
                        "g=${sample.generation} " +
                            "decision=${micros(sample.decisionStartNanos, sample.decisionCompleteNanos)}us " +
                            "source=${micros(sample.sourceRequestedNanos, sample.sourceReturnedNanos)}us " +
                            "tree=${micros(sample.treeFallbackStartNanos, sample.treeFallbackEndNanos)}us " +
                            "overlay=${micros(sample.overlayRequestedNanos, sample.overlayUpdatedNanos)}us " +
                            "frame=${micros(sample.eventReceivedNanos, sample.overlayFrameCommittedNanos)}us " +
                            "home=${micros(sample.homeCallStartNanos, sample.homeCallReturnedNanos)}us " +
                            "gate=${micros(sample.eventReceivedNanos, sample.gateRequestedNanos)}us"
                    )
                }
            } finally {
                telemetryDrainScheduled.set(false)
                if (AccessibilityHotPathTelemetry.pendingCountForTest() > 0) {
                    scheduleHotPathTelemetryDrain()
                }
            }
        }
    }

    private fun recordSelfProtectionLatency(
        eventTimeUptimeMillis: Long,
        eventDeliveredAtUptimeMillis: Long,
        eventDetectedAtNanos: Long,
        curtainReadyAtNanos: Long,
        homeRequestedAtNanos: Long
    ) {
        val eventDeliveryMicros = if (eventTimeUptimeMillis > 0L) {
            (eventDeliveredAtUptimeMillis - eventTimeUptimeMillis)
                .coerceAtLeast(0L) * 1_000L
        } else 0L
        val eventToCurtainMicros =
            (curtainReadyAtNanos - eventDetectedAtNanos).coerceAtLeast(0L) / 1_000L
        val curtainToHomeMicros =
            (homeRequestedAtNanos - curtainReadyAtNanos).coerceAtLeast(0L) / 1_000L
        val totalMicros =
            (homeRequestedAtNanos - eventDetectedAtNanos).coerceAtLeast(0L) / 1_000L
        scope.launch {
            FocusGuardLogger.log(
                "A11yLatency",
                "Autoproteção: entrega=${eventDeliveryMicros}µs, " +
                    "callback→cortina=${eventToCurtainMicros}µs, " +
                    "cortina→HOME=${curtainToHomeMicros}µs, total=${totalMicros}µs"
            )
        }
    }

''',
    "protection telemetry",
)

replace_between(
    "    private fun syncWarmOverlays() {\n",
    "    private fun launchPomodoroLockScreen() {\n",
    r'''    private fun syncWarmOverlays() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(::syncWarmOverlays)
            return
        }
        if (!accessibilityServiceConnected) return
        // Keep the trusted accessibility overlay attached for the lifetime of the
        // bound service. Idle state is alpha=0 + NOT_TOUCHABLE, so steady-state
        // blocking pays updateViewLayout only and never addToDisplay/addView.
        armInstantBlockCurtain()
        val active = isBlockingSessionActive || focusModeSessionActive
        protectedPowerMenuController?.onProtectionStateChanged(active)
    }

''',
    "persistent warm overlay",
)

# Keep the fast snapshot coherent when Focus Mode fallback state changes outside
# the main refresh loop.
replace_once(
    '''        focusModeBlockedAppsSet = if (focusModeFallbackActive) {
            session?.blockedPackages.orEmpty()
        } else {
            emptySet()
        }
    }
''',
    '''        focusModeBlockedAppsSet = if (focusModeFallbackActive) {
            session?.blockedPackages.orEmpty()
        } else {
            emptySet()
        }
        publishProtectionFastSnapshot()
    }
''',
    "focus snapshot publish",
)

# Development relinquish must publish the all-off generation before disabling.
replace_once(
    '''            protectedPowerMenuController?.onProtectionStateChanged(false)
            releaseInstantBlockCurtain()
''',
    '''            protectedPowerMenuController?.onProtectionStateChanged(false)
            publishProtectionFastSnapshot()
            releaseInstantBlockCurtain()
''',
    "development snapshot publish",
)

# Sanity invariants: hot-path forbidden calls must no longer exist inside the
# rewritten Settings handler.
handler_start = text.index("    private fun handleSettingsInterception(\n")
handler_end = text.index("    private fun SettingsSurface.toMasterRemovalTarget()", handler_start)
handler = text[handler_start:handler_end]
for forbidden in (
    "deviceOwnerManager.isMaintenanceActive()",
    "deviceOwnerManager.isDeviceAdminActive()",
    "SelfProtectionStateStore.isArmed",
):
    if forbidden in handler:
        raise SystemExit(f"forbidden hot-path call remained: {forbidden}")

PATH.write_text(text, encoding="utf-8")
print("Deep-research service patch applied successfully")
