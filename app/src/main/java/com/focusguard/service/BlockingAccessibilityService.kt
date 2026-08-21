package com.focusguard.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import com.focusguard.R
import com.focusguard.admin.DeviceOwnerManager
import com.focusguard.focusmode.FocusModePolicy
import com.focusguard.focusmode.FocusModeStore
import com.focusguard.manager.BlockingSessionManager
import com.focusguard.manager.StrictPomodoroLock
import com.focusguard.security.SettingsInterceptionPolicy
import com.focusguard.security.SelfProtectionStateStore
import com.focusguard.utils.FocusGuardLogger
import com.focusguard.utils.UsageLimitForegroundPolicy
import com.focusguard.utils.WebsiteBlocker
import dagger.hilt.android.AndroidEntryPoint
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@AndroidEntryPoint
class BlockingAccessibilityService : AccessibilityService() {

    @Inject lateinit var sessionManager: BlockingSessionManager
    @Inject lateinit var deviceOwnerManager: DeviceOwnerManager
    @Inject lateinit var blockingStateLoader: AccessibilityBlockingStateLoader
    @Inject lateinit var websiteUsageStore: AccessibilityWebsiteUsageStore

    private lateinit var blockPresenter: AccessibilityBlockPresenter
    private lateinit var settingsInterceptor: AccessibilitySettingsInterceptor
    private lateinit var websiteTracker: AccessibilityWebsiteTracker
    private lateinit var runtimeController: AccessibilityServiceRuntimeController
    private lateinit var windowBlockController: AccessibilityWindowBlockController

    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(serviceJob + Dispatchers.IO)
    private val isRefreshing = AtomicBoolean(false)
    private val refreshRequested = AtomicBoolean(false)

    @Volatile private var blockedAppsSet: Set<String> = emptySet()
    @Volatile private var blockedWebsitesDomainSet: Set<String> = emptySet()
    @Volatile private var blockedWebsiteAppDomains: Map<String, String> = emptyMap()
    @Volatile private var limitedWebsiteDomains: Set<String> = emptySet()
    @Volatile private var limitedWebsiteAppDomains: Map<String, String> = emptyMap()
    @Volatile private var isBlockingSessionActive = false
    @Volatile private var isPomodoroStrictActive = false
    @Volatile private var focusModeSessionActive = false
    @Volatile private var focusModeFallbackActive = false
    @Volatile private var focusModeBlockedAppsSet: Set<String> = emptySet()
    @Volatile private var focusModeAllowedAppsSet: Set<String> = emptySet()
    @Volatile private var hasActiveAppLimits = false
    @Volatile private var lastEnforcementFingerprint: String? = null

    private var lastLoadTime = 0L
    private var lastBrowserCheck = 0L
    private var defaultLauncherPackage: String? = null
    private var powerManager: PowerManager? = null
    @Volatile private var foregroundPackageName: String? = null

    private var appLimitMonitoringJob: Job? = null

    private val cacheTimeoutMillis = 5_000L
    private val browserDebounceMillis = 120L
    private val appLimitPulseMillis = 5_000L

    private val interceptionPackages = SettingsInterceptionPolicy.interceptionPackages

    private var browserPackages: Set<String> = emptySet()

    override fun onCreate() {
        super.onCreate()
        blockPresenter = AccessibilityBlockPresenter(
            service = this,
            scope = scope,
            strictPomodoroActive = { isPomodoroStrictActive },
            onWebsiteBlockStarted = { now ->
                if (::websiteTracker.isInitialized) websiteTracker.stop(now)
            }
        )
        websiteTracker = AccessibilityWebsiteTracker(
            service = this,
            scope = scope,
            usageStore = websiteUsageStore,
            powerManager = { powerManager },
            foregroundPackage = { foregroundPackageName },
            browserPackages = { browserPackages },
            blockedWebsiteRules = { blockedWebsitesDomainSet },
            limitedWebsiteRules = { limitedWebsiteDomains },
            onWebsiteBlocked = ::blockWebsite,
            onUsageLimitExceeded = { domain, packageName, exceededRules ->
                blockedWebsitesDomainSet = blockedWebsitesDomainSet + exceededRules
                if (packageName in browserPackages) {
                    blockWebsite(domain, packageName)
                } else {
                    val displayRule = exceededRules.firstOrNull() ?: domain
                    blockedWebsiteAppDomains = blockedWebsiteAppDomains +
                        (packageName to displayRule)
                    blockWebsiteApp(displayRule, packageName)
                }
            }
        )
        settingsInterceptor = AccessibilitySettingsInterceptor(
            service = this,
            deviceOwnerManager = deviceOwnerManager,
            presenter = blockPresenter
        )
        windowBlockController = AccessibilityWindowBlockController(
            service = this,
            presenter = blockPresenter,
            websiteTracker = websiteTracker
        )
        runtimeController = AccessibilityServiceRuntimeController(
            service = this,
            onPackageChanged = { _, isKnownBrowser ->
                browserPackages = runtimeController.calculateBrowserPackages()
                lastLoadTime = 0L
                scope.launch {
                    if (isKnownBrowser) deviceOwnerManager.invalidateWebsitePolicyCache()
                    sessionManager.checkAndEnforce()
                }
            },
            onRefresh = { intent ->
                applyImmediateBlockingSnapshot(intent)
                lastLoadTime = 0L
                refreshData()
            },
            onBlockNoticeReady = blockPresenter::dismissBlockNoticeCurtain,
            onScreenOff = {
                foregroundPackageName = null
                stopWebsiteTracking()
            }
        )
        refreshSynchronousProtectionState()
        powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        runtimeController.start()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Do this before any node/tree work or asynchronous Room refresh. The
        // persisted snapshot exists specifically to cover the first event after
        // Android binds or recreates the service.
        refreshSynchronousProtectionState()
        defaultLauncherPackage = runtimeController.calculateDefaultLauncher()
        foregroundPackageName = rootInActiveWindow?.packageName?.toString()
        browserPackages = runtimeController.calculateBrowserPackages()
        refreshData()
        startAppLimitMonitoringPulse()
        scope.launch {
            sessionManager.checkAndEnforce()
        }

        // Preserva capacidades estáticas carregadas do XML, especialmente
        // canRetrieveWindowContent; apenas campos dinâmicos podem ser alterados aqui.
        serviceInfo = serviceInfo.apply {
            eventTypes = requestedAccessibilityEventTypes()
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = EVENT_NOTIFICATION_TIMEOUT_MILLIS
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        try {
            val now = System.currentTimeMillis()
            if (now - lastLoadTime > cacheTimeoutMillis) refreshData()

            // Fast path: settings interception decides on `event.packageName` alone,
            // before resolveEventPackageName() is allowed to touch the node tree.
            //
            // That resolution costs one or two synchronous binder calls into the
            // inspected app (windows walk plus rootInActiveWindow), on every event.
            // Those milliseconds are exactly the window in which the user can reach
            // the switch that disables this service, so nothing that can block runs
            // ahead of the decision to bounce them out.
            val directPackage = event.packageName?.toString().orEmpty()
            val eligibleForInterception =
                event.eventType in AccessibilityServiceContract.settingsInterceptionEventTypes
            if (eligibleForInterception &&
                directPackage in interceptionPackages &&
                handleSettingsInterception(event, directPackage)
            ) {
                return
            }

            val packageName = runtimeController.resolveEventPackageName(event)
            // Second chance: `event.packageName` is occasionally blank, and for
            // TYPE_WINDOWS_CHANGED it can name a different window than the one that
            // actually changed. Only reached when the fast path could not decide.
            if (eligibleForInterception &&
                packageName != directPackage &&
                packageName in interceptionPackages &&
                handleSettingsInterception(event, packageName)
            ) {
                return
            }

            val isWindowTransition =
                event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                    event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
            if (isWindowTransition) {
                if (FocusModeStore.isActive(applicationContext) != focusModeSessionActive) {
                    refreshFocusModeFallbackState()
                }
                foregroundPackageName = packageName.takeIf(String::isNotBlank)
                if (packageName !in browserPackages &&
                    packageName !in limitedWebsiteAppDomains
                ) {
                    stopWebsiteTracking(now)
                }
            }

            if (isPomodoroStrictActive &&
                isWindowTransition
            ) {
                handleStrictPomodoro(packageName, event.className?.toString().orEmpty())
                return
            }

            val focusLauncherMustReturn = focusModeFallbackActive &&
                packageName == defaultLauncherPackage
            if (packageName in focusModeAllowedAppsSet && !focusLauncherMustReturn) {
                stopWebsiteTracking(now)
                return
            }

            if (!isBlockingSessionActive && limitedWebsiteDomains.isEmpty()) return

            when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                    handleWindowStateChanged(event, packageName)
                }
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
                AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                    val fastEvent = event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                    if (packageName in browserPackages &&
                        (fastEvent || now - lastBrowserCheck >= browserDebounceMillis)
                    ) {
                        lastBrowserCheck = now
                        handleBrowserEvent(event)
                    }
                }
            }
        } catch (error: RuntimeException) {
            FocusGuardLogger.logError("A11y", "Erro no evento de acessibilidade", error)
        }
    }

    private fun applyImmediateBlockingSnapshot(intent: Intent) {
        if (!intent.hasExtra(EXTRA_BLOCKING_SNAPSHOT_PRESENT)) return

        refreshFocusModeFallbackState()
        val apps = intent.getStringArrayListExtra(EXTRA_BLOCKED_APPS_SNAPSHOT)
            .orEmpty()
            .filter(String::isNotBlank)
            .toSet()
        val sites = WebsiteBlocker.normalizeRules(
            intent.getStringArrayListExtra(EXTRA_BLOCKED_SITES_SNAPSHOT).orEmpty()
        )
        blockedAppsSet = apps
        blockedWebsitesDomainSet = sites
        blockedWebsiteAppDomains = WebsiteBlocker.appPackageDomainsFor(sites)
        isPomodoroStrictActive = intent.getBooleanExtra(
            EXTRA_STRICT_POMODORO_SNAPSHOT,
            false
        )
        isBlockingSessionActive = intent.getBooleanExtra(
            EXTRA_BLOCKING_ACTIVE_SNAPSHOT,
            apps.isNotEmpty() || sites.isNotEmpty()
        )
        lastLoadTime = System.currentTimeMillis()
    }

    override fun onInterrupt() {
        foregroundPackageName = null
        stopWebsiteTracking()
    }

    private fun refreshData() {
        refreshRequested.set(true)
        if (!isRefreshing.compareAndSet(false, true)) return
        scope.launch {
            try {
                do {
                    refreshRequested.set(false)
                    try {
                        val state = blockingStateLoader.load(lastEnforcementFingerprint)
                        withContext(Dispatchers.Main) {
                            isPomodoroStrictActive = state.strictPomodoroActive
                            focusModeSessionActive = state.focusModeSessionActive
                            focusModeFallbackActive = state.focusModeFallbackActive
                            focusModeBlockedAppsSet = state.focusModeBlockedApps
                            focusModeAllowedAppsSet = state.focusModeAllowedApps
                            blockedAppsSet = state.blockedApps
                            blockedWebsitesDomainSet = state.blockedWebsiteDomains
                            blockedWebsiteAppDomains = state.blockedWebsiteAppDomains
                            limitedWebsiteDomains = state.limitedWebsiteDomains
                            limitedWebsiteAppDomains = state.limitedWebsiteAppDomains
                            hasActiveAppLimits = state.hasActiveAppLimits
                            isBlockingSessionActive = state.blockingSessionActive
                            lastEnforcementFingerprint = state.enforcementFingerprint
                            lastLoadTime = System.currentTimeMillis()
                        }
                        if (state.shouldReconcilePolicies) {
                            sessionManager.checkAndEnforce()
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        FocusGuardLogger.logError(
                            "A11y",
                            "Falha ao atualizar bloqueios",
                            error
                        )
                    }
                } while (refreshRequested.get())
            } finally {
                isRefreshing.set(false)
                if (refreshRequested.get() && serviceJob.isActive) refreshData()
            }
        }
    }

    private fun startAppLimitMonitoringPulse() {
        if (appLimitMonitoringJob?.isActive == true) return
        appLimitMonitoringJob = scope.launch {
            while (isActive) {
                delay(appLimitPulseMillis)
                if (!hasActiveAppLimits || powerManager?.isInteractive != true) continue

                try {
                    val packageName = foregroundPackageName ?: continue
                    val shouldEnforce = UsageLimitForegroundPolicy.shouldEnforceCurrentApp(
                        foregroundPackageName = packageName,
                        exceededPackages = blockingStateLoader.calculateExceededAppLimits(),
                        focusGuardPackageName = this@BlockingAccessibilityService.packageName,
                        launcherPackageName = defaultLauncherPackage,
                        isDeviceInteractive = true
                    )
                    if (!shouldEnforce) continue

                    withContext(Dispatchers.Main) {
                        if (foregroundPackageName != packageName) return@withContext
                        blockedAppsSet = blockedAppsSet + packageName
                        blockApp(packageName)
                    }
                    sessionManager.checkAndEnforce()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    FocusGuardLogger.logError(
                        "A11y",
                        "Falha ao monitorar limite do app em primeiro plano",
                        error
                    )
                }
            }
        }
    }

    private fun handleWindowStateChanged(
        event: AccessibilityEvent,
        packageName: String
    ) = windowBlockController.handleWindow(
        event = event,
        packageName = packageName,
        defaultLauncherPackage = defaultLauncherPackage,
        focusModeFallbackActive = focusModeFallbackActive,
        focusModeBlockedApps = focusModeBlockedAppsSet,
        focusModeAllowedApps = focusModeAllowedAppsSet,
        blockedApps = blockedAppsSet,
        blockedWebsiteApps = blockedWebsiteAppDomains,
        limitedWebsiteApps = limitedWebsiteAppDomains,
        browserPackages = browserPackages,
        blockedWebsiteRules = blockedWebsitesDomainSet,
        limitedWebsiteRules = limitedWebsiteDomains
    )

    private fun handleStrictPomodoro(packageName: String, className: String) =
        windowBlockController.handleStrictPomodoro(
            packageName,
            className,
            defaultLauncherPackage
        )

    private fun handleSettingsInterception(
        event: AccessibilityEvent,
        packageName: String
    ): Boolean = settingsInterceptor.handle(
        event = event,
        packageName = packageName,
        cachedProtectionActive = isBlockingSessionActive,
        strictPomodoroActive = isPomodoroStrictActive
    )

    private fun refreshSynchronousProtectionState() {
        refreshFocusModeFallbackState()
        isBlockingSessionActive = isSelfProtectionEngaged(
            cachedActive = isBlockingSessionActive,
            persistedActive = SelfProtectionStateStore.isArmed(applicationContext),
            focusModeActive = FocusModeStore.isActive(applicationContext),
            armoredDeviceOwnerActive = deviceOwnerManager.isDeviceOwnerActive() &&
                deviceOwnerManager.isArmoredProtectionArmed()
        )
    }

    private fun refreshFocusModeFallbackState() {
        val session = FocusModeStore.readSession(applicationContext)
            ?.takeIf { it.isActive() }
        focusModeSessionActive = session != null
        val nativeLockdownActive = session != null &&
            FocusModePolicy.usesNativeFocusLockdown(
                deviceOwnerActive = deviceOwnerManager.isDeviceOwnerActive(),
                systemLockdownSupported =
                    deviceOwnerManager.isFocusModeSystemLockdownSupported()
            )
        focusModeFallbackActive = session != null && !nativeLockdownActive
        focusModeAllowedAppsSet = session?.allowedPackages.orEmpty()
        focusModeBlockedAppsSet = if (focusModeFallbackActive) {
            session?.blockedPackages.orEmpty()
        } else {
            emptySet()
        }
    }

    private fun handleBrowserEvent(event: AccessibilityEvent) =
        websiteTracker.handleBrowserEvent(event)

    private fun stopWebsiteTracking(now: Long = System.currentTimeMillis()) =
        websiteTracker.stop(now)

    private fun blockApp(packageName: String) = blockPresenter.blockApp(packageName)

    private fun blockWebsite(domain: String, browserPackageName: String) =
        blockPresenter.blockWebsite(domain, browserPackageName)

    private fun blockWebsiteApp(domain: String, packageName: String) =
        blockPresenter.blockWebsiteApp(domain, packageName)

    override fun onDestroy() {
        stopWebsiteTracking()
        blockPresenter.destroy()
        runtimeController.destroy()
        scope.cancel()
        super.onDestroy()

        if (StrictPomodoroLock.isActive(applicationContext)) {
            FocusGuardLogger.log(
                "A11y",
                "Serviço destruído durante Pomodoro; reativando watchdog"
            )
            PomodoroForegroundService.start(applicationContext)
            PomodoroForegroundService.scheduleWatchdogAlarm(applicationContext)
        } else {
            runCatching {
                Toast.makeText(
                    this,
                    getString(R.string.servico_focusguard_parado),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    companion object {
        internal const val SETTINGS_TRANSITION_GUARD_MILLIS = 2_000L
        internal const val SELF_PROTECTION_HOME_DELAY_MILLIS = 80L
        internal const val SELF_PROTECTION_ACTION_DEBOUNCE_MILLIS = 2_500L
        internal const val SELF_PROTECTION_NOTICE_DURATION_MILLIS = 1_200L
        internal const val BLOCK_NOTICE_RELAUNCH_COOLDOWN_MILLIS = 1_500L
        internal const val INSTANT_CURTAIN_FAILSAFE_MILLIS = 5_000L
        internal const val EVENT_NOTIFICATION_TIMEOUT_MILLIS = 0L
        internal const val WEBSITE_BLOCK_NOTICE_DURATION_MILLIS = 1_000L

        const val ACTION_REFRESH_BLOCKING = "com.focusguard.ACTION_REFRESH_BLOCKING"
        const val ACTION_BLOCK_NOTICE_READY = "com.focusguard.ACTION_BLOCK_NOTICE_READY"
        const val EXTRA_STRICT_BLOCK = "STRICT_BLOCK"
        const val EXTRA_BLOCKED_PACKAGE = "BLOCKED_PACKAGE"
        const val EXTRA_BLOCKED_DOMAIN = "BLOCKED_DOMAIN"
        const val EXTRA_REDIRECT_BROWSER_PACKAGE = "REDIRECT_BROWSER_PACKAGE"
        const val EXTRA_BLOCK_DETECTED_ELAPSED_REALTIME = "BLOCK_DETECTED_ELAPSED_REALTIME"
        internal const val EXTRA_BLOCKING_SNAPSHOT_PRESENT = "BLOCKING_SNAPSHOT_PRESENT"
        internal const val EXTRA_BLOCKED_APPS_SNAPSHOT = "BLOCKED_APPS_SNAPSHOT"
        internal const val EXTRA_BLOCKED_SITES_SNAPSHOT = "BLOCKED_SITES_SNAPSHOT"
        internal const val EXTRA_BLOCKING_ACTIVE_SNAPSHOT = "BLOCKING_ACTIVE_SNAPSHOT"
        internal const val EXTRA_STRICT_POMODORO_SNAPSHOT = "STRICT_POMODORO_SNAPSHOT"

        internal fun settingsInterceptionEventTypesForTest(): Set<Int> =
            AccessibilityServiceContract.settingsInterceptionEventTypes

        internal fun settingsTransitionGuardMillisForTest(): Long =
            SETTINGS_TRANSITION_GUARD_MILLIS

        internal fun selfProtectionActionDebounceMillisForTest(): Long =
            SELF_PROTECTION_ACTION_DEBOUNCE_MILLIS

        internal fun shouldExecuteProtectionAction(
            blockedUntilElapsed: Long,
            nowElapsed: Long
        ): Boolean = AccessibilityServiceContract.shouldExecuteProtectionAction(
            blockedUntilElapsed,
            nowElapsed
        )

        internal fun isSelfProtectionEngaged(
            cachedActive: Boolean,
            persistedActive: Boolean,
            focusModeActive: Boolean,
            armoredDeviceOwnerActive: Boolean
        ): Boolean = AccessibilityServiceContract.isSelfProtectionEngaged(
            cachedActive,
            persistedActive,
            focusModeActive,
            armoredDeviceOwnerActive
        )

        internal fun shouldSearchSameRowMarkers(clicked: Rect, root: Rect): Boolean =
            AccessibilityServiceContract.shouldSearchSameRowMarkers(clicked, root)

        internal fun boundsShareHorizontalRow(clicked: Rect, marker: Rect): Boolean =
            AccessibilityServiceContract.boundsShareHorizontalRow(clicked, marker)

        internal fun requestedAccessibilityEventTypes(): Int =
            AccessibilityServiceContract.requestedAccessibilityEventTypes()

        internal fun shouldLaunchBlockNotice(
            previousKey: String?,
            previousLaunchElapsed: Long,
            requestedKey: String,
            nowElapsed: Long
        ): Boolean = AccessibilityServiceContract.shouldLaunchBlockNotice(
            previousKey,
            previousLaunchElapsed,
            requestedKey,
            nowElapsed
        )

        internal fun createRefreshBlockingIntent(
            context: Context,
            blockedApps: Collection<String>,
            blockedSites: Collection<String>,
            blockingActive: Boolean,
            strictPomodoro: Boolean
        ): Intent = AccessibilityServiceContract.createRefreshBlockingIntent(
            context,
            blockedApps,
            blockedSites,
            blockingActive,
            strictPomodoro
        )

        internal fun createBlockNoticeIntent(
            context: Context,
            strictBlock: Boolean,
            blockedPackage: String?,
            blockedDomain: String?,
            redirectBrowserPackage: String?,
            detectedElapsedRealtime: Long = SystemClock.elapsedRealtime()
        ): Intent = AccessibilityServiceContract.createBlockNoticeIntent(
            context,
            strictBlock,
            blockedPackage,
            blockedDomain,
            redirectBrowserPackage,
            detectedElapsedRealtime
        )

        internal fun createSafeRedirectIntent(browserPackageName: String): Intent =
            AccessibilityServiceContract.createSafeRedirectIntent(browserPackageName)
    }
}
