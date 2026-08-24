package com.focusguard.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.focusguard.MainActivity
import com.focusguard.R
import com.focusguard.admin.DeviceOwnerManager
import com.focusguard.data.PredefinedWebsites
import com.focusguard.database.AppDatabase
import com.focusguard.focusmode.FocusModePolicy
import com.focusguard.focusmode.FocusModeStore
import com.focusguard.manager.BlockingSessionManager
import com.focusguard.manager.StrictPomodoroLock
import com.focusguard.security.AccessibilitySettingsPolicy
import com.focusguard.security.AuthenticatedRemovalWindow
import com.focusguard.security.AuthManager
import com.focusguard.security.CurtainDestinationReadyCoordinator
import com.focusguard.security.CurtainSafeWindowPolicy
import com.focusguard.security.DeviceAdminActivationWindow
import com.focusguard.security.ImmediateInterceptionPolicy
import com.focusguard.security.ImmediateInterceptionPolicy.DirectDecision
import com.focusguard.security.ImmediateInterceptionPolicy.SettingsSurface
import com.focusguard.security.LauncherIndexRefreshPolicy
import com.focusguard.security.ManagedSelfProtectionPolicy
import com.focusguard.security.ProtectedSettingsResetWindow
import com.focusguard.security.SettingsInterceptionPolicy
import com.focusguard.security.SelfProtectionStateStore
import com.focusguard.security.UsageAccessPausePolicy
import com.focusguard.ui.BlockNoticeActivity
import com.focusguard.ui.MasterRemovalActivity
import com.focusguard.ui.PomodoroLockActivity
import com.focusguard.utils.FocusGuardLogger
import com.focusguard.utils.PermissionUtils
import com.focusguard.utils.UsageLimitForegroundPolicy
import com.focusguard.utils.WebsiteBlocker
import com.focusguard.utils.WebsiteUsageLimitPolicy
import dagger.hilt.android.AndroidEntryPoint
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
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

    internal enum class InstantCurtainFailsafeDecision {
        NO_ACTION,
        HIDE,
        EVACUATE_THEN_HIDE
    }

    internal enum class CurtainLaunchFailureDecision {
        NO_ACTION,
        EVACUATE_THEN_HIDE
    }

    @Inject lateinit var authManager: AuthManager

    private lateinit var database: AppDatabase
    private lateinit var sessionManager: BlockingSessionManager
    private lateinit var deviceOwnerManager: DeviceOwnerManager

    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(serviceJob + Dispatchers.IO)
    private val isRefreshing = AtomicBoolean(false)
    private val refreshRequested = AtomicBoolean(false)
    private val isRefreshingLauncherIndex = AtomicBoolean(false)
    private val launcherIndexRefreshRequested = AtomicBoolean(false)

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
    private var lastWebsiteBlockTime = 0L
    private var lastWebsiteBlockKey: String? = null
    private var lastToastTime = 0L
    private var defaultLauncherPackage: String? = null
    @Volatile private var launcherLabelIndex =
        ImmediateInterceptionPolicy.buildLauncherLabelIndex(emptyList())
    @Volatile private var lastLauncherIndexRefreshRequestElapsed = 0L
    @Volatile private var hasSuccessfulLauncherIndexSnapshot = false
    private var usageStatsManager: UsageStatsManager? = null
    private var powerManager: PowerManager? = null
    private var windowManager: WindowManager? = null
    private var protectedPowerMenuController: ProtectedPowerMenuController? = null
    private var accessibilityServiceConnected = false
    @Volatile private var foregroundPackageName: String? = null
    @Volatile private var deviceOwnerActiveCached = false
    @Volatile private var deviceAdminActiveCached = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private var instantBlockCurtain: View? = null
    private var instantBlockCurtainMessage: TextView? = null
    private var instantBlockCurtainLayoutParams: WindowManager.LayoutParams? = null
    private var instantBlockCurtainAttached = false
    private var instantBlockCurtainVisible = false
    private var instantBlockCurtainMode: CurtainMode? = null
    private var instantBlockCurtainGeneration = 0L
    private var awaitingSafeSurfaceGeneration = 0L
    private val instantBlockCurtainGenerationCounter = AtomicLong(0L)
    private var failsafeEvacuationGeneration = 0L
    private var pendingReadyWindowValidationGeneration = 0L
    private val instantCurtainFailsafe = Runnable { handleInstantCurtainFailsafe() }
    private val instantCurtainFailsafeRelease = Runnable {
        val generation = failsafeEvacuationGeneration
        if (generation <= 0L ||
            generation != instantBlockCurtainGeneration
        ) return@Runnable
        completeCurtainFailsafeAfterEvacuation(generation)
    }
    private val readyWindowValidation = Runnable { validateReadyDestinationWindows() }
    private val protectionCurtainDismiss = Runnable { handleTimedProtectionCurtainDismiss() }
    @Volatile private var protectionActionUntilElapsed = 0L

    private val websiteTrackingLock = Any()
    @Volatile private var trackedDomain: String? = null
    @Volatile private var trackedPackageName: String? = null
    @Volatile private var trackedSinceMillis = 0L
    private var websiteTrackingJob: Job? = null
    private var appLimitMonitoringJob: Job? = null

    private data class WebsiteUsageSlice(
        val domain: String,
        val deltaMillis: Long,
        val packageName: String
    )

    private enum class CurtainMode {
        BLOCK_NOTICE,
        SELF_PROTECTION
    }

    private val cacheTimeoutMillis = 5_000L
    private val browserDebounceMillis = 120L
    private val websiteBlockCooldownMillis = 1_500L
    private val websitePulseMillis = 5_000L
    private val appLimitPulseMillis = 5_000L
    private val maxUsageDeltaMillis = 15_000L
    private val channelId = "focusguard_service_channel"
    private val notificationId = 101
    private val dateFormat = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd", Locale.US)
    }

    private val phonePackages = setOf(
        "com.android.dialer",
        "com.google.android.dialer",
        "com.android.phone",
        "com.android.server.telecom",
        "com.samsung.android.dialer",
        "com.samsung.android.incallui"
    )

    // Fonte única em SettingsInterceptionPolicy — estas listas estavam duplicadas aqui.
    private val settingsPackages = SettingsInterceptionPolicy.settingsPackages
    private val interceptionPackages = SettingsInterceptionPolicy.interceptionPackages
    // Locator terms only. Full classification still uses the richer policy
    // dictionaries after a node is found. Keeping this list tiny matters because
    // every entry can become a synchronous accessibility-tree query on a click.
    private val directClickContextSufficientTerms =
        (listOf("FocusGuard", "Focus Guard", "com.focusguard") +
            AccessibilitySettingsPolicy.accessibilityDisclosureNodeSearchTerms +
            AccessibilitySettingsPolicy.installedAccessibilityAppsNodeSearchTerms).distinct()
    private val clickInterceptionSearchTerms =
        (directClickContextSufficientTerms +
            listOf("admin", "Informações do app", "Informações do aplicativo", "App info"))
            .distinct()

    private var pendingSettingsProtectionUntilElapsed = 0L

    private var browserPackages: Set<String> = emptySet()
    private val knownBrowserPackages = setOf(
        "com.android.chrome",
        "com.chrome.beta",
        "com.chrome.dev",
        "com.chrome.canary",
        "com.microsoft.emmx",
        "com.microsoft.emmx.beta",
        "com.microsoft.emmx.dev",
        "com.microsoft.emmx.canary",
        "com.brave.browser",
        "com.brave.browser_beta",
        "com.brave.browser_nightly",
        "com.kiwibrowser.browser",
        "com.kiwibrowser.browser.dev",
        "com.vivaldi.browser",
        "com.vivaldi.browser.snapshot",
        "com.ecosia.android",
        "com.yandex.browser",
        "com.UCMobile.intl",
        "com.UCMobile.intl.mi",
        "org.mozilla.firefox",
        "org.mozilla.firefox_beta",
        "org.mozilla.fenix",
        "org.mozilla.fennec_aurora",
        "org.mozilla.focus",
        "org.mozilla.klar",
        "com.opera.browser",
        "com.opera.browser.beta",
        "com.opera.mini.native",
        "com.opera.gx",
        "com.sec.android.app.sbrowser",
        "com.sec.android.app.sbrowser.beta",
        "com.duckduckgo.mobile.android",
        "com.google.android.googlequicksearchbox"
    )

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val changedPackage = intent?.data?.schemeSpecificPart.orEmpty()
            calculateBrowserPackages()
            refreshLauncherIndex(force = true)
            lastLoadTime = 0L
            scope.launch {
                if (changedPackage in knownBrowserPackages) {
                    deviceOwnerManager.invalidateWebsitePolicyCache()
                }
                sessionManager.checkAndEnforce()
            }
        }
    }

    private val launcherReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshLauncherIndex(force = true)
        }
    }

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_DEV_RELINQUISH_ACCESSIBILITY) {
                relinquishAccessibilityForDevelopment()
                return
            }
            intent?.let(::applyImmediateBlockingSnapshot)
            lastLoadTime = 0L
            refreshData()
        }
    }

    private val curtainDestinationReadyListener =
        CurtainDestinationReadyCoordinator.Listener { generation ->
            mainHandler.post {
                if (shouldDismissCurtain(instantBlockCurtainGeneration, generation)) {
                    if (pendingReadyWindowValidationGeneration != generation) {
                        pendingReadyWindowValidationGeneration = generation
                        mainHandler.postDelayed(
                            readyWindowValidation,
                            SAFE_WINDOW_SETTLE_MILLIS
                        )
                    }
                }
            }
        }

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                foregroundPackageName = null
                stopWebsiteTracking()
                protectedPowerMenuController?.onScreenOff()
                when (screenOffCurtainDecision(
                    curtainVisible = instantBlockCurtainVisible,
                    awaitingSafeSurfaceGeneration = awaitingSafeSurfaceGeneration,
                    unsafeWindowVisible = instantBlockCurtainVisible &&
                        hasUnsafeVisibleWindow()
                )) {
                    InstantCurtainFailsafeDecision.NO_ACTION -> Unit
                    InstantCurtainFailsafeDecision.HIDE -> dismissInstantBlockCurtain()
                    InstantCurtainFailsafeDecision.EVACUATE_THEN_HIDE ->
                        beginCurtainEvacuationBeforeHide()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.getDatabase(this)
        sessionManager = BlockingSessionManager.getInstance(this)
        deviceOwnerManager = DeviceOwnerManager.getInstance(this)
        deviceOwnerActiveCached = deviceOwnerManager.isDeviceOwnerActive()
        refreshSynchronousProtectionState()
        usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        windowManager = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        protectedPowerMenuController = ProtectedPowerMenuController(this)
        prepareInstantBlockCurtain()
        AuthenticatedRemovalWindow.preload(this)
        DeviceAdminActivationWindow.preload(this)

        registerPackageReceiver()
        registerLauncherReceiver()
        registerRefreshReceiver()
        CurtainDestinationReadyCoordinator.register(curtainDestinationReadyListener)
        registerScreenStateReceiver()
        createNotificationChannel()
        startAsForeground()
    }

    private fun registerPackageReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(packageReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(packageReceiver, filter)
        }
    }

    private fun registerLauncherReceiver() {
        val filter = IntentFilter().apply {
            addAction("android.intent.action.ACTION_PREFERRED_ACTIVITY_CHANGED")
            addAction("android.app.role.action.ROLE_HOLDER_CHANGED")
            addAction(Intent.ACTION_LOCALE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(launcherReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(launcherReceiver, filter)
        }
    }

    private fun registerRefreshReceiver() {
        val filter = IntentFilter().apply {
            addAction(ACTION_REFRESH_BLOCKING)
            addAction(ACTION_DEV_RELINQUISH_ACCESSIBILITY)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(refreshReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(refreshReceiver, filter)
        }
    }

    private fun registerScreenStateReceiver() {
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(screenStateReceiver, filter)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            channelId,
            "FocusGuard Protection Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.mantem_o_focusguard_ativo_para_garantir_)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun startAsForeground() {
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(getString(R.string.service_notification_text))
            .setSmallIcon(R.drawable.ic_shield)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                notificationId,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(notificationId, notification)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        accessibilityServiceConnected = true
        // Do this before any node/tree work or asynchronous Room refresh. The
        // persisted snapshot exists specifically to cover the first event after
        // Android binds or recreates the service.
        deviceOwnerActiveCached = deviceOwnerManager.isDeviceOwnerActive()
        refreshSynchronousProtectionState()
        prepareInstantBlockCurtain()
        defaultLauncherPackage = calculateDefaultLauncher()
        refreshLauncherIndex(force = true)
        foregroundPackageName = rootInActiveWindow?.packageName?.toString()
        calculateBrowserPackages()
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
        syncWarmOverlays()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshLauncherIndex(force = true)
    }

    private fun calculateBrowserPackages() {
        browserPackages = try {
            val browserIntent = Intent(
                Intent.ACTION_VIEW,
                android.net.Uri.parse("https://example.com")
            )
            val dynamicBrowsers = packageManager.queryIntentActivities(
                browserIntent,
                PackageManagerCompat.MATCH_ALL
            ).mapNotNull { it.activityInfo?.packageName }.toSet()
            knownBrowserPackages + dynamicBrowsers
        } catch (error: RuntimeException) {
            FocusGuardLogger.logError(
                "A11y",
                "Falha ao identificar navegadores",
                error
            )
            knownBrowserPackages
        }
    }

    private fun calculateDefaultLauncher(): String? {
        return try {
            val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME) }
            packageManager.resolveActivity(
                intent,
                android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
            )?.activityInfo?.packageName
        } catch (error: RuntimeException) {
            FocusGuardLogger.logError("A11y", "Falha ao identificar launcher", error)
            null
        }
    }

    /**
     * Builds the launcher-label index off the accessibility callback. PackageManager
     * is never consulted after the user taps an icon; that path reads one volatile
     * snapshot and the event's direct text only.
     */
    private fun refreshLauncherIndex(force: Boolean = false) {
        val nowElapsed = SystemClock.elapsedRealtime()
        if (!LauncherIndexRefreshPolicy.shouldRequest(
                force = force,
                lastRequestElapsed = lastLauncherIndexRefreshRequestElapsed,
                nowElapsed = nowElapsed
            )
        ) return
        lastLauncherIndexRefreshRequestElapsed = nowElapsed
        launcherIndexRefreshRequested.set(true)
        if (!isRefreshingLauncherIndex.compareAndSet(false, true)) return
        scope.launch {
            try {
                do {
                    launcherIndexRefreshRequested.set(false)
                    val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_LAUNCHER)
                    }
                    val queryResult = runCatching {
                        packageManager.queryIntentActivities(
                            launcherIntent,
                            PackageManagerCompat.MATCH_ALL
                        )
                    }
                    val resolveInfos = queryResult.getOrNull()
                    if (resolveInfos == null) {
                        FocusGuardLogger.logError(
                            "A11y",
                            "Falha ao consultar Activities do launcher; snapshot preservado",
                            queryResult.exceptionOrNull()
                                ?: IllegalStateException("Consulta do launcher sem resultado")
                        )
                        // Permit the next normal refresh pulse to retry instead
                        // of leaving the fast path stale for the full 15 minutes.
                        lastLauncherIndexRefreshRequestElapsed = 0L
                        continue
                    }
                    val entries = resolveInfos.flatMap { resolveInfo ->
                        val activityInfo = resolveInfo.activityInfo
                        if (activityInfo == null) {
                            emptyList()
                        } else {
                            val componentName = android.content.ComponentName(
                                activityInfo.packageName,
                                activityInfo.name
                            ).flattenToShortString()
                            buildList {
                                runCatching {
                                    resolveInfo.loadLabel(packageManager)
                                }.getOrNull()?.let { label ->
                                    add(
                                        ImmediateInterceptionPolicy.LauncherLabelEntry(
                                            label = label,
                                            packageName = activityInfo.packageName,
                                            componentName = componentName
                                        )
                                    )
                                }
                                runCatching {
                                    activityInfo.applicationInfo.loadLabel(packageManager)
                                }.getOrNull()?.let { label ->
                                    add(
                                        ImmediateInterceptionPolicy.LauncherLabelEntry(
                                            label = label,
                                            packageName = activityInfo.packageName,
                                            componentName = componentName
                                        )
                                    )
                                }
                            }
                        }
                    }
                    val rebuilt = ImmediateInterceptionPolicy.buildLauncherLabelIndex(entries)
                    val launcher = calculateDefaultLauncher()
                    if (LauncherIndexRefreshPolicy.shouldPublishCandidate(
                            querySucceeded = true,
                            candidateSize = rebuilt.size,
                            hasSuccessfulSnapshot = hasSuccessfulLauncherIndexSnapshot
                        )
                    ) {
                        withContext(Dispatchers.Main) {
                            launcherLabelIndex = rebuilt
                            hasSuccessfulLauncherIndexSnapshot = true
                            if (launcher != null) defaultLauncherPackage = launcher
                        }
                    }
                } while (launcherIndexRefreshRequested.get())
            } finally {
                isRefreshingLauncherIndex.set(false)
                if (launcherIndexRefreshRequested.get() && serviceJob.isActive) {
                    refreshLauncherIndex(force = true)
                }
            }
        }
    }

    private fun resolveEventPackageName(event: AccessibilityEvent): String {
        val directPackage = event.packageName?.toString().orEmpty()
        if (event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED &&
            directPackage.isNotBlank()
        ) {
            return directPackage
        }

        if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            val eventWindowRoot = windows
                .firstOrNull { window -> window.id == event.windowId }
                ?.root
            val eventWindowPackage = try {
                eventWindowRoot?.packageName?.toString().orEmpty()
            } finally {
                recycleSafely(eventWindowRoot)
            }
            if (eventWindowPackage.isNotBlank()) return eventWindowPackage
        }

        val root = rootInActiveWindow
        return try {
            root?.packageName?.toString().orEmpty().ifBlank { directPackage }
        } finally {
            recycleSafely(root)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val eventDetectedAtNanos = SystemClock.elapsedRealtimeNanos()
        val eventDeliveredAtUptimeMillis = SystemClock.uptimeMillis()
        try {
            // Fast path: settings interception decides on `event.packageName` alone,
            // before resolveEventPackageName() is allowed to touch the node tree.
            //
            // That resolution costs one or two synchronous binder calls into the
            // inspected app (windows walk plus rootInActiveWindow), on every event.
            // Those milliseconds are exactly the window in which the user can reach
            // the switch that disables this service, so nothing that can block runs
            // ahead of the decision to bounce them out.
            val directPackage = event.packageName?.toString().orEmpty()

            // Shield the native System UI power menu before any other handling.
            // A touch-consuming TYPE_ACCESSIBILITY_OVERLAY stays on top while the
            // controller forwards only ACTION_CLICK to native actions; the user
            // never reaches the long-press path that requests Safe Mode.
            if (protectedPowerMenuController?.handleAccessibilityEvent(
                    event = event,
                    protectionActive = isBlockingSessionActive || focusModeSessionActive
                ) == true
            ) {
                return
            }

            if (ImmediateInterceptionPolicy.shouldHandleLauncherClick(
                    isViewClickedEvent =
                        event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED,
                    eventPackageName = directPackage,
                    defaultLauncherPackage = defaultLauncherPackage
                )
            ) {
                val directValues = directEventTextValues(event)
                if (handleBlockedLauncherClick(event, directValues)) return
                if (handleLauncherAppInfoClick(
                        event,
                        directValues,
                        eventDetectedAtNanos,
                        eventDeliveredAtUptimeMillis
                    )
                ) return
            }

            val eligibleForInterception = event.eventType in settingsInterceptionEventTypes
            if (eligibleForInterception &&
                directPackage in interceptionPackages &&
                handleSettingsInterception(
                    event,
                    directPackage,
                    eventDetectedAtNanos,
                    eventDeliveredAtUptimeMillis
                )
            ) {
                return
            }

            val packageName = resolveEventPackageName(event)
            // Second chance: `event.packageName` is occasionally blank, and for
            // TYPE_WINDOWS_CHANGED it can name a different window than the one that
            // actually changed. Only reached when the fast path could not decide.
            if (eligibleForInterception &&
                packageName != directPackage &&
                packageName in interceptionPackages &&
                handleSettingsInterception(
                    event,
                    packageName,
                    eventDetectedAtNanos,
                    eventDeliveredAtUptimeMillis
                )
            ) {
                return
            }

            // Refresh is intentionally below the self-protection fast path. Even
            // though refreshData() is asynchronous, scheduling it and touching its
            // atomics before the critical decision is wasted work on the exact event
            // where every millisecond matters.
            val now = System.currentTimeMillis()
            if (now - lastLoadTime > cacheTimeoutMillis) {
                refreshLauncherIndex()
                refreshData()
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
        syncWarmOverlays()
        lastLoadTime = System.currentTimeMillis()
    }

    private fun relinquishAccessibilityForDevelopment() {
        if (!AuthenticatedRemovalWindow.isActive(this)) return

        runCatching {
            blockedAppsSet = emptySet()
            blockedWebsitesDomainSet = emptySet()
            blockedWebsiteAppDomains = emptyMap()
            limitedWebsiteDomains = emptySet()
            limitedWebsiteAppDomains = emptyMap()
            isBlockingSessionActive = false
            focusModeSessionActive = false
            focusModeFallbackActive = false
            focusModeBlockedAppsSet = emptySet()
            focusModeAllowedAppsSet = emptySet()
            SelfProtectionStateStore.setArmed(applicationContext, false)
            isPomodoroStrictActive = false
            pendingSettingsProtectionUntilElapsed = 0L
            StrictPomodoroLock.clear(applicationContext)
            PomodoroForegroundService.stop(applicationContext)
            foregroundPackageName = null
            stopWebsiteTracking()
            protectedPowerMenuController?.onProtectionStateChanged(false)
            releaseInstantBlockCurtain()
            stopForeground(STOP_FOREGROUND_REMOVE)
        }.onFailure { error ->
            FocusGuardLogger.logError(
                "DevelopmentUninstall",
                "Falha parcial ao limpar o serviço de Acessibilidade",
                error
            )
        }
        disableSelf()
    }

    override fun onInterrupt() {
        foregroundPackageName = null
        stopWebsiteTracking()
        // onInterrupt stops accessibility feedback; it does not prove that an
        // Android-owned power window disappeared. Keep shielding until the
        // controller confirms absence through its normal window recheck.
        protectedPowerMenuController?.onFeedbackInterrupted()
    }

    private fun refreshData() {
        refreshRequested.set(true)
        if (!isRefreshing.compareAndSet(false, true)) return
        scope.launch {
            try {
                do {
                    refreshRequested.set(false)
                    try {
                        val deviceOwnerActiveNow = deviceOwnerManager.isDeviceOwnerActive()
                        deviceOwnerActiveCached = deviceOwnerActiveNow
                        val adultFilterEnabled = authManager.isAdultFilterEnabled()
                        val adultRules = if (adultFilterEnabled) {
                            setOf(com.focusguard.data.PredefinedWebsites.PORNOGRAPHY_RULE)
                        } else {
                            emptySet()
                        }
                        val focusModeSession = FocusModeStore.readSession(applicationContext)
                            ?.takeIf { it.isActive() }
                        val nativeFocusLockdownActive = focusModeSession != null &&
                            FocusModePolicy.usesNativeFocusLockdown(
                                deviceOwnerActive = deviceOwnerActiveNow,
                                systemLockdownSupported =
                                    deviceOwnerManager.isFocusModeSystemLockdownSupported()
                            )
                        val focusFallbackActive =
                            focusModeSession != null && !nativeFocusLockdownActive
                        val focusFallbackApps = if (focusFallbackActive) {
                            focusModeSession?.blockedPackages.orEmpty()
                        } else {
                            emptySet()
                        }
                        val focusAllowedApps = focusModeSession?.allowedPackages.orEmpty()

                        val activeSessions = database.blockSessionDao().getAllActiveSessionsStatic()
                        val enforcingSessions = activeSessions.filter {
                            BlockingSessionManager.participatesInBlocking(it) &&
                                sessionManager.isCurrentlyInBlockingWindow(it)
                        }
                        val enforcingIds = enforcingSessions.map { it.id }

                        val sessionApps = getAppsForSessions(enforcingIds).toSet()
                        val sessionSites = WebsiteBlocker.normalizeRules(
                            getSitesForSessions(enforcingIds)
                        )

                        val activeAppLimits = database.appUsageLimitDao()
                            .getAllActiveLimitsStatic()
                        val limitApps = calculateExceededAppLimits(activeAppLimits)
                        val websiteLimits = database.websiteUsageLimitDao().getAllStatic()
                            .filter { it.isEnabled }
                        val configuredWebsiteDomains = WebsiteBlocker.normalizeRules(
                            websiteLimits.map { it.domain }
                        )
                        val exceededWebsiteDomains = calculateExceededWebsiteLimits(websiteLimits)
                        val blockedWebsiteDomains = WebsiteBlocker.normalizeRules(
                            sessionSites + exceededWebsiteDomains + adultRules
                        )
                        val blockedWebsiteApps = WebsiteBlocker.appPackageDomainsFor(
                            sessionSites + exceededWebsiteDomains
                        ).filterKeys { it !in focusAllowedApps }
                        val limitedWebsiteApps = WebsiteBlocker.appPackageDomainsFor(
                            configuredWebsiteDomains
                        ).filterKeys { it !in focusAllowedApps }
                        val enforcedApps = FocusModePolicy.packagesToEnforce(
                            configuredBlockedPackages = sessionApps + limitApps,
                            focusModeBlockedPackages =
                                focusModeSession?.blockedPackages.orEmpty(),
                            focusModeAllowedPackages = focusAllowedApps
                        )
                        val accessibilityApps = FocusModePolicy.packagesForAccessibility(
                            enforcedPackages = enforcedApps,
                            focusModeBlockedPackages =
                                focusModeSession?.blockedPackages.orEmpty(),
                            nativeFocusLockdownActive = nativeFocusLockdownActive
                        )
                        val enforcementFingerprint = listOf(
                            enforcingIds.sorted().joinToString(","),
                            sessionApps.sorted().joinToString(","),
                            sessionSites.sorted().joinToString(","),
                            limitApps.sorted().joinToString(","),
                            exceededWebsiteDomains.sorted().joinToString(","),
                            adultFilterEnabled.toString(),
                            focusModeSession?.startedAtMillis?.toString().orEmpty()
                        ).joinToString("|")
                        val shouldReconcilePolicies = lastEnforcementFingerprint?.let {
                            it != enforcementFingerprint
                        } == true

                        withContext(Dispatchers.Main) {
                            isPomodoroStrictActive = enforcingSessions.any {
                                it.sessionType == "POMODORO" && it.isBlockingEnabled
                            }
                            focusModeSessionActive = focusModeSession != null
                            focusModeFallbackActive = focusFallbackActive
                            focusModeBlockedAppsSet = focusFallbackApps
                            focusModeAllowedAppsSet = focusAllowedApps
                            blockedAppsSet = accessibilityApps
                            blockedWebsitesDomainSet = blockedWebsiteDomains
                            blockedWebsiteAppDomains = blockedWebsiteApps
                            limitedWebsiteDomains = configuredWebsiteDomains
                            limitedWebsiteAppDomains = limitedWebsiteApps
                            hasActiveAppLimits = activeAppLimits.isNotEmpty()
                            isBlockingSessionActive = isSelfProtectionEngaged(
                                cachedActive = enforcingSessions.isNotEmpty() ||
                                    limitApps.isNotEmpty() ||
                                    exceededWebsiteDomains.isNotEmpty() ||
                                    adultFilterEnabled,
                                persistedActive = SelfProtectionStateStore.isArmed(
                                    applicationContext
                                ),
                                focusModeActive = FocusModeStore.isActive(
                                    applicationContext
                                ),
                                armoredDeviceOwnerActive =
                                    deviceOwnerActiveNow &&
                                        deviceOwnerManager.isArmoredProtectionArmed()
                            )
                            lastEnforcementFingerprint = enforcementFingerprint
                            lastLoadTime = System.currentTimeMillis()
                            syncWarmOverlays()
                        }
                        if (shouldReconcilePolicies) {
                            sessionManager.checkAndEnforce()
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        FocusGuardLogger.logError("A11y", "Falha ao atualizar bloqueios", error)
                    }
                } while (refreshRequested.get())
            } finally {
                isRefreshing.set(false)
                if (refreshRequested.get() && serviceJob.isActive) refreshData()
            }
        }
    }

    private suspend fun calculateExceededAppLimits(): Set<String> {
        return calculateExceededAppLimits(
            database.appUsageLimitDao().getAllActiveLimitsStatic()
        )
    }

    private fun calculateExceededAppLimits(
        limits: List<com.focusguard.database.AppUsageLimit>
    ): Set<String> {
        val manager = usageStatsManager ?: return emptySet()
        if (limits.isEmpty()) return emptySet()

        // Without Usage Access, queryAndAggregateUsageStats returns an empty map and
        // every limit below reads as "0 minutes used" — indistinguishable from a
        // limit that is genuinely satisfied. Enforcement stops with nothing in the
        // logs to say why, so record it explicitly. UsageAccessStateMonitor turns
        // the same condition into a user-visible warning.
        if (UsageAccessPausePolicy.measurementIsUnavailable(
                usageAccessGranted = PermissionUtils.isUsageAccessEnabled(this),
                enabledAppLimitCount = limits.size
            )
        ) {
            FocusGuardLogger.log(
                "A11y",
                "Acesso de uso revogado: ${limits.size} limite(s) de app sem medicao"
            )
            return emptySet()
        }

        val startOfDay = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val usage = manager.queryAndAggregateUsageStats(startOfDay, System.currentTimeMillis())
        val now = System.currentTimeMillis()

        return limits.filter { limit ->
            val usedMinutes = UsageLimitForegroundPolicy.usedMinutes(
                usage[limit.packageName]?.totalTimeInForeground ?: 0L
            )
            usedMinutes >= limit.dailyLimitMinutes &&
                limit.preventOpeningAfterLimit &&
                WebsiteUsageLimitPolicy.isBlockingModeActive(
                    limit.lockMode,
                    limit.lockUntilTimestamp,
                    now
                )
        }.mapTo(mutableSetOf()) { it.packageName }
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
                        exceededPackages = calculateExceededAppLimits(),
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

    private suspend fun calculateExceededWebsiteLimits(
        limits: List<com.focusguard.database.WebsiteUsageLimit>
    ): Set<String> {
        if (limits.isEmpty()) return emptySet()
        val today = dateFormat.get()!!.format(Date())
        val usage = WebsiteUsageLimitPolicy.aggregateUsageByRule(
            usageByIdentifier = database.dailyUsageStatDao()
                .getStatsForDateStatic(today)
                .map { it.identifier to it.timeSpentMs },
            configuredRules = limits.map { it.domain }
        )
        val now = System.currentTimeMillis()

        return limits.filter { limit ->
            val domain = WebsiteBlocker.normalizeRule(limit.domain)
            WebsiteUsageLimitPolicy.shouldBlock(
                usedMillis = usage[domain] ?: 0L,
                dailyLimitMinutes = limit.dailyLimitMinutes,
                lockMode = limit.lockMode,
                lockUntilTimestamp = limit.lockUntilTimestamp,
                nowMillis = now
            )
        }.mapTo(mutableSetOf()) { WebsiteBlocker.normalizeRule(it.domain) }
    }

    private fun handleWindowStateChanged(
        event: AccessibilityEvent,
        packageName: String = event.packageName?.toString().orEmpty()
    ) {
        if (packageName.isBlank()) return
        val className = event.className?.toString().orEmpty()
        if (className.contains("Toast") || className.contains("PopupWindow")) return
        if (packageName == this.packageName) return
        if (FocusModePolicy.shouldRedirectToFocusGuard(
                focusModeFallbackActive = focusModeFallbackActive,
                foregroundPackage = packageName,
                focusGuardPackage = this.packageName,
                launcherPackage = defaultLauncherPackage,
                focusModeBlockedPackages = focusModeBlockedAppsSet
            )
        ) {
            redirectToFocusGuard(packageName, event.eventTime)
            return
        }
        if (packageName in focusModeAllowedAppsSet) return
        if (packageName == defaultLauncherPackage) return

        val blockedWebsiteDomain = blockedWebsiteAppDomains[packageName]
        val limitedWebsiteDomain = limitedWebsiteAppDomains[packageName]
        when {
            blockedWebsiteDomain != null -> blockWebsiteApp(blockedWebsiteDomain, packageName)
            ImmediateInterceptionPolicy.isBlockedTargetWindow(
                packageName,
                blockedAppsSet
            ) -> blockApp(packageName, event.eventTime)
            limitedWebsiteDomain != null -> updateWebsiteTracking(
                urlOrDomain = limitedWebsiteDomain,
                packageName = packageName,
                now = System.currentTimeMillis()
            )
            packageName in browserPackages &&
                (blockedWebsitesDomainSet.isNotEmpty() || limitedWebsiteDomains.isNotEmpty()) ->
                handleBrowserEvent(event)
        }
    }

    /**
     * Best-effort consumer-mode fast path. Accessibility delivers this after the
     * launcher click, so the native Device Owner suspension remains the only path
     * that can guarantee the target Activity never starts. This path avoids all
     * binder/tree work and normally covers the transition before a useful frame.
     */
    private fun handleBlockedLauncherClick(
        event: AccessibilityEvent,
        directValues: List<CharSequence?>
    ): Boolean {
        if (!ImmediateInterceptionPolicy.isLikelyLauncherAppIconClass(
                event.className?.toString().orEmpty()
            )
        ) return false
        val blockedPackage = launcherLabelIndex.matchBlockedPackage(
            values = directValues,
            blockedPackages = blockedAppsSet,
            additionalBlockedPackages = if (focusModeFallbackActive) {
                focusModeBlockedAppsSet
            } else {
                emptySet()
            }
        ) ?: return false

        if (focusModeFallbackActive && blockedPackage in focusModeBlockedAppsSet) {
            redirectToFocusGuard(blockedPackage, event.eventTime)
            return true
        }

        launchBlockNotice(
            blockedPackage = blockedPackage,
            blockedDomain = null,
            eventUptimeMillis = event.eventTime
        )
        return true
    }

    /** Covers the launcher's long-press “App info” shortcut for FocusGuard. */
    private fun handleLauncherAppInfoClick(
        event: AccessibilityEvent,
        directValues: List<CharSequence?>,
        eventDetectedAtNanos: Long,
        eventDeliveredAtUptimeMillis: Long
    ): Boolean {
        val directDecision = ImmediateInterceptionPolicy.classifyLauncherAppInfoClick(
            directValues
        )
        if (directDecision == DirectDecision.IGNORE) return false
        if (AuthenticatedRemovalWindow.isActive(this) || !isSelfProtectionEngagedNow()) {
            return false
        }
        if (deviceOwnerActiveCached &&
            com.focusguard.security.DeviceOwnerMaintenanceGate.isTemporarilyUnlocked(this)) return false

        val decision = if (directDecision == DirectDecision.NEED_TREE) {
            ImmediateInterceptionPolicy.classifyLauncherAppInfoClick(eventTextValues(event))
        } else {
            directDecision
        }
        if (decision != DirectDecision.PROTECT) return false

        pendingSettingsProtectionUntilElapsed =
            SystemClock.elapsedRealtime() + SETTINGS_TRANSITION_GUARD_MILLIS
        val generation = executeProtectionAction(
            eventTimeUptimeMillis = event.eventTime,
            eventDeliveredAtUptimeMillis = eventDeliveredAtUptimeMillis,
            eventDetectedAtNanos = eventDetectedAtNanos,
            holdUntilSafeSurface = true
        )
        launchMasterRemovalGate(MasterRemovalActivity.Target.APP_INFO, generation)
        return true
    }

    private fun directEventTextValues(event: AccessibilityEvent): List<CharSequence?> =
        buildList {
            addAll(event.text.orEmpty())
            add(event.contentDescription)
        }

    private fun isSelfProtectionEngagedNow(): Boolean =
        isBlockingSessionActive ||
            focusModeSessionActive ||
            SelfProtectionStateStore.isArmed(applicationContext) ||
            (deviceOwnerActiveCached && deviceOwnerManager.isArmoredProtectionArmed())

    private fun handleStrictPomodoro(packageName: String, className: String) {
        if (packageName.isBlank() || packageName == this.packageName || packageName in phonePackages) {
            return
        }

        if (packageName == "com.android.systemui") {
            performGlobalAction(GLOBAL_ACTION_HOME)
            launchPomodoroLockScreen()
            return
        }

        if (packageName == defaultLauncherPackage || packageName in settingsPackages) {
            performGlobalAction(GLOBAL_ACTION_BACK)
            launchPomodoroLockScreen()
            return
        }

        FocusGuardLogger.log(
            "A11y",
            "Pomodoro rigoroso bloqueou $packageName ($className)"
        )
        performGlobalAction(GLOBAL_ACTION_HOME)
        blockApp(packageName)
    }

    /**
     * @param packageName o app já resolvido pelo chamador. Reler
     *   `event.packageName` aqui anulava a segunda chance: ela existe justamente
     *   para os eventos em que esse campo vem vazio ou nomeia outra janela, e o
     *   guard abaixo então rejeitava todos eles.
     */
    private fun handleSettingsInterception(
        event: AccessibilityEvent,
        packageName: String,
        eventDetectedAtNanos: Long,
        eventDeliveredAtUptimeMillis: Long
    ): Boolean {
        // Cheap guards stay ahead of the signal extraction below: this runs on every
        // accessibility event, and eventTextValues() plus the classifiers are not free.
        if (packageName !in interceptionPackages) return false
        if (packageName in SettingsInterceptionPolicy.systemUiPackages &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED
        ) return false

        // ACTION_DELETE was opened by FocusGuard itself after the master
        // credential (or the day-15 window) authorized removal. Do not block
        // the Android-owned confirmation screen during that short hand-off.
        if (AuthenticatedRemovalWindow.isActive(this)) return false

        // A proteção contra a própria remoção vale em dois casos. O mais forte é
        // o Device Owner blindado. O outro é o modo consumidor: sem Device Owner,
        // mas com um bloqueio, limite ou o filtro adulto ativo agora — é o estado
        // em que desativar o app derruba justamente o que o usuário pediu para
        // segurar. `isBlockingSessionActive` já é a verdade em tempo real desse
        // estado, atualizada pelo snapshot no mesmo instante em que um bloqueio é
        // armado, então a defesa sobe junto com o bloqueio, sem depender de uma
        // recarga posterior.
        //
        // O cache e os dois snapshots em Device Protected Storage vêm antes das
        // chamadas ao DevicePolicyManager. Assim o primeiro evento após um novo
        // bind falha fechado, sem esperar Room, e o caminho ocioso ainda sai antes
        // das consultas binder mais caras.
        // Kotlin OR short-circuits left-to-right. The common consumer-mode
        // path therefore exits on the volatile snapshot and performs zero SharedPrefs
        // reads and zero DevicePolicyManager binder calls. Persistent/Device Owner
        // state remains as a fail-closed fallback for process recreation.
        if (!isSelfProtectionEngagedNow()) return false

        // Only actual Device Owner devices can have this maintenance gate. Avoid a
        // DevicePolicyManager round-trip on the consumer path.
        if (deviceOwnerActiveCached &&
            com.focusguard.security.DeviceOwnerMaintenanceGate.isTemporarilyUnlocked(this)) return false

        val nowElapsed = SystemClock.elapsedRealtime()
        val isSystemUi = packageName in SettingsInterceptionPolicy.systemUiPackages

        // MasterRemovalActivity deliberately opens Settings at its root for one
        // frame to clear the protected task. The same curtain generation proves
        // this is our internal reset, not a user attempt; do not HOME-bounce it.
        if (!isSystemUi &&
            ProtectedSettingsResetWindow.isActive(
                curtainGeneration = awaitingSafeSurfaceGeneration,
                nowElapsed = nowElapsed
            )
        ) return true

        // Strict Pomodoro keeps ownership of Settings. System UI clicks still need
        // their dedicated disclosure/admin classifier, matching the policy order.
        if (!isSystemUi &&
            isPomodoroStrictActive &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED
        ) {
            performGlobalAction(GLOBAL_ACTION_BACK)
            launchPomodoroLockScreen()
            return true
        }

        // A click already classified as protected arms a short transition guard.
        // Follow-up window/focus/content events need no class, text, source or root
        // inspection at all: cover and evict immediately.
        if (!isSystemUi &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED &&
            nowElapsed <= pendingSettingsProtectionUntilElapsed
        ) {
            executeProtectionAction(
                eventTimeUptimeMillis = event.eventTime,
                eventDeliveredAtUptimeMillis = eventDeliveredAtUptimeMillis,
                eventDetectedAtNanos = eventDetectedAtNanos
            )
            return true
        }

        // The cached inactive state costs no Preferences/Settings read. A DPM
        // binder call happens only while FocusGuard itself opened enrollment.
        val deviceAdminActivationAuthorized =
            DeviceAdminActivationWindow.isPotentiallyAuthorized(this) &&
                DeviceAdminActivationWindow.isAuthorized(
                    context = this,
                    deviceAdminActive = deviceOwnerManager.isDeviceAdminActive()
                )
        val className = event.className?.toString().orEmpty()

        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            val directValues = directEventTextValues(event)
            val direct = if (isSystemUi) {
                ImmediateInterceptionPolicy.classifySystemUiClickWithContext(
                    className = className,
                    directValues = directValues,
                    contextualValues = {
                        eventTextValues(event, forceExpandClickContext = true)
                    }
                )
            } else {
                ImmediateInterceptionPolicy.classifySettingsClick(
                    packageName = packageName,
                    className = className,
                    values = directValues
                )
            }
            val authorizedAdminNeedsFullPolicy =
                ImmediateInterceptionPolicy.requiresFullPolicyForAuthorizedAdmin(
                    deviceAdminActivationAuthorized = deviceAdminActivationAuthorized,
                    className = className,
                    directSurface = direct.surface
                )
            if (direct.decision == DirectDecision.PROTECT &&
                !authorizedAdminNeedsFullPolicy
            ) {
                val target = direct.surface?.toMasterRemovalTarget() ?: return false
                pendingSettingsProtectionUntilElapsed =
                    nowElapsed + SETTINGS_TRANSITION_GUARD_MILLIS
                val generation = executeProtectionAction(
                    eventTimeUptimeMillis = event.eventTime,
                    eventDeliveredAtUptimeMillis = eventDeliveredAtUptimeMillis,
                    eventDetectedAtNanos = eventDetectedAtNanos,
                    holdUntilSafeSurface = true
                )
                launchMasterRemovalGate(target, generation)
                return true
            }
            if (direct.decision == DirectDecision.IGNORE && !isPomodoroStrictActive) {
                return false
            }
            // System UI is intentionally limited to two exact deep links. An
            // expanded notification subtree that still cannot prove either one
            // must not inherit the broader Settings policy below.
            if (isSystemUi && direct.decision == DirectDecision.NEED_TREE) {
                return false
            }
        }

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
        val eventValues = eventTextValues(event)
        val accessibilityTextSignals = AccessibilitySettingsPolicy.classifyText(eventValues)
        val accessibilityContextConfirmed = confirmAccessibilityContextForInstalledEntry(
            directAccessibility = accessibilityTextSignals.accessibility,
            installedAccessibilityApps = accessibilityTextSignals.installedAccessibilityApps,
            rootMentionsAccessibility = ::rootMentionsAccessibility
        )
        val managedTextSignals = ManagedSelfProtectionPolicy.classifyText(eventValues)

        val signals = SettingsInterceptionPolicy.EventSignals(
            packageName = packageName,
            isViewClickedEvent = event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED,
            isWindowTransitionEvent =
                event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                    event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            guardArmed = nowElapsed <= pendingSettingsProtectionUntilElapsed,
            classTargetsAccessibilityServiceToggle =
                classTargetsAccessibilityServiceToggle,
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
        } else {
            null
        }

        val decision = SettingsInterceptionPolicy.decide(
            signals = signals,
            // Já confirmado pelas guardas acima; a política revalida por conta
            // própria porque é testada isoladamente.
            selfProtectionEngaged = true,
            strictPomodoroActive = isPomodoroStrictActive,
            deviceAdminActivationAuthorized = deviceAdminActivationAuthorized,
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
                performGlobalAction(GLOBAL_ACTION_BACK)
                launchPomodoroLockScreen()
                true
            }

            SettingsInterceptionPolicy.Decision.PROTECT -> {
                val generation = executeProtectionAction(
                    eventTimeUptimeMillis = event.eventTime,
                    eventDeliveredAtUptimeMillis = eventDeliveredAtUptimeMillis,
                    eventDetectedAtNanos = eventDetectedAtNanos,
                    holdUntilSafeSurface = masterRemovalTarget != null
                )
                masterRemovalTarget?.let { launchMasterRemovalGate(it, generation) }
                true
            }

            SettingsInterceptionPolicy.Decision.PROTECT_AND_ARM_GUARD -> {
                pendingSettingsProtectionUntilElapsed =
                    nowElapsed + SETTINGS_TRANSITION_GUARD_MILLIS
                val generation = executeProtectionAction(
                    eventTimeUptimeMillis = event.eventTime,
                    eventDeliveredAtUptimeMillis = eventDeliveredAtUptimeMillis,
                    eventDetectedAtNanos = eventDetectedAtNanos,
                    holdUntilSafeSurface = masterRemovalTarget != null
                )
                masterRemovalTarget?.let { launchMasterRemovalGate(it, generation) }
                true
            }
        }
    }

    private fun SettingsSurface.toMasterRemovalTarget(): MasterRemovalActivity.Target = when (this) {
        SettingsSurface.APP_INFO -> MasterRemovalActivity.Target.APP_INFO
        SettingsSurface.DEVICE_ADMIN -> MasterRemovalActivity.Target.DEVICE_ADMIN
        SettingsSurface.ACCESSIBILITY -> MasterRemovalActivity.Target.ACCESSIBILITY
        SettingsSurface.UNINSTALL -> MasterRemovalActivity.Target.UNINSTALL
    }

    private fun launchMasterRemovalGate(
        target: MasterRemovalActivity.Target,
        curtainGeneration: Long
    ) {
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

    private fun eventTextValues(
        event: AccessibilityEvent,
        forceExpandClickContext: Boolean = false
    ): List<CharSequence?> {
        return buildList {
            addAll(event.text.orEmpty())
            add(event.contentDescription)
            event.source?.let { source ->
                add(source.text)
                add(source.contentDescription)
                add(source.viewIdResourceName)
                if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED &&
                    (forceExpandClickContext || shouldExpandClickContext(this))
                ) {
                    // Most buttons already expose enough text directly. Only pay
                    // for subtree/root queries when the clicked node itself has no
                    // locator that can identify FocusGuard, Device Admin or the
                    // accessibility disclosure.
                    clickInterceptionSearchTerms.forEach { term ->
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
                    // A switch is commonly a sibling of the FocusGuard label,
                    // not its parent. Match only markers sharing the clicked
                    // control's horizontal row, instead of scanning/classifying
                    // the whole list and accidentally protecting other services.
                    addAll(sameRowClickTextValues(source))
                }
                recycleSafely(source)
            }
        }
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
    }

    private fun refreshFocusModeFallbackState() {
        val session = FocusModeStore.readSession(applicationContext)
            ?.takeIf { it.isActive() }
        focusModeSessionActive = session != null
        val nativeLockdownActive = session != null &&
            FocusModePolicy.usesNativeFocusLockdown(
                deviceOwnerActive = deviceOwnerActiveCached,
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

    private fun redirectToFocusGuard(
        blockedPackage: String,
        eventUptimeMillis: Long = SystemClock.uptimeMillis()
    ) {
        val generation = showInstantBlockCurtain(mode = CurtainMode.BLOCK_NOTICE)
        awaitingSafeSurfaceGeneration = generation
        evictBlockedAppFromForeground()
        FocusGuardLogger.log(
            "FocusMode",
            "Modo consumidor redirecionou $blockedPackage para o FocusGuard"
        )
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION
                    )
                    putExtra(EXTRA_CURTAIN_GENERATION, generation)
                    putExtra(EXTRA_BLOCK_EVENT_UPTIME_MILLIS, eventUptimeMillis)
                }
            )
        }.onFailure { error ->
            FocusGuardLogger.logError(
                "FocusMode",
                "Falha ao retornar ao FocusGuard",
                error
            )
            beginCurtainEvacuationBeforeHide(generation)
        }
    }

    private fun shouldExpandClickContext(values: Iterable<CharSequence?>): Boolean {
        // “admin” is deliberately only a locator: by itself it is too weak to prove
        // that the clicked node already contains the whole Device Admin context.
        if (ManagedSelfProtectionPolicy.textTargetsDeviceAdmin(values)) return false
        return values.none { value ->
            val text = value?.toString().orEmpty()
            text.isNotBlank() && directClickContextSufficientTerms.any { term ->
                text.contains(term, ignoreCase = true)
            }
        }
    }

    private fun sameRowClickTextValues(source: AccessibilityNodeInfo): List<CharSequence?> {
        val sourceBounds = Rect().also(source::getBoundsInScreen)
        if (sourceBounds.isEmpty || source.isScrollable) return emptyList()

        val root = rootInActiveWindow ?: return emptyList()
        return try {
            val rootBounds = Rect().also(root::getBoundsInScreen)
            if (!shouldSearchSameRowMarkers(sourceBounds, rootBounds)) return emptyList()

            buildList {
                clickInterceptionSearchTerms.forEach { term ->
                    val matchingNodes = runCatching {
                        root.findAccessibilityNodeInfosByText(term)
                    }.getOrDefault(emptyList())
                    matchingNodes.forEach { node ->
                        val nodeBounds = Rect().also(node::getBoundsInScreen)
                        if (boundsShareHorizontalRow(sourceBounds, nodeBounds)) {
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

    private fun rootMentionsAccessibility(): Boolean {
        return rootContainsAny(
            searchTerms = AccessibilitySettingsPolicy.searchTerms,
            classifier = AccessibilitySettingsPolicy::textTargetsAccessibility,
            screenLabel = "Acessibilidade"
        )
    }

    private fun rootMentionsDeviceAdmin(): Boolean {
        return rootContainsAny(
            searchTerms = ManagedSelfProtectionPolicy.deviceAdminSearchTerms,
            classifier = ManagedSelfProtectionPolicy::textTargetsDeviceAdmin,
            screenLabel = "Administrador do dispositivo"
        )
    }

    private fun rootMentionsFocusGuard(): Boolean {
        return rootContainsAny(
            searchTerms = ManagedSelfProtectionPolicy.focusGuardSearchTerms,
            classifier = ManagedSelfProtectionPolicy::textTargetsFocusGuard,
            screenLabel = "controles do FocusGuard"
        )
    }

    private fun rootMentionsDestructiveControl(): Boolean {
        return rootContainsAny(
            searchTerms = ManagedSelfProtectionPolicy.destructiveControlSearchTerms,
            classifier = ManagedSelfProtectionPolicy::textTargetsDestructiveControl,
            screenLabel = "ação destrutiva"
        )
    }

    private fun rootMentionsEssentialSpecialAccess(): Boolean {
        return rootContainsAny(
            searchTerms = ManagedSelfProtectionPolicy.essentialSpecialAccessSearchTerms,
            classifier = ManagedSelfProtectionPolicy::textTargetsEssentialSpecialAccess,
            screenLabel = "acesso especial essencial"
        )
    }

    private fun rootContainsAny(
        searchTerms: Iterable<String>,
        classifier: (Iterable<CharSequence?>) -> Boolean,
        screenLabel: String
    ): Boolean {
        val root = rootInActiveWindow ?: return false
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

    private fun executeProtectionAction(
        eventTimeUptimeMillis: Long,
        eventDeliveredAtUptimeMillis: Long,
        eventDetectedAtNanos: Long,
        holdUntilSafeSurface: Boolean = false
    ): Long {
        // Segurança nunca sofre debounce: cada tentativa protegida expulsa Settings
        // imediatamente. A cortina, porém, é desenhada ANTES de pedir HOME para
        // cobrir também os frames da animação/transição do sistema.
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
        val generation = if (!holdUntilSafeSurface && alreadyAwaitingSafeSurface) {
            renewInstantCurtainFailsafe()
            awaitingSafeSurfaceGeneration
        } else {
            showInstantBlockCurtain(
                mode = CurtainMode.SELF_PROTECTION,
                messageRes = R.string.accessibility_protection_blocked_notice
            )
        }
        if (holdUntilSafeSurface) awaitingSafeSurfaceGeneration = generation
        val curtainReadyAtNanos = SystemClock.elapsedRealtimeNanos()
        mainHandler.removeCallbacks(protectionCurtainDismiss)
        if (!holdUntilSafeSurface && !alreadyAwaitingSafeSurface) {
            mainHandler.postDelayed(
                protectionCurtainDismiss,
                SELF_PROTECTION_NOTICE_DURATION_MILLIS
            )
        }

        if (shouldEvictForProtectionAttempt(alreadyAwaitingSafeSurface)) {
            evictBlockedAppFromForeground()
        }
        val homeRequestedAtNanos = SystemClock.elapsedRealtimeNanos()

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
        } else {
            0L
        }
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

    private fun handleBrowserEvent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        if (packageName !in browserPackages) return

        val pornographyCategoryActive = blockedWebsitesDomainSet.any(
            WebsiteBlocker::isPornographyRule
        )
        val fastAddressText = WebsiteBlocker.extractAddressBarTextFromEvent(
            event,
            packageName
        )
        val fastUrl = fastAddressText?.let(WebsiteBlocker::extractUrlCandidate)
            ?: WebsiteBlocker.extractUrlFromEvent(event, packageName)
        val root = if (fastUrl == null) rootInActiveWindow ?: event.source else null
        val url = fastUrl ?: WebsiteBlocker.extractUrlFromRoot(root, packageName)
        val addressText = fastAddressText
            ?: WebsiteBlocker.extractAddressBarTextFromRoot(root, packageName)
        val now = System.currentTimeMillis()

        if (pornographyCategoryActive) {
            val addressBarHasBlockedSearch = addressText?.let(
                WebsiteBlocker::isPornographySearchInput
            ) == true
            val googlePageFieldHasBlockedSearch = fastAddressText == null &&
                url?.let(WebsiteBlocker::isGoogleUrl) == true &&
                WebsiteBlocker.extractEditableTextFromEvent(event)?.let(
                    WebsiteBlocker::containsPornographySearchTerm
                ) == true
            if (addressBarHasBlockedSearch || googlePageFieldHasBlockedSearch) {
                blockWebsite(
                    PredefinedWebsites.PORNOGRAPHY_RULE,
                    packageName
                )
                recycleSafely(root)
                return
            }
        }

        if (!url.isNullOrBlank()) {
            val domain = WebsiteBlocker.extractDomain(url)
            updateWebsiteTracking(url, packageName, now)
            val matchingRule = WebsiteBlocker.findMatchingRule(
                url,
                blockedWebsitesDomainSet
            )
            if (matchingRule != null) {
                val blockTarget = if (WebsiteBlocker.isPornographyRule(matchingRule)) {
                    matchingRule
                } else {
                    domain
                }
                blockWebsite(blockTarget, packageName)
                recycleSafely(root)
                return
            }
        } else if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // Uma nova aba/tela interna sem URL não deve continuar somando o
            // tempo do site visitado anteriormente.
            stopWebsiteTracking(now)
        }
        recycleSafely(root)
    }

    private fun updateWebsiteTracking(urlOrDomain: String, packageName: String, now: Long) {
        val matchingRules = WebsiteBlocker.findMatchingRules(
            urlOrDomain,
            limitedWebsiteDomains
        )
        if (matchingRules.isEmpty()) {
            stopWebsiteTracking(now)
            return
        }
        val pornographyGoogleSurface =
            WebsiteBlocker.isPornographyGoogleSearchUrl(urlOrDomain) ||
                WebsiteBlocker.isGoogleImagesUrl(urlOrDomain)
        val usageDomain = if (
            PredefinedWebsites.PORNOGRAPHY_RULE in matchingRules &&
            pornographyGoogleSurface
        ) {
            PredefinedWebsites.PORNOGRAPHY_RULE
        } else {
            WebsiteBlocker.extractDomain(urlOrDomain)
                .ifBlank { WebsiteBlocker.normalizeRule(urlOrDomain) }
        }

        var usageToPersist: WebsiteUsageSlice? = null
        synchronized(websiteTrackingLock) {
            val previousDomain = trackedDomain
            val previousPackage = trackedPackageName
            if (previousDomain == usageDomain && previousPackage == packageName) {
                val delta = (now - trackedSinceMillis).coerceIn(0L, maxUsageDeltaMillis)
                if (delta >= 1_000L) {
                    usageToPersist = WebsiteUsageSlice(usageDomain, delta, packageName)
                    trackedSinceMillis = now
                }
            } else {
                if (previousDomain != null && previousPackage != null) {
                    val delta = (now - trackedSinceMillis).coerceIn(0L, maxUsageDeltaMillis)
                    if (delta >= 1_000L) {
                        usageToPersist = WebsiteUsageSlice(
                            previousDomain,
                            delta,
                            previousPackage
                        )
                    }
                }
                trackedDomain = usageDomain
                trackedPackageName = packageName
                trackedSinceMillis = now
            }
        }
        usageToPersist?.let(::persistWebsiteUsage)
        startWebsiteTrackingPulse()
    }

    private fun startWebsiteTrackingPulse() {
        if (websiteTrackingJob?.isActive == true) return
        websiteTrackingJob = scope.launch {
            while (isActive) {
                delay(websitePulseMillis)
                var usageToPersist: WebsiteUsageSlice? = null
                var shouldStopTracking = false
                synchronized(websiteTrackingLock) {
                    val domain = trackedDomain
                    val packageName = trackedPackageName
                    if (domain == null || packageName == null) {
                        return@launch
                    }
                    if (
                        !UsageLimitForegroundPolicy.shouldCountWebsiteUsage(
                            trackedPackageName = packageName,
                            foregroundPackageName = foregroundPackageName,
                            isDeviceInteractive = powerManager?.isInteractive == true
                        )
                    ) {
                        trackedDomain = null
                        trackedPackageName = null
                        trackedSinceMillis = 0L
                        shouldStopTracking = true
                        return@synchronized
                    }
                    val now = System.currentTimeMillis()
                    val delta = (now - trackedSinceMillis).coerceIn(0L, maxUsageDeltaMillis)
                    if (delta >= 1_000L) {
                        trackedSinceMillis = now
                        usageToPersist = WebsiteUsageSlice(domain, delta, packageName)
                    }
                }
                if (shouldStopTracking) return@launch
                usageToPersist?.let { persistWebsiteUsageNow(it) }
            }
        }
    }

    private fun stopWebsiteTracking(now: Long = System.currentTimeMillis()) {
        var usageToPersist: WebsiteUsageSlice? = null
        synchronized(websiteTrackingLock) {
            val domain = trackedDomain
            val packageName = trackedPackageName
            if (domain != null && packageName != null) {
                val delta = (now - trackedSinceMillis).coerceIn(0L, maxUsageDeltaMillis)
                if (delta >= 1_000L) {
                    usageToPersist = WebsiteUsageSlice(domain, delta, packageName)
                }
            }
            trackedDomain = null
            trackedPackageName = null
            trackedSinceMillis = 0L
        }
        websiteTrackingJob?.cancel()
        websiteTrackingJob = null
        usageToPersist?.let(::persistWebsiteUsage)
    }

    private fun persistWebsiteUsage(usage: WebsiteUsageSlice) {
        scope.launch {
            persistWebsiteUsageNow(usage)
        }
    }

    private suspend fun persistWebsiteUsageNow(usage: WebsiteUsageSlice) {
        try {
            val today = dateFormat.get()!!.format(Date())
            database.dailyUsageStatDao().addUsage(
                usage.domain,
                today,
                usage.deltaMillis
            )
            val limits = database.websiteUsageLimitDao().getAllStatic()
                .filter { it.isEnabled }
            val matchingRules = WebsiteBlocker.findMatchingRules(
                usage.domain,
                WebsiteBlocker.normalizeRules(limits.map { it.domain })
            )
            if (matchingRules.isEmpty()) return

            val usageByRule = WebsiteUsageLimitPolicy.aggregateUsageByRule(
                usageByIdentifier = database.dailyUsageStatDao()
                    .getStatsForDateStatic(today)
                    .map { it.identifier to it.timeSpentMs },
                configuredRules = limits.map { it.domain }
            )
            val now = System.currentTimeMillis()
            val exceededRules = limits.mapNotNullTo(linkedSetOf()) { limit ->
                val rule = WebsiteBlocker.normalizeRule(limit.domain)
                rule.takeIf {
                    rule in matchingRules && WebsiteUsageLimitPolicy.shouldBlock(
                        usedMillis = usageByRule[rule] ?: 0L,
                        dailyLimitMinutes = limit.dailyLimitMinutes,
                        lockMode = limit.lockMode,
                        lockUntilTimestamp = limit.lockUntilTimestamp,
                        nowMillis = now
                    )
                }
            }
            if (exceededRules.isNotEmpty()) {
                enforceExceededWebsiteImmediately(usage, exceededRules)
                sessionManager.checkAndEnforce()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            FocusGuardLogger.logError(
                "A11y",
                "Falha ao registrar uso de ${usage.domain}",
                error
            )
        }
    }

    private fun enforceExceededWebsiteImmediately(
        usage: WebsiteUsageSlice,
        exceededRules: Set<String>
    ) {
        scope.launch(Dispatchers.Main) {
            val stillActive = synchronized(websiteTrackingLock) {
                trackedDomain == usage.domain && trackedPackageName == usage.packageName
            }
            if (!stillActive) return@launch

            blockedWebsitesDomainSet = blockedWebsitesDomainSet + exceededRules
            if (usage.packageName in browserPackages) {
                blockWebsite(usage.domain, usage.packageName)
            } else {
                val displayRule = exceededRules.firstOrNull() ?: usage.domain
                blockedWebsiteAppDomains = blockedWebsiteAppDomains +
                    (usage.packageName to displayRule)
                blockWebsiteApp(displayRule, usage.packageName)
            }
        }
    }

    private fun blockApp(
        packageName: String,
        eventUptimeMillis: Long = SystemClock.uptimeMillis()
    ) {
        launchBlockNotice(
            blockedPackage = packageName,
            blockedDomain = null,
            eventUptimeMillis = eventUptimeMillis
        )
    }

    private fun blockWebsite(domain: String, browserPackageName: String) {
        if (!beginWebsiteBlock(domain, browserPackageName)) return
        val noticeLaunched = launchBlockNotice(
            blockedPackage = null,
            blockedDomain = WebsiteBlocker.displayRule(domain),
            redirectBrowserPackage = browserPackageName
        )
        if (!noticeLaunched && !redirectBrowserToSafePage(browserPackageName)) {
            performGlobalAction(GLOBAL_ACTION_BACK)
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    private fun blockWebsiteApp(domain: String, packageName: String) {
        if (!beginWebsiteBlock(domain, packageName)) return
        launchBlockNotice(
            blockedPackage = null,
            blockedDomain = WebsiteBlocker.displayRule(domain)
        )
    }

    private fun beginWebsiteBlock(domain: String, packageName: String): Boolean {
        val now = System.currentTimeMillis()
        val blockKey = "$packageName|$domain"
        if (blockKey == lastWebsiteBlockKey &&
            now - lastWebsiteBlockTime < websiteBlockCooldownMillis
        ) return false

        lastWebsiteBlockKey = blockKey
        lastWebsiteBlockTime = now
        stopWebsiteTracking(now)
        return true
    }

    private fun redirectBrowserToSafePage(browserPackageName: String): Boolean {
        return runCatching {
            startActivity(createSafeRedirectIntent(browserPackageName))
            true
        }.getOrElse { error ->
            FocusGuardLogger.logError(
                "A11y",
                "Falha ao redirecionar navegador bloqueado",
                error
            )
            false
        }
    }

    private fun evictBlockedAppFromForeground(forceLauncherFallback: Boolean = false) {
        val globalHomeAccepted = performGlobalAction(GLOBAL_ACTION_HOME)
        if (!shouldLaunchBlockedAppEvictionFallback(
                globalHomeAccepted = globalHomeAccepted,
                forceLauncherFallback = forceLauncherFallback
            )
        ) return

        runCatching {
            startActivity(createBlockedAppEvictionIntent())
        }.onFailure { error ->
            FocusGuardLogger.logError(
                "A11y",
                "Falha ao tirar app bloqueado do primeiro plano",
                error
            )
        }
    }

    private fun launchBlockNotice(
        blockedPackage: String?,
        blockedDomain: String?,
        redirectBrowserPackage: String? = null,
        eventUptimeMillis: Long = SystemClock.uptimeMillis()
    ): Boolean {
        // Every attempt renews the touch-blocking curtain and eviction. Activity
        // flags coalesce the already-drawn notice; no cooldown is allowed to leave
        // a newly foregrounded blocked app uncovered.
        val generation = showInstantBlockCurtain(mode = CurtainMode.BLOCK_NOTICE)
        awaitingSafeSurfaceGeneration = generation
        if (shouldEvictBlockedAppBeforeNotice(blockedPackage)) {
            evictBlockedAppFromForeground()
        }
        return try {
            startActivity(
                createBlockNoticeIntent(
                    context = this,
                    strictBlock = isPomodoroStrictActive,
                    blockedPackage = blockedPackage,
                    blockedDomain = blockedDomain,
                    redirectBrowserPackage = redirectBrowserPackage,
                    curtainGeneration = generation,
                    eventUptimeMillis = eventUptimeMillis
                )
            )
            true
        } catch (error: RuntimeException) {
            FocusGuardLogger.logError("A11y", "Falha ao abrir tela de bloqueio", error)
            beginCurtainEvacuationBeforeHide(generation)
            false
        }
    }

    private fun prepareInstantBlockCurtain() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(::prepareInstantBlockCurtain)
            return
        }
        if (instantBlockCurtain != null) return

        val density = resources.displayMetrics.density
        val iconSize = (72 * density).toInt()
        val spacing = (18 * density).toInt()
        val curtain = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(16, 17, 23))
            isClickable = true
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            contentDescription = getString(R.string.block_notice_instant_content_description)

            addView(
                ImageView(this@BlockingAccessibilityService).apply {
                    setImageResource(R.drawable.ic_shield)
                    setColorFilter(Color.rgb(38, 198, 218))
                },
                LinearLayout.LayoutParams(iconSize, iconSize)
            )
            addView(
                TextView(this@BlockingAccessibilityService).apply {
                    text = getString(R.string.block_notice_instant_title)
                    setTextColor(Color.WHITE)
                    textSize = 20f
                    gravity = Gravity.CENTER
                },
                LinearLayout.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = spacing
                }
            )
            addView(
                TextView(this@BlockingAccessibilityService).apply {
                    setTextColor(Color.LTGRAY)
                    textSize = 14f
                    gravity = Gravity.CENTER
                    visibility = View.GONE
                    instantBlockCurtainMessage = this
                },
                LinearLayout.LayoutParams(
                    (280 * density).toInt(),
                    WindowManager.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (10 * density).toInt()
                }
            )
        }
        instantBlockCurtain = curtain
        instantBlockCurtainLayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.OPAQUE
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "FocusGuardInstantBlock"
            alpha = 0f
        }
    }

    private fun armInstantBlockCurtain() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(::armInstantBlockCurtain)
            return
        }
        prepareInstantBlockCurtain()
        if (instantBlockCurtainAttached) return
        val curtain = instantBlockCurtain ?: return
        val params = instantBlockCurtainLayoutParams ?: return
        params.alpha = 0f
        params.flags = hiddenOverlayFlags(params.flags)
        runCatching {
            (windowManager ?: return).addView(curtain, params)
            instantBlockCurtainAttached = true
            instantBlockCurtainVisible = false
        }.onFailure { error ->
            FocusGuardLogger.logError(
                "A11y",
                "Falha ao pré-anexar cortina instantânea",
                error
            )
        }
    }

    private fun showInstantBlockCurtain(
        mode: CurtainMode,
        messageRes: Int? = null
    ): Long {
        val generation = instantBlockCurtainGenerationCounter.incrementAndGet()
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { showInstantBlockCurtain(mode, messageRes, generation) }
            return generation
        }
        showInstantBlockCurtain(mode, messageRes, generation)
        return generation
    }

    private fun showInstantBlockCurtain(
        mode: CurtainMode,
        messageRes: Int?,
        generation: Long
    ) {
        mainHandler.removeCallbacks(instantCurtainFailsafeRelease)
        mainHandler.removeCallbacks(readyWindowValidation)
        failsafeEvacuationGeneration = 0L
        pendingReadyWindowValidationGeneration = 0L
        armInstantBlockCurtain()
        val params = instantBlockCurtainLayoutParams ?: return
        if (!instantBlockCurtainAttached) return

        instantBlockCurtainMode = mode
        instantBlockCurtainGeneration = generation
        instantBlockCurtainMessage?.apply {
            if (messageRes == null) {
                text = ""
                visibility = View.GONE
            } else {
                setText(messageRes)
                visibility = View.VISIBLE
            }
        }

        params.alpha = 1f
        params.flags = visibleOverlayFlags(params.flags)
        val curtain = instantBlockCurtain ?: return
        val manager = windowManager ?: return
        runCatching {
            manager.updateViewLayout(curtain, params)
            instantBlockCurtainVisible = true
        }.onFailure { error ->
            FocusGuardLogger.logError(
                "A11y",
                "Falha ao exibir cortina instantânea",
                error
            )
        }

        renewInstantCurtainFailsafe()
    }

    private fun renewInstantCurtainFailsafe() {
        mainHandler.removeCallbacks(instantCurtainFailsafe)
        mainHandler.postDelayed(instantCurtainFailsafe, INSTANT_CURTAIN_FAILSAFE_MILLIS)
    }

    private fun handleInstantCurtainFailsafe() {
        when (instantCurtainFailsafeDecision(
            curtainVisible = instantBlockCurtainVisible,
            awaitingSafeSurfaceGeneration = awaitingSafeSurfaceGeneration,
            unsafeWindowVisible = hasUnsafeVisibleWindow()
        )) {
            InstantCurtainFailsafeDecision.NO_ACTION -> Unit
            InstantCurtainFailsafeDecision.HIDE -> dismissInstantBlockCurtain()
            InstantCurtainFailsafeDecision.EVACUATE_THEN_HIDE ->
                beginCurtainEvacuationBeforeHide()
        }
    }

    private fun beginCurtainEvacuationBeforeHide(
        expectedGeneration: Long = instantBlockCurtainGeneration
    ) {
        // A missing Activity acknowledgement (including SCREEN_OFF between the
        // click and ACK) must never reveal the app or protected Settings surface
        // later. Move to HOME first, keep consuming touch across the next frames,
        // and only then release this exact generation.
        val generation = instantBlockCurtainGeneration
        if (curtainLaunchFailureDecision(
                currentGeneration = generation,
                failedGeneration = expectedGeneration
            ) == CurtainLaunchFailureDecision.NO_ACTION
        ) return
        failsafeEvacuationGeneration = generation
        evictBlockedAppFromForeground(forceLauncherFallback = true)
        mainHandler.removeCallbacks(instantCurtainFailsafe)
        mainHandler.removeCallbacks(instantCurtainFailsafeRelease)
        mainHandler.postDelayed(
            instantCurtainFailsafeRelease,
            FAILSAFE_EVACUATION_HOLD_MILLIS
        )
    }

    private fun validateReadyDestinationWindows() {
        val generation = pendingReadyWindowValidationGeneration
        if (!shouldDismissCurtain(instantBlockCurtainGeneration, generation)) {
            pendingReadyWindowValidationGeneration = 0L
            return
        }
        when (CurtainSafeWindowPolicy.decide(
            settleElapsed = true,
            unsafeWindowVisible = hasUnsafeVisibleWindow()
        )) {
            CurtainSafeWindowPolicy.Decision.WAIT_FOR_SETTLE -> {
                mainHandler.postDelayed(readyWindowValidation, SAFE_WINDOW_SETTLE_MILLIS)
            }
            CurtainSafeWindowPolicy.Decision.KEEP_AND_EVACUATE -> {
                evictBlockedAppFromForeground()
                mainHandler.postDelayed(
                    readyWindowValidation,
                    UNSAFE_WINDOW_RECHECK_MILLIS
                )
            }
            CurtainSafeWindowPolicy.Decision.DISMISS -> {
                pendingReadyWindowValidationGeneration = 0L
                pendingSettingsProtectionUntilElapsed = 0L
                ProtectedSettingsResetWindow.close(generation)
                dismissInstantBlockCurtain(generation)
            }
        }
    }

    private fun completeCurtainFailsafeAfterEvacuation(generation: Long) {
        if (generation <= 0L ||
            generation != instantBlockCurtainGeneration
        ) return
        if (hasUnsafeVisibleWindow()) {
            evictBlockedAppFromForeground()
            mainHandler.postDelayed(
                instantCurtainFailsafeRelease,
                FAILSAFE_EVACUATION_HOLD_MILLIS
            )
            return
        }
        ProtectedSettingsResetWindow.close(generation)
        pendingSettingsProtectionUntilElapsed = 0L
        dismissInstantBlockCurtain(generation)
    }

    private fun handleTimedProtectionCurtainDismiss() {
        if (instantBlockCurtainMode != CurtainMode.SELF_PROTECTION) return
        if (hasUnsafeVisibleWindow()) {
            evictBlockedAppFromForeground()
            mainHandler.postDelayed(
                protectionCurtainDismiss,
                UNSAFE_WINDOW_RECHECK_MILLIS
            )
            return
        }
        dismissInstantBlockCurtain()
    }

    private fun hasUnsafeVisibleWindow(): Boolean {
        val blockedTargets = blockedAppsSet + focusModeBlockedAppsSet
        val protectSettings = instantBlockCurtainMode == CurtainMode.SELF_PROTECTION
        val protectedSettingsPackages = SettingsInterceptionPolicy.settingsPackages +
            SettingsInterceptionPolicy.packageInstallerPackages
        windows.forEach { window ->
            val root = runCatching { window.root }.getOrNull() ?: return@forEach
            try {
                val visiblePackage = root.packageName?.toString().orEmpty()
                if (CurtainSafeWindowPolicy.isUnsafePackage(
                        visiblePackage = visiblePackage,
                        focusGuardPackage = packageName,
                        blockedPackages = blockedTargets,
                        protectSettings = protectSettings,
                        protectedSettingsPackages = protectedSettingsPackages
                    )
                ) return true
            } finally {
                recycleSafely(root)
            }
        }
        return false
    }

    private fun dismissInstantBlockCurtain(expectedGeneration: Long? = null) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { dismissInstantBlockCurtain(expectedGeneration) }
            return
        }
        if (expectedGeneration != null &&
            expectedGeneration != instantBlockCurtainGeneration
        ) return

        mainHandler.removeCallbacks(instantCurtainFailsafe)
        mainHandler.removeCallbacks(instantCurtainFailsafeRelease)
        mainHandler.removeCallbacks(readyWindowValidation)
        mainHandler.removeCallbacks(protectionCurtainDismiss)
        failsafeEvacuationGeneration = 0L
        pendingReadyWindowValidationGeneration = 0L
        awaitingSafeSurfaceGeneration = 0L
        instantBlockCurtainMode = null
        if (!instantBlockCurtainAttached || !instantBlockCurtainVisible) return
        val params = instantBlockCurtainLayoutParams ?: return
        params.alpha = 0f
        params.flags = hiddenOverlayFlags(params.flags)
        val curtain = instantBlockCurtain ?: return
        val manager = windowManager ?: return
        runCatching { manager.updateViewLayout(curtain, params) }
            .onFailure { error ->
                FocusGuardLogger.logError(
                    "A11y",
                    "Falha ao ocultar cortina instantânea",
                    error
                )
                releaseInstantBlockCurtain()
            }
        instantBlockCurtainVisible = false
    }

    private fun releaseInstantBlockCurtain() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(::releaseInstantBlockCurtain)
            return
        }
        mainHandler.removeCallbacks(instantCurtainFailsafe)
        mainHandler.removeCallbacks(instantCurtainFailsafeRelease)
        mainHandler.removeCallbacks(readyWindowValidation)
        mainHandler.removeCallbacks(protectionCurtainDismiss)
        failsafeEvacuationGeneration = 0L
        pendingReadyWindowValidationGeneration = 0L
        awaitingSafeSurfaceGeneration = 0L
        instantBlockCurtainMode = null
        instantBlockCurtainVisible = false
        val curtain = instantBlockCurtain
        if (curtain != null && instantBlockCurtainAttached) {
            runCatching { windowManager?.removeViewImmediate(curtain) }
                .onFailure { error ->
                    FocusGuardLogger.logError(
                        "A11y",
                        "Falha ao liberar cortina instantânea",
                        error
                    )
                }
        }
        instantBlockCurtainAttached = false
    }

    private fun syncWarmOverlays() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(::syncWarmOverlays)
            return
        }
        if (!accessibilityServiceConnected) return
        val active = isBlockingSessionActive || focusModeSessionActive
        if (active) {
            armInstantBlockCurtain()
        } else {
            releaseInstantBlockCurtain()
        }
        protectedPowerMenuController?.onProtectionStateChanged(active)
    }

    private fun launchPomodoroLockScreen() {
        try {
            startActivity(
                Intent(this, PomodoroLockActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP
                    )
                }
            )
        } catch (error: RuntimeException) {
            FocusGuardLogger.logError("A11y", "Falha ao abrir Pomodoro", error)
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    private fun showToastThrottled(message: String) {
        val now = System.currentTimeMillis()
        if (now - lastToastTime < 3_000L) return
        lastToastTime = now
        scope.launch(Dispatchers.Main) {
            Toast.makeText(this@BlockingAccessibilityService, message, Toast.LENGTH_SHORT).show()
        }
    }

    private suspend fun getAppsForSessions(ids: List<Int>): List<String> {
        return if (ids.isEmpty()) emptyList()
        else database.sessionAppCrossRefDao().getAppsForSessions(ids)
    }

    private suspend fun getSitesForSessions(ids: List<Int>): List<String> {
        return if (ids.isEmpty()) emptyList()
        else database.sessionWebsiteCrossRefDao().getWebsitesForSessions(ids)
    }

    private fun recycleSafely(node: AccessibilityNodeInfo?) {
        if (node == null) return
        runCatching { node.recycle() }
    }

    override fun onDestroy() {
        accessibilityServiceConnected = false
        stopWebsiteTracking()
        mainHandler.removeCallbacks(protectionCurtainDismiss)
        protectionActionUntilElapsed = 0L
        protectedPowerMenuController?.destroy()
        releaseInstantBlockCurtain()
        runCatching { unregisterReceiver(packageReceiver) }
        runCatching { unregisterReceiver(launcherReceiver) }
        runCatching { unregisterReceiver(refreshReceiver) }
        CurtainDestinationReadyCoordinator.unregister(curtainDestinationReadyListener)
        runCatching { unregisterReceiver(screenStateReceiver) }
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
        private const val SAFE_REDIRECT_URL = "https://www.google.com"
        /**
         * How long a relevant click keeps intercepting follow-up events.
         *
         * This is the main defence against the race the user can win: the click on
         * the menu entry is seen *before* the destination screen exists, so the
         * guard bounces the transition itself instead of waiting for the new
         * window. Sized for a cold Settings start on a slow device — the cost of
         * being generous is only that Settings stays interceptive for a few
         * seconds after such a click.
         */
        private const val SETTINGS_TRANSITION_GUARD_MILLIS = 2_000L
        private const val SELF_PROTECTION_ACTION_DEBOUNCE_MILLIS = 2_500L
        private const val SELF_PROTECTION_NOTICE_DURATION_MILLIS = 1_200L
        private const val INSTANT_CURTAIN_FAILSAFE_MILLIS = 5_000L
        internal const val FAILSAFE_EVACUATION_HOLD_MILLIS = 450L
        internal const val SAFE_WINDOW_SETTLE_MILLIS = 160L
        internal const val UNSAFE_WINDOW_RECHECK_MILLIS = 240L
        internal const val EVENT_NOTIFICATION_TIMEOUT_MILLIS = 0L
        /**
         * Event types that can trigger settings interception.
         *
         * Ordered by how early they arrive, not by how much they tell us.
         * TYPE_WINDOWS_CHANGED and TYPE_VIEW_FOCUSED carry almost no class name or
         * text — on their own they decide nothing — but they are the first signals
         * that a new window exists, and once the transition guard is armed by a
         * click that is all it takes to bounce out. Waiting for
         * TYPE_WINDOW_STATE_CHANGED costs the frames in which the switch is already
         * on screen and tappable.
         *
         * All four are already in [requestedAccessibilityEventTypes], so this
         * widens nothing about what the service observes.
         */
        private val settingsInterceptionEventTypes = setOf(
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_CLICKED
        )
        internal const val WEBSITE_BLOCK_NOTICE_DURATION_MILLIS = 1_000L
        const val ACTION_REFRESH_BLOCKING = "com.focusguard.ACTION_REFRESH_BLOCKING"
        internal const val ACTION_DEV_RELINQUISH_ACCESSIBILITY =
            "com.focusguard.ACTION_DEV_RELINQUISH_ACCESSIBILITY"
        const val EXTRA_STRICT_BLOCK = "STRICT_BLOCK"
        const val EXTRA_BLOCKED_PACKAGE = "BLOCKED_PACKAGE"
        const val EXTRA_BLOCKED_DOMAIN = "BLOCKED_DOMAIN"
        const val EXTRA_REDIRECT_BROWSER_PACKAGE = "REDIRECT_BROWSER_PACKAGE"
        const val EXTRA_BLOCK_EVENT_UPTIME_MILLIS = "BLOCK_EVENT_UPTIME_MILLIS"
        const val EXTRA_CURTAIN_GENERATION = "CURTAIN_GENERATION"
        internal const val EXTRA_BLOCKING_SNAPSHOT_PRESENT = "BLOCKING_SNAPSHOT_PRESENT"
        internal const val EXTRA_BLOCKED_APPS_SNAPSHOT = "BLOCKED_APPS_SNAPSHOT"
        internal const val EXTRA_BLOCKED_SITES_SNAPSHOT = "BLOCKED_SITES_SNAPSHOT"
        internal const val EXTRA_BLOCKING_ACTIVE_SNAPSHOT = "BLOCKING_ACTIVE_SNAPSHOT"
        internal const val EXTRA_STRICT_POMODORO_SNAPSHOT = "STRICT_POMODORO_SNAPSHOT"

        internal fun confirmAccessibilityContextForInstalledEntry(
        directAccessibility: Boolean,
        installedAccessibilityApps: Boolean,
        rootMentionsAccessibility: () -> Boolean
    ): Boolean = directAccessibility ||
        (installedAccessibilityApps && rootMentionsAccessibility())

    internal fun settingsInterceptionEventTypesForTest(): Set<Int> =
        settingsInterceptionEventTypes

        internal fun settingsTransitionGuardMillisForTest(): Long =
            SETTINGS_TRANSITION_GUARD_MILLIS

        internal fun selfProtectionActionDebounceMillisForTest(): Long =
            SELF_PROTECTION_ACTION_DEBOUNCE_MILLIS

        internal fun shouldExecuteProtectionAction(
            blockedUntilElapsed: Long,
            nowElapsed: Long
        ): Boolean = blockedUntilElapsed <= 0L || nowElapsed > blockedUntilElapsed

        internal fun isSelfProtectionEngaged(
            cachedActive: Boolean,
            persistedActive: Boolean,
            focusModeActive: Boolean,
            armoredDeviceOwnerActive: Boolean
        ): Boolean = cachedActive ||
            persistedActive ||
            focusModeActive ||
            armoredDeviceOwnerActive

        internal fun shouldSearchSameRowMarkers(clicked: Rect, root: Rect): Boolean =
            !clicked.isEmpty &&
                !root.isEmpty &&
                clicked.height() * 3 < root.height()

        internal fun boundsShareHorizontalRow(clicked: Rect, marker: Rect): Boolean =
            !clicked.isEmpty &&
                !marker.isEmpty &&
                minOf(clicked.bottom, marker.bottom) > maxOf(clicked.top, marker.top)

        internal fun requestedAccessibilityEventTypes(): Int =
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_CLICKED or
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_FOCUSED

        internal fun shouldDismissCurtain(
            currentGeneration: Long,
            readyGeneration: Long
        ): Boolean = currentGeneration > 0L && currentGeneration == readyGeneration

        internal fun curtainLaunchFailureDecision(
            currentGeneration: Long,
            failedGeneration: Long
        ): CurtainLaunchFailureDecision = if (
            currentGeneration > 0L && currentGeneration == failedGeneration
        ) {
            CurtainLaunchFailureDecision.EVACUATE_THEN_HIDE
        } else {
            CurtainLaunchFailureDecision.NO_ACTION
        }

        internal fun instantCurtainFailsafeDecision(
            curtainVisible: Boolean,
            awaitingSafeSurfaceGeneration: Long,
            unsafeWindowVisible: Boolean
        ): InstantCurtainFailsafeDecision = when {
            !curtainVisible -> InstantCurtainFailsafeDecision.NO_ACTION
            awaitingSafeSurfaceGeneration > 0L || unsafeWindowVisible ->
                InstantCurtainFailsafeDecision.EVACUATE_THEN_HIDE
            else -> InstantCurtainFailsafeDecision.HIDE
        }

        internal fun screenOffCurtainDecision(
            curtainVisible: Boolean,
            awaitingSafeSurfaceGeneration: Long,
            unsafeWindowVisible: Boolean
        ): InstantCurtainFailsafeDecision = instantCurtainFailsafeDecision(
            curtainVisible = curtainVisible,
            awaitingSafeSurfaceGeneration = awaitingSafeSurfaceGeneration,
            unsafeWindowVisible = unsafeWindowVisible
        )

        internal fun shouldReuseAwaitedCurtain(
            holdUntilSafeSurface: Boolean,
            awaitingGeneration: Long,
            curtainVisible: Boolean
        ): Boolean = !holdUntilSafeSurface &&
            awaitingGeneration > 0L &&
            curtainVisible

        internal fun shouldEvictForProtectionAttempt(
            alreadyAwaitingSafeSurface: Boolean
        ): Boolean = !alreadyAwaitingSafeSurface

        internal fun hiddenOverlayFlags(flags: Int): Int =
            flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE

        internal fun visibleOverlayFlags(flags: Int): Int =
            (flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()) or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE

        internal fun shouldEvictBlockedAppBeforeNotice(blockedPackage: String?): Boolean =
            !blockedPackage.isNullOrBlank()

        internal fun createBlockedAppEvictionIntent(): Intent =
            Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }

        internal fun shouldLaunchBlockedAppEvictionFallback(
            globalHomeAccepted: Boolean,
            forceLauncherFallback: Boolean
        ): Boolean = !globalHomeAccepted || forceLauncherFallback

        internal fun createRefreshBlockingIntent(
            context: Context,
            blockedApps: Collection<String>,
            blockedSites: Collection<String>,
            blockingActive: Boolean,
            strictPomodoro: Boolean
        ): Intent {
            val normalizedApps = blockedApps.filter(String::isNotBlank).distinct()
            val normalizedSites = WebsiteBlocker.normalizeRules(blockedSites)
            SelfProtectionStateStore.setSnapshot(
                context = context,
                armed = blockingActive,
                blockedApps = normalizedApps,
                blockedSites = normalizedSites,
                strictPomodoro = strictPomodoro
            )

            return Intent(ACTION_REFRESH_BLOCKING).apply {
                setPackage(context.packageName)
                putExtra(EXTRA_BLOCKING_SNAPSHOT_PRESENT, true)
                putStringArrayListExtra(
                    EXTRA_BLOCKED_APPS_SNAPSHOT,
                    ArrayList(normalizedApps)
                )
                putStringArrayListExtra(
                    EXTRA_BLOCKED_SITES_SNAPSHOT,
                    ArrayList(normalizedSites)
                )
                putExtra(EXTRA_BLOCKING_ACTIVE_SNAPSHOT, blockingActive)
                putExtra(EXTRA_STRICT_POMODORO_SNAPSHOT, strictPomodoro)
            }
        }

        internal fun createDevelopmentRelinquishIntent(context: Context): Intent =
            Intent(ACTION_DEV_RELINQUISH_ACCESSIBILITY).setPackage(context.packageName)

        internal fun createBlockNoticeIntent(
            context: Context,
            strictBlock: Boolean,
            blockedPackage: String?,
            blockedDomain: String?,
            redirectBrowserPackage: String?,
            curtainGeneration: Long = 0L,
            eventUptimeMillis: Long = SystemClock.uptimeMillis()
        ): Intent = Intent(context, BlockNoticeActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            )
            putExtra(EXTRA_STRICT_BLOCK, strictBlock)
            putExtra(EXTRA_BLOCKED_PACKAGE, blockedPackage)
            putExtra(EXTRA_BLOCKED_DOMAIN, blockedDomain)
            putExtra(EXTRA_BLOCK_EVENT_UPTIME_MILLIS, eventUptimeMillis)
            putExtra(EXTRA_CURTAIN_GENERATION, curtainGeneration)
            redirectBrowserPackage
                ?.takeIf(String::isNotBlank)
                ?.let { putExtra(EXTRA_REDIRECT_BROWSER_PACKAGE, it) }
        }

        internal fun createSafeRedirectIntent(browserPackageName: String): Intent {
            require(browserPackageName.isNotBlank())
            return Intent(Intent.ACTION_VIEW, Uri.parse(SAFE_REDIRECT_URL)).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                setPackage(browserPackageName)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
        }
    }

    private object PackageManagerCompat {
        const val MATCH_ALL: Int = 0x00020000
    }
}
