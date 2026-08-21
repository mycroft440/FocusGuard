package com.focusguard.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.focusguard.admin.DeviceOwnerManager
import com.focusguard.state.FocusModeStore
import com.focusguard.security.AccessibilitySettingsPolicy
import com.focusguard.security.AuthenticatedRemovalWindow
import com.focusguard.security.DeviceAdminActivationWindow
import com.focusguard.security.ManagedSelfProtectionPolicy
import com.focusguard.security.SelfProtectionStateStore
import com.focusguard.security.SettingsInterceptionPolicy
import com.focusguard.utils.FocusGuardLogger

/** Classifies and blocks attempts to disable FocusGuard in Android Settings. */
class AccessibilitySettingsInterceptor(
    private val service: BlockingAccessibilityService,
    private val deviceOwnerManager: DeviceOwnerManager,
    private val presenter: AccessibilityBlockPresenter
) {
    private val interceptionPackages = SettingsInterceptionPolicy.interceptionPackages
    private val clickSearchTerms = (
        ManagedSelfProtectionPolicy.focusGuardSearchTerms +
            AccessibilitySettingsPolicy.accessibilityDisclosureNodeSearchTerms
        ).distinct()
    private var pendingProtectionUntilElapsed = 0L

    fun handle(
        event: AccessibilityEvent,
        packageName: String,
        cachedProtectionActive: Boolean,
        strictPomodoroActive: Boolean
    ): Boolean {
        if (packageName !in interceptionPackages) return false
        if (packageName in SettingsInterceptionPolicy.systemUiPackages &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED
        ) {
            return false
        }
        if (AuthenticatedRemovalWindow.isActive(service)) return false

        val armorPersists = deviceOwnerManager.isDeviceOwnerActive() &&
            deviceOwnerManager.isArmoredProtectionArmed()
        val protectionEngaged = BlockingAccessibilityService.isSelfProtectionEngaged(
            cachedActive = cachedProtectionActive,
            persistedActive = SelfProtectionStateStore.isArmed(service.applicationContext),
            focusModeActive = FocusModeStore.isActive(service.applicationContext),
            armoredDeviceOwnerActive = armorPersists
        )
        if (!protectionEngaged || deviceOwnerManager.isMaintenanceActive()) return false

        val nowElapsed = SystemClock.elapsedRealtime()
        val className = event.className?.toString().orEmpty()
        val eventValues = eventTextValues(event)
        val signals = SettingsInterceptionPolicy.EventSignals(
            packageName = packageName,
            isViewClickedEvent = event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED,
            isWindowTransitionEvent =
                event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                    event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            guardArmed = nowElapsed <= pendingProtectionUntilElapsed,
            classTargetsAccessibilityServiceToggle =
                AccessibilitySettingsPolicy.classTargetsAccessibilityServiceToggle(className),
            classTargetsAccessibilityList =
                AccessibilitySettingsPolicy.classTargetsAccessibilityList(className),
            classTargetsDeviceAdmin =
                ManagedSelfProtectionPolicy.classTargetsDeviceAdmin(className),
            classTargetsAppDetails =
                ManagedSelfProtectionPolicy.classTargetsAppDetails(className),
            classTargetsUninstall =
                ManagedSelfProtectionPolicy.classTargetsUninstall(className),
            classTargetsEssentialSpecialAccess =
                ManagedSelfProtectionPolicy.classTargetsEssentialSpecialAccess(className),
            isGenericSubSettings = className.contains("SubSettings", ignoreCase = true),
            textMentionsAccessibility =
                AccessibilitySettingsPolicy.textTargetsAccessibility(eventValues),
            textMentionsInstalledAccessibilityApps =
                AccessibilitySettingsPolicy.textTargetsInstalledAccessibilityApps(eventValues),
            textMentionsAccessibilityDisclosure =
                AccessibilitySettingsPolicy.textTargetsAccessibilityDisclosure(eventValues),
            textMentionsDeviceAdmin =
                ManagedSelfProtectionPolicy.textTargetsDeviceAdmin(eventValues),
            textMentionsFocusGuard =
                ManagedSelfProtectionPolicy.textTargetsFocusGuard(eventValues),
            textMentionsDestructiveControl =
                ManagedSelfProtectionPolicy.textTargetsDestructiveControl(eventValues),
            textMentionsEssentialSpecialAccess =
                ManagedSelfProtectionPolicy.textTargetsEssentialSpecialAccess(eventValues)
        )

        val decision = SettingsInterceptionPolicy.decide(
            signals = signals,
            selfProtectionEngaged = true,
            strictPomodoroActive = strictPomodoroActive,
            deviceAdminActivationAuthorized = DeviceAdminActivationWindow.isAuthorized(
                context = service,
                deviceAdminActive = deviceOwnerManager.isDeviceAdminActive()
            ),
            rootSignals = SettingsInterceptionPolicy.RootSignals(
                mentionsAccessibility = ::rootMentionsAccessibility,
                mentionsDeviceAdmin = ::rootMentionsDeviceAdmin,
                mentionsFocusGuard = ::rootMentionsFocusGuard,
                mentionsDestructiveControl = ::rootMentionsDestructiveControl,
                mentionsEssentialSpecialAccess = ::rootMentionsEssentialSpecialAccess
            )
        )

        return when (decision) {
            SettingsInterceptionPolicy.Decision.IGNORE -> false
            SettingsInterceptionPolicy.Decision.POMODORO_LOCK -> {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                presenter.launchPomodoroLockScreen()
                true
            }
            SettingsInterceptionPolicy.Decision.PROTECT -> {
                presenter.executeSelfProtectionAction()
                true
            }
            SettingsInterceptionPolicy.Decision.PROTECT_AND_ARM_GUARD -> {
                pendingProtectionUntilElapsed =
                    nowElapsed + BlockingAccessibilityService.SETTINGS_TRANSITION_GUARD_MILLIS
                presenter.executeSelfProtectionAction()
                true
            }
        }
    }

    private fun eventTextValues(event: AccessibilityEvent): List<CharSequence?> = buildList {
        addAll(event.text.orEmpty())
        add(event.contentDescription)
        event.source?.let { source ->
            add(source.text)
            add(source.contentDescription)
            add(source.viewIdResourceName)
            if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
                clickSearchTerms.forEach { term ->
                    val matchingNodes = runCatching {
                        source.findAccessibilityNodeInfosByText(term)
                    }.getOrDefault(emptyList())
                    matchingNodes.forEach { node ->
                        add(node.text)
                        add(node.contentDescription)
                        add(node.viewIdResourceName)
                        recycleSafely(node)
                    }
                }
                addAll(sameRowClickTextValues(source))
            }
            recycleSafely(source)
        }
    }

    private fun sameRowClickTextValues(
        source: AccessibilityNodeInfo
    ): List<CharSequence?> {
        val sourceBounds = Rect().also(source::getBoundsInScreen)
        if (sourceBounds.isEmpty || source.isScrollable) return emptyList()

        val root = service.rootInActiveWindow ?: return emptyList()
        return try {
            val rootBounds = Rect().also(root::getBoundsInScreen)
            if (!BlockingAccessibilityService.shouldSearchSameRowMarkers(
                    sourceBounds,
                    rootBounds
                )
            ) {
                return emptyList()
            }

            buildList {
                clickSearchTerms.forEach { term ->
                    val matchingNodes = runCatching {
                        root.findAccessibilityNodeInfosByText(term)
                    }.getOrDefault(emptyList())
                    matchingNodes.forEach { node ->
                        val nodeBounds = Rect().also(node::getBoundsInScreen)
                        if (BlockingAccessibilityService.boundsShareHorizontalRow(
                                sourceBounds,
                                nodeBounds
                            )
                        ) {
                            add(node.text)
                            add(node.contentDescription)
                            add(node.viewIdResourceName)
                        }
                        recycleSafely(node)
                    }
                }
            }
        } finally {
            recycleSafely(root)
        }
    }

    private fun rootMentionsAccessibility(): Boolean = rootContainsAny(
        searchTerms = AccessibilitySettingsPolicy.searchTerms,
        classifier = AccessibilitySettingsPolicy::textTargetsAccessibility,
        screenLabel = "Acessibilidade"
    )

    private fun rootMentionsDeviceAdmin(): Boolean = rootContainsAny(
        searchTerms = ManagedSelfProtectionPolicy.deviceAdminSearchTerms,
        classifier = ManagedSelfProtectionPolicy::textTargetsDeviceAdmin,
        screenLabel = "Administrador do dispositivo"
    )

    private fun rootMentionsFocusGuard(): Boolean = rootContainsAny(
        searchTerms = ManagedSelfProtectionPolicy.focusGuardSearchTerms,
        classifier = ManagedSelfProtectionPolicy::textTargetsFocusGuard,
        screenLabel = "controles do FocusGuard"
    )

    private fun rootMentionsDestructiveControl(): Boolean = rootContainsAny(
        searchTerms = ManagedSelfProtectionPolicy.destructiveControlSearchTerms,
        classifier = ManagedSelfProtectionPolicy::textTargetsDestructiveControl,
        screenLabel = "ação destrutiva"
    )

    private fun rootMentionsEssentialSpecialAccess(): Boolean = rootContainsAny(
        searchTerms = ManagedSelfProtectionPolicy.essentialSpecialAccessSearchTerms,
        classifier = ManagedSelfProtectionPolicy::textTargetsEssentialSpecialAccess,
        screenLabel = "acesso especial essencial"
    )

    private fun rootContainsAny(
        searchTerms: Iterable<String>,
        classifier: (Iterable<CharSequence?>) -> Boolean,
        screenLabel: String
    ): Boolean {
        val root = service.rootInActiveWindow ?: return false
        return try {
            searchTerms.any { term ->
                val nodes = root.findAccessibilityNodeInfosByText(term)
                val found = nodes.any { node ->
                    classifier(listOf(node.text, node.contentDescription, node.viewIdResourceName))
                }
                nodes.forEach(::recycleSafely)
                found
            }
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

    private fun recycleSafely(node: AccessibilityNodeInfo?) {
        if (node != null) runCatching { node.recycle() }
    }
}
