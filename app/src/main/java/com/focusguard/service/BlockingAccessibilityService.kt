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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

    @Volatile private var blockedAppsSet: Set<String> = emptySet()
    @Volatile private var blockedWebsitesDomainSet: Set<String> = emptySet()
    @Volatile private var limitedWebsiteDomains: Set<String> = emptySet()
    @Volatile private var isBlockingSessionActive = false
    @Volatile private var isPomodoroStrictActive = false

    private var lastLoadTime = 0L
    private var lastBrowserCheck = 0L
    private var lastToastTime = 0L
    private var defaultLauncherPackage: String? = null
    private var usageStatsManager: android.app.usage.UsageStatsManager? = null

    private var trackedDomain: String? = null
    private var trackedSinceMillis = 0L

    private val cacheTimeoutMillis = 5_000L
    private val browserDebounceMillis = 300L
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

    private val incognitoTerms = arrayOf(
        "incognito",
        "incógnito",
        "anônimo",
        "private browsing",
        "navegação privada"
    )

    private var browserPackages: Set<String> = emptySet()
    private val knownBrowserPackages = setOf(
        "com.android.chrome",
        "com.android.chrome.beta",
        "com.android.chrome.dev",
        "com.android.chrome.canary",
        "com.microsoft.emmx",
        "com.brave.browser",
        "com.brave.browser_beta",
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
        "org.mozilla.fennec_aurora",
        "org.mozilla.focus",
        "org.mozilla.klar",
        "com.opera.browser",
        "com.opera.browser.beta",
        "com.opera.mini.native",
        "com.opera.gx",
        "com.sec.android.app.sbrowser",
        "com.sec.android.app.sbrowser.beta",
        "com.duckduckgo.mobile.android"
    )

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            calculateBrowserPackages()
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

        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 200L
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
                if (packageName !in browserPackages) stopWebsiteTracking(now)
            }

            if (isPomodoroStrictActive && event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                handleStrictPomodoro(packageName, event.className?.toString().orEmpty())
                return
            }

            if (!isBlockingSessionActive && limitedWebsiteDomains.isEmpty()) return

            when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> handleWindowStateChanged(event)
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                    if (packageName in browserPackages && now - lastBrowserCheck >= browserDebounceMillis) {
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
        if (!isRefreshing.compareAndSet(false, true)) return
        scope.launch {
            try {
                val adultFilterEnabled = authManager.isAdultFilterEnabled()
                val adultDomains = if (adultFilterEnabled) {
                    com.focusguard.data.PredefinedApps.PREVENTIVE_APPS
                        .asSequence()
                        .filter {
                            it.category == com.focusguard.data.PredefinedApps.CATEGORY_PORNOGRAPHY
                        }
                        .mapNotNull { it.domain }
                        .map(WebsiteBlocker::extractDomain)
                        .filter { it.isNotBlank() }
                        .toSet()
                } else {
                    emptySet()
                }

                sessionManager.checkAndEnforce()
                val activeSessions = database.blockSessionDao().getAllActiveSessionsStatic()
                val enforcingSessions = activeSessions.filter(sessionManager::isCurrentlyInBlockingWindow)
                val enforcingIds = enforcingSessions.map { it.id }

                val sessionApps = getAppsForSessions(enforcingIds).toSet()
                val sessionSites = getSitesForSessions(enforcingIds)
                    .map(WebsiteBlocker::extractDomain)
                    .filter { it.isNotBlank() }
                    .toSet()

                val limitApps = calculateExceededAppLimits()
                val websiteLimits = database.websiteUsageLimitDao().getAllStatic()
                    .filter { it.isEnabled }
                val configuredWebsiteDomains = websiteLimits
                    .map { WebsiteBlocker.extractDomain(it.domain) }
                    .filter { it.isNotBlank() }
                    .toSet()
                val exceededWebsiteDomains = calculateExceededWebsiteLimits(websiteLimits)

                withContext(Dispatchers.Main) {
                    isPomodoroStrictActive = enforcingSessions.any {
                        it.sessionType == "POMODORO" && it.isBlockingEnabled
                    }
                    blockedAppsSet = sessionApps + limitApps
                    blockedWebsitesDomainSet = sessionSites + exceededWebsiteDomains + adultDomains
                    limitedWebsiteDomains = configuredWebsiteDomains
                    isBlockingSessionActive = enforcingSessions.isNotEmpty() ||
                        limitApps.isNotEmpty() ||
                        exceededWebsiteDomains.isNotEmpty() ||
                        adultFilterEnabled
                    WebsiteBlocker.clearCache()
                    lastLoadTime = System.currentTimeMillis()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                FocusGuardLogger.logError("A11y", "Falha ao atualizar bloqueios", error)
            } finally {
                isRefreshing.set(false)
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
            val mode = limit.lockMode.uppercase(Locale.ROOT)
            val lockValid = mode != "TIME" || limit.lockUntilTimestamp == null ||
                limit.lockUntilTimestamp > now
            usedMinutes >= limit.dailyLimitMinutes &&
                limit.preventOpeningAfterLimit &&
                mode != "WARNING" &&
                lockValid
        }.mapTo(mutableSetOf()) { it.packageName }
    }

    private suspend fun calculateExceededWebsiteLimits(
        limits: List<com.focusguard.database.WebsiteUsageLimit>
    ): Set<String> {
        if (limits.isEmpty()) return emptySet()
        val today = dateFormat.get()!!.format(Date())
        val usage = database.dailyUsageStatDao().getStatsForDateStatic(today)
            .groupBy { WebsiteBlocker.extractDomain(it.identifier) }
            .mapValues { (_, rows) -> rows.sumOf { it.timeSpentMs } }
        val now = System.currentTimeMillis()

        return limits.filter { limit ->
            val domain = WebsiteBlocker.extractDomain(limit.domain)
            val usedMinutes = (usage[domain] ?: 0L) / 60_000L
            val mode = limit.lockMode.uppercase(Locale.ROOT)
            val lockValid = mode != "TIME" || limit.lockUntilTimestamp == null ||
                limit.lockUntilTimestamp > now
            usedMinutes >= limit.dailyLimitMinutes && lockValid
        }.mapTo(mutableSetOf()) { WebsiteBlocker.extractDomain(it.domain) }
    }

    private fun handleWindowStateChanged(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        val className = event.className?.toString().orEmpty()
        if (className.contains("Toast") || className.contains("PopupWindow")) return
        if (packageName == this.packageName || packageName == defaultLauncherPackage) return

        when {
            packageName in blockedAppsSet -> blockApp(packageName)
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

        val fastUrl = WebsiteBlocker.extractUrlFromEvent(event)
        val root = if (fastUrl == null) rootInActiveWindow ?: event.source else null
        val url = fastUrl ?: extractUrlFromRoot(root)
        val now = System.currentTimeMillis()

        if (!url.isNullOrBlank()) {
            val domain = WebsiteBlocker.extractDomain(url)
            updateWebsiteTracking(domain, now)
            if (WebsiteBlocker.isUrlBlocked(url, blockedWebsitesDomainSet)) {
                blockWebsite(domain)
                recycleSafely(root)
                return
            }
        }

        if (root != null && blockedWebsitesDomainSet.isNotEmpty() && isIncognitoMode(root)) {
            blockWebsite(null)
        }
        recycleSafely(root)
    }

    private fun extractUrlFromRoot(root: AccessibilityNodeInfo?): String? {
        if (root == null) return null
        val addressBar = WebsiteBlocker.findAddressBarNode(root) ?: return null
        return try {
            addressBar.text?.toString()?.trim()
        } finally {
            recycleSafely(addressBar)
        }
    }

    private fun updateWebsiteTracking(domain: String, now: Long) {
        val normalized = WebsiteBlocker.extractDomain(domain)
        if (normalized !in limitedWebsiteDomains) {
            stopWebsiteTracking(now)
            return
        }

        val previous = trackedDomain
        if (previous == normalized) {
            val delta = (now - trackedSinceMillis).coerceIn(0L, 30_000L)
            if (delta >= 1_000L) {
                persistWebsiteUsage(previous, delta)
                trackedSinceMillis = now
            }
            return
        }

        stopWebsiteTracking(now)
        trackedDomain = normalized
        trackedSinceMillis = now
    }

    private fun stopWebsiteTracking(now: Long = System.currentTimeMillis()) {
        val domain = trackedDomain ?: return
        val delta = (now - trackedSinceMillis).coerceIn(0L, 30_000L)
        if (delta >= 1_000L) persistWebsiteUsage(domain, delta)
        trackedDomain = null
        trackedSinceMillis = 0L
    }

    private fun persistWebsiteUsage(domain: String, deltaMillis: Long) {
        scope.launch {
            try {
                val today = dateFormat.get()!!.format(Date())
                database.dailyUsageStatDao().addUsage(domain, today, deltaMillis)
                val limit = database.websiteUsageLimitDao().getAllStatic()
                    .firstOrNull {
                        it.isEnabled && WebsiteBlocker.extractDomain(it.domain) == domain
                    }
                if (limit != null) {
                    val total = database.dailyUsageStatDao().getUsageMillis(domain, today)
                    if (total / 60_000L >= limit.dailyLimitMinutes) {
                        lastLoadTime = 0L
                        refreshData()
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                FocusGuardLogger.logError(
                    "A11y",
                    "Falha ao registrar uso de $domain",
                    error
                )
            }
        }
    }

    private fun isIncognitoMode(node: AccessibilityNodeInfo?): Boolean {
        return checkIncognito(node, 0)
    }

    private fun checkIncognito(node: AccessibilityNodeInfo?, depth: Int): Boolean {
        if (node == null || depth > 4) return false
        try {
            val text = node.text?.toString()?.lowercase(Locale.ROOT).orEmpty()
            val description = node.contentDescription?.toString()
                ?.lowercase(Locale.ROOT)
                .orEmpty()
            if (incognitoTerms.any { text.contains(it) || description.contains(it) }) return true

            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                val found = checkIncognito(child, depth + 1)
                recycleSafely(child)
                if (found) return true
            }
        } catch (error: RuntimeException) {
            FocusGuardLogger.logError("A11y", "Falha ao detectar modo privado", error)
        }
        return false
    }

    private fun blockApp(packageName: String) {
        launchBlockNotice(blockedPackage = packageName, blockedDomain = null)
    }

    private fun blockWebsite(domain: String?) {
        performGlobalAction(GLOBAL_ACTION_BACK)
        launchBlockNotice(blockedPackage = null, blockedDomain = domain)
    }

    private fun launchBlockNotice(blockedPackage: String?, blockedDomain: String?) {
        try {
            val intent = Intent(this, BlockNoticeActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                )
                putExtra(EXTRA_STRICT_BLOCK, isPomodoroStrictActive)
                putExtra(EXTRA_BLOCKED_PACKAGE, blockedPackage)
                putExtra(EXTRA_BLOCKED_DOMAIN, blockedDomain)
            }
            startActivity(intent)
        } catch (error: RuntimeException) {
            FocusGuardLogger.logError("A11y", "Falha ao abrir tela de bloqueio", error)
            performGlobalAction(GLOBAL_ACTION_HOME)
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
        const val ACTION_REFRESH_BLOCKING = "com.focusguard.ACTION_REFRESH_BLOCKING"
        const val EXTRA_STRICT_BLOCK = "STRICT_BLOCK"
        const val EXTRA_BLOCKED_PACKAGE = "BLOCKED_PACKAGE"
        const val EXTRA_BLOCKED_DOMAIN = "BLOCKED_DOMAIN"
    }

    /** Compatibilidade para evitar usar flags novas diretamente em APIs antigas. */
    private object PackageManagerCompat {
        const val MATCH_ALL: Int = 0x00020000
    }
}
