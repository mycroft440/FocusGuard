package com.focusguard.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.focusguard.R
import com.focusguard.admin.DeviceOwnerManager
import com.focusguard.database.AppDatabase
import com.focusguard.manager.BlockingSessionManager
import com.focusguard.manager.StrictPomodoroLock
import com.focusguard.security.AuthManager
import com.focusguard.ui.BlockNoticeActivity
import com.focusguard.ui.PomodoroLockActivity
import com.focusguard.utils.FocusGuardLogger
import com.focusguard.utils.WebsiteBlocker
import com.focusguard.utils.WebsiteUsageLimitPolicy
import dagger.hilt.android.AndroidEntryPoint
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
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

    @Inject lateinit var authManager: AuthManager

    private lateinit var database: AppDatabase
    private lateinit var sessionManager: BlockingSessionManager
    private lateinit var deviceOwnerManager: DeviceOwnerManager

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
    @Volatile private var lastEnforcementFingerprint: String? = null

    private var lastLoadTime = 0L
    private var lastBrowserCheck = 0L
    private var lastWebsiteBlockTime = 0L
    private var lastWebsiteBlockKey: String? = null
    private var lastToastTime = 0L
    private var defaultLauncherPackage: String? = null
    private var usageStatsManager: android.app.usage.UsageStatsManager? = null

    private val websiteTrackingLock = Any()
    @Volatile private var trackedDomain: String? = null
    @Volatile private var trackedPackageName: String? = null
    @Volatile private var trackedSinceMillis = 0L
    private var websiteTrackingJob: Job? = null

    private data class WebsiteUsageSlice(
        val domain: String,
        val deltaMillis: Long,
        val packageName: String
    )

    private val cacheTimeoutMillis = 5_000L
    private val browserDebounceMillis = 120L
    private val websiteBlockCooldownMillis = 1_500L
    private val websitePulseMillis = 5_000L
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

    private val settingsPackages = setOf(
        "com.android.settings",
        "com.miui.securitycenter",
        "com.huawei.systemmanager",
        "com.samsung.android.sm",
        "com.samsung.android.sm_cn"
    )

    private val criticalClassNames = arrayOf(
        "InstalledAppDetails",
        "AppDetailsActivity",
        "SubSettings",
        "AccessibilitySettings",
        "AccessibilityServiceSettings",
        "ManageApplications",
        "AppPermissionsEditor",
        "AppManager",
        "AppDetail",
        "AppControl"
    )

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
            lastLoadTime = 0L
            scope.launch {
                if (changedPackage in knownBrowserPackages) {
                    deviceOwnerManager.invalidateWebsitePolicyCache()
                }
                sessionManager.checkAndEnforce()
            }
        }
    }

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            lastLoadTime = 0L
            refreshData()
        }
    }

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.getDatabase(this)
        sessionManager = BlockingSessionManager.getInstance(this)
        deviceOwnerManager = DeviceOwnerManager.getInstance(this)
        usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE)
            as? android.app.usage.UsageStatsManager

        registerPackageReceiver()
        registerRefreshReceiver()
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

    private fun registerRefreshReceiver() {
        val filter = IntentFilter(ACTION_REFRESH_BLOCKING)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(refreshReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(refreshReceiver, filter)
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
            .setContentTitle("FocusGuard Ativo")
            .setContentText("Proteção contra distrações em execução")
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
        defaultLauncherPackage = calculateDefaultLauncher()
        calculateBrowserPackages()
        refreshData()
        scope.launch {
            sessionManager.checkAndEnforce()
        }

        // Preserva capacidades estáticas carregadas do XML, especialmente
        // canRetrieveWindowContent; apenas campos dinâmicos podem ser alterados aqui.
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_FOCUSED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 80L
        }
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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        try {
            val now = System.currentTimeMillis()
            if (now - lastLoadTime > cacheTimeoutMillis) refreshData()

            val packageName = event.packageName?.toString().orEmpty()
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                if (handleSettingsInterception(event)) return
                if (packageName !in browserPackages &&
                    packageName !in limitedWebsiteAppDomains
                ) {
                    stopWebsiteTracking(now)
                }
            }

            if (isPomodoroStrictActive && event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                handleStrictPomodoro(packageName, event.className?.toString().orEmpty())
                return
            }

            if (!isBlockingSessionActive && limitedWebsiteDomains.isEmpty()) return

            when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> handleWindowStateChanged(event)
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

    override fun onInterrupt() = Unit

    private fun refreshData() {
        refreshRequested.set(true)
        if (!isRefreshing.compareAndSet(false, true)) return
        scope.launch {
            try {
                do {
                    refreshRequested.set(false)
                    try {
                        val adultFilterEnabled = authManager.isAdultFilterEnabled()
                        val adultDomains = if (adultFilterEnabled) {
                            WebsiteBlocker.normalizeRules(
                                com.focusguard.data.PredefinedWebsites.ADULT_DOMAINS
                            )
                        } else {
                            emptySet()
                        }

                        val activeSessions = database.blockSessionDao().getAllActiveSessionsStatic()
                        val enforcingSessions = activeSessions.filter(
                            sessionManager::isCurrentlyInBlockingWindow
                        )
                        val enforcingIds = enforcingSessions.map { it.id }

                        val sessionApps = getAppsForSessions(enforcingIds).toSet()
                        val sessionSites = WebsiteBlocker.normalizeRules(
                            getSitesForSessions(enforcingIds)
                        )

                        val limitApps = calculateExceededAppLimits()
                        val websiteLimits = database.websiteUsageLimitDao().getAllStatic()
                            .filter { it.isEnabled }
                        val configuredWebsiteDomains = WebsiteBlocker.normalizeRules(
                            websiteLimits.map { it.domain }
                        )
                        val exceededWebsiteDomains = calculateExceededWebsiteLimits(websiteLimits)
                        val blockedWebsiteDomains = WebsiteBlocker.normalizeRules(
                            sessionSites + exceededWebsiteDomains + adultDomains
                        )
                        val configuredWebsiteApps = WebsiteBlocker.appPackageDomainsFor(
                            configuredWebsiteDomains
                        )
                        val blockedWebsiteApps = WebsiteBlocker.appPackageDomainsFor(
                            sessionSites + exceededWebsiteDomains
                        )
                        val enforcementFingerprint = listOf(
                            enforcingIds.sorted().joinToString(","),
                            sessionApps.sorted().joinToString(","),
                            sessionSites.sorted().joinToString(","),
                            limitApps.sorted().joinToString(","),
                            exceededWebsiteDomains.sorted().joinToString(",")
                        ).joinToString("|")
                        val shouldReconcilePolicies = lastEnforcementFingerprint?.let {
                            it != enforcementFingerprint
                        } == true

                        withContext(Dispatchers.Main) {
                            isPomodoroStrictActive = enforcingSessions.any {
                                it.sessionType == "POMODORO" && it.isBlockingEnabled
                            }
                            blockedAppsSet = sessionApps + limitApps
                            blockedWebsitesDomainSet = blockedWebsiteDomains
                            blockedWebsiteAppDomains = blockedWebsiteApps
                            limitedWebsiteDomains = configuredWebsiteDomains
                            limitedWebsiteAppDomains = configuredWebsiteApps
                            isBlockingSessionActive = enforcingSessions.isNotEmpty() ||
                                limitApps.isNotEmpty() ||
                                exceededWebsiteDomains.isNotEmpty() ||
                                adultFilterEnabled
                            lastEnforcementFingerprint = enforcementFingerprint
                            lastLoadTime = System.currentTimeMillis()
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
        val limits = database.appUsageLimitDao().getAllActiveLimitsStatic()
        val manager = usageStatsManager ?: return emptySet()
        if (limits.isEmpty()) return emptySet()

        val startOfDay = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val usage = manager.queryAndAggregateUsageStats(startOfDay, System.currentTimeMillis())
        val now = System.currentTimeMillis()

        return limits.filter { limit ->
            val usedMinutes = (usage[limit.packageName]?.totalTimeInForeground ?: 0L) / 60_000L
            usedMinutes >= limit.dailyLimitMinutes &&
                limit.preventOpeningAfterLimit &&
                WebsiteUsageLimitPolicy.isBlockingModeActive(
                    limit.lockMode,
                    limit.lockUntilTimestamp,
                    now
                )
        }.mapTo(mutableSetOf()) { it.packageName }
    }

    private suspend fun calculateExceededWebsiteLimits(
        limits: List<com.focusguard.database.WebsiteUsageLimit>
    ): Set<String> {
        if (limits.isEmpty()) return emptySet()
        val today = dateFormat.get()!!.format(Date())
        val limitDomains = WebsiteBlocker.normalizeRules(limits.map { it.domain })
        val usage = mutableMapOf<String, Long>()
        database.dailyUsageStatDao().getStatsForDateStatic(today).forEach { row ->
            WebsiteBlocker.findMatchingRule(row.identifier, limitDomains)?.let { rule ->
                usage[rule] = (usage[rule] ?: 0L) + row.timeSpentMs
            }
        }
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

    private fun handleWindowStateChanged(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        val className = event.className?.toString().orEmpty()
        if (className.contains("Toast") || className.contains("PopupWindow")) return
        if (packageName == this.packageName || packageName == defaultLauncherPackage) return

        val blockedWebsiteDomain = blockedWebsiteAppDomains[packageName]
        val limitedWebsiteDomain = limitedWebsiteAppDomains[packageName]
        when {
            blockedWebsiteDomain != null -> blockWebsiteApp(blockedWebsiteDomain, packageName)
            packageName in blockedAppsSet -> blockApp(packageName)
            limitedWebsiteDomain != null -> updateWebsiteTracking(
                domain = limitedWebsiteDomain,
                packageName = packageName,
                now = System.currentTimeMillis()
            )
            packageName in browserPackages &&
                (blockedWebsitesDomainSet.isNotEmpty() || limitedWebsiteDomains.isNotEmpty()) ->
                handleBrowserEvent(event)
        }
    }

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

    private fun handleSettingsInterception(event: AccessibilityEvent): Boolean {
        val packageName = event.packageName?.toString() ?: return false
        if (packageName !in settingsPackages) return false
        if (isPomodoroStrictActive) {
            performGlobalAction(GLOBAL_ACTION_BACK)
            launchPomodoroLockScreen()
            return true
        }

        val className = event.className?.toString().orEmpty()
        if (criticalClassNames.none { className.contains(it, ignoreCase = true) }) return false

        val eventMentionsApp = event.text.orEmpty().any {
            it?.toString()?.contains("FocusGuard", ignoreCase = true) == true
        }
        if (eventMentionsApp) {
            executeProtectionAction()
            return true
        }

        val root = rootInActiveWindow ?: return false
        return try {
            val nodes = root.findAccessibilityNodeInfosByText("FocusGuard")
            val found = nodes.isNotEmpty()
            nodes.forEach(::recycleSafely)
            if (found) executeProtectionAction()
            found
        } catch (error: RuntimeException) {
            FocusGuardLogger.logError("A11y", "Falha ao inspecionar Configurações", error)
            false
        } finally {
            recycleSafely(root)
        }
    }

    private fun executeProtectionAction() {
        performGlobalAction(GLOBAL_ACTION_BACK)
        showToastThrottled("Proteção ativa: ação restrita pelo FocusGuard")
    }

    private fun handleBrowserEvent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        if (packageName !in browserPackages) return

        val fastUrl = WebsiteBlocker.extractUrlFromEvent(event, packageName)
        val root = if (fastUrl == null) rootInActiveWindow ?: event.source else null
        val url = fastUrl ?: WebsiteBlocker.extractUrlFromRoot(root, packageName)
        val now = System.currentTimeMillis()

        if (!url.isNullOrBlank()) {
            val domain = WebsiteBlocker.extractDomain(url)
            updateWebsiteTracking(domain, packageName, now)
            if (WebsiteBlocker.findMatchingRule(domain, blockedWebsitesDomainSet) != null) {
                blockWebsite(domain, packageName)
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

    private fun updateWebsiteTracking(domain: String, packageName: String, now: Long) {
        val matchedRule = WebsiteBlocker.findMatchingRule(domain, limitedWebsiteDomains)
        if (matchedRule == null) {
            stopWebsiteTracking(now)
            return
        }

        var usageToPersist: WebsiteUsageSlice? = null
        synchronized(websiteTrackingLock) {
            val previousDomain = trackedDomain
            val previousPackage = trackedPackageName
            if (previousDomain == matchedRule && previousPackage == packageName) {
                val delta = (now - trackedSinceMillis).coerceIn(0L, maxUsageDeltaMillis)
                if (delta >= 1_000L) {
                    usageToPersist = WebsiteUsageSlice(matchedRule, delta, packageName)
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
                trackedDomain = matchedRule
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
                synchronized(websiteTrackingLock) {
                    val domain = trackedDomain
                    val packageName = trackedPackageName
                    if (domain == null || packageName == null) {
                        return@launch
                    }
                    val now = System.currentTimeMillis()
                    val delta = (now - trackedSinceMillis).coerceIn(0L, maxUsageDeltaMillis)
                    if (delta >= 1_000L) {
                        trackedSinceMillis = now
                        usageToPersist = WebsiteUsageSlice(domain, delta, packageName)
                    }
                }
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
            val limit = database.websiteUsageLimitDao().getAllStatic()
                .firstOrNull {
                    it.isEnabled && WebsiteBlocker.normalizeRule(it.domain) == usage.domain
                }
            if (limit != null) {
                val total = database.dailyUsageStatDao().getUsageMillis(usage.domain, today)
                val shouldBlock = WebsiteUsageLimitPolicy.shouldBlock(
                    usedMillis = total,
                    dailyLimitMinutes = limit.dailyLimitMinutes,
                    lockMode = limit.lockMode,
                    lockUntilTimestamp = limit.lockUntilTimestamp,
                    nowMillis = System.currentTimeMillis()
                )
                if (shouldBlock) {
                    enforceExceededWebsiteImmediately(usage)
                    sessionManager.checkAndEnforce()
                }
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

    private fun enforceExceededWebsiteImmediately(usage: WebsiteUsageSlice) {
        scope.launch(Dispatchers.Main) {
            val stillActive = synchronized(websiteTrackingLock) {
                trackedDomain == usage.domain && trackedPackageName == usage.packageName
            }
            if (!stillActive) return@launch

            blockedWebsitesDomainSet = blockedWebsitesDomainSet + usage.domain
            if (usage.packageName in browserPackages) {
                blockWebsite(usage.domain, usage.packageName)
            } else {
                blockedWebsiteAppDomains = blockedWebsiteAppDomains +
                    (usage.packageName to usage.domain)
                blockWebsiteApp(usage.domain, usage.packageName)
            }
        }
    }

    private fun blockApp(packageName: String) {
        launchBlockNotice(blockedPackage = packageName, blockedDomain = null)
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
        performGlobalAction(GLOBAL_ACTION_HOME)
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

    private fun launchBlockNotice(
        blockedPackage: String?,
        blockedDomain: String?,
        redirectBrowserPackage: String? = null
    ): Boolean {
        return try {
            startActivity(
                createBlockNoticeIntent(
                    context = this,
                    strictBlock = isPomodoroStrictActive,
                    blockedPackage = blockedPackage,
                    blockedDomain = blockedDomain,
                    redirectBrowserPackage = redirectBrowserPackage
                )
            )
            true
        } catch (error: RuntimeException) {
            FocusGuardLogger.logError("A11y", "Falha ao abrir tela de bloqueio", error)
            performGlobalAction(GLOBAL_ACTION_HOME)
            false
        }
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
        stopWebsiteTracking()
        runCatching { unregisterReceiver(packageReceiver) }
        runCatching { unregisterReceiver(refreshReceiver) }
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
        internal const val WEBSITE_BLOCK_NOTICE_DURATION_MILLIS = 1_000L
        const val ACTION_REFRESH_BLOCKING = "com.focusguard.ACTION_REFRESH_BLOCKING"
        const val EXTRA_STRICT_BLOCK = "STRICT_BLOCK"
        const val EXTRA_BLOCKED_PACKAGE = "BLOCKED_PACKAGE"
        const val EXTRA_BLOCKED_DOMAIN = "BLOCKED_DOMAIN"
        const val EXTRA_REDIRECT_BROWSER_PACKAGE = "REDIRECT_BROWSER_PACKAGE"

        internal fun createBlockNoticeIntent(
            context: Context,
            strictBlock: Boolean,
            blockedPackage: String?,
            blockedDomain: String?,
            redirectBrowserPackage: String?
        ): Intent = Intent(context, BlockNoticeActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            )
            putExtra(EXTRA_STRICT_BLOCK, strictBlock)
            putExtra(EXTRA_BLOCKED_PACKAGE, blockedPackage)
            putExtra(EXTRA_BLOCKED_DOMAIN, blockedDomain)
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
