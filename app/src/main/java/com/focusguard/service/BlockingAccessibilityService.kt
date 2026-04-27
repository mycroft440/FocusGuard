package com.focusguard.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.focusguard.database.AppDatabase
import com.focusguard.database.DailyUsageStat
import com.focusguard.manager.BlockingSessionManager
import com.focusguard.admin.DeviceOwnerManager
import com.focusguard.utils.WebsiteBlocker
import com.focusguard.utils.FocusGuardLogger
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

class BlockingAccessibilityService : AccessibilityService() {

    private lateinit var database: AppDatabase
    private lateinit var sessionManager: BlockingSessionManager
    private lateinit var deviceOwnerManager: DeviceOwnerManager
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.IO)

    @Volatile private var blockedAppsSet: Set<String> = emptySet()
    @Volatile private var blockedWebsitesDomainSet: Set<String> = emptySet()
    @Volatile private var isBlockingSessionActive = false
    private var lastLoadTime = 0L
    private val CACHE_TIMEOUT = 2000L
    private var lastScrollCheck = 0L
    private var lastToastTime = 0L

    private var appUsageLimits: Map<String, Int> = emptyMap()
    private val usageExceededApps = mutableSetOf<String>()

    private var websiteUsageLimits: Map<String, Int> = emptyMap()
    private val websiteExceededDomains = mutableSetOf<String>()
    
    // Cache em memória do uso diário (sincronizado com DB)
    private val websiteDailyUsageMs = mutableMapOf<String, Long>()
    private var currentBrowsingDomain: String? = null
    private var lastTickMs: Long = 0L

    private val isRefreshing = AtomicBoolean(false)
    private var browserPackages: Set<String> = setOf()
    private val browserPackagesOriginal = setOf(
        "com.android.chrome", "org.mozilla.firefox", "org.mozilla.firefox_beta",
        "com.opera.browser", "com.opera.mini.native", "com.microsoft.emmx",
        "com.sec.android.app.sbrowser", "com.brave.browser", "com.kiwibrowser.browser",
        "com.duckduckgo.mobile.android", "com.vivaldi.browser", "com.UCMobile.intl"
    )

    private var defaultLauncherPackage: String? = null

    override fun onCreate() {
        super.onCreate()
        FocusGuardLogger.init(applicationContext)
        FocusGuardLogger.log("BlockingService", "Acessibility Service Criado")
        database = AppDatabase.getDatabase(this)
        sessionManager = BlockingSessionManager.getInstance(this)
        deviceOwnerManager = DeviceOwnerManager(this)
        
        loadInitialUsageStats()
        startUsageLimitMonitor()
    }

    private fun loadInitialUsageStats() {
        scope.launch {
            val today = getTodayDate()
            val stats = database.dailyUsageStatDao().getStatsForDate(today)
            stats.forEach { stat ->
                if (stat.type == "WEBSITE") {
                    websiteDailyUsageMs[stat.identifier] = stat.timeSpentMs
                }
            }
            FocusGuardLogger.log("Limits", "Estatísticas de uso carregadas para $today: ${stats.size} itens")
        }
    }

    private fun getTodayDate(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        defaultLauncherPackage = calculateDefaultLauncher()
        calculateBrowserPackages()
        refreshData()

        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 300
        }
        setServiceInfo(info)
    }

    private fun calculateBrowserPackages() {
        try {
            val browserIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("http://www.google.com"))
            val dynamicBrowsers = packageManager.queryIntentActivities(browserIntent, android.content.pm.PackageManager.MATCH_ALL)
                .mapNotNull { it.activityInfo?.packageName }.toSet()
            browserPackages = browserPackagesOriginal + dynamicBrowsers
        } catch (_: Exception) {
            browserPackages = browserPackagesOriginal
        }
    }

    private fun calculateDefaultLauncher(): String? {
        return try {
            val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME) }
            val resolveInfo = packageManager.resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
            resolveInfo?.activityInfo?.packageName
        } catch (_: Exception) { null }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            if (event == null) return

            if (System.currentTimeMillis() - lastLoadTime > CACHE_TIMEOUT) {
                refreshData()
            }

            val packageName = event.packageName?.toString()

            if (packageName != null && usageExceededApps.contains(packageName)) {
                blockApp(packageName)
                return
            }

            when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    if (packageName != null && !browserPackages.contains(packageName)) {
                        stopBrowsingTick()
                    }
                    if (!isBlockingSessionActive) return
                    handleWindowStateChanged(event)
                }
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                    if (packageName == null || !browserPackages.contains(packageName)) return
                    
                    val now = System.currentTimeMillis()
                    if (now - lastScrollCheck > 500) {
                        lastScrollCheck = now
                        trackAndCheckWebsiteUsage(event)
                        if (isBlockingSessionActive) {
                            handleBrowserEvent(event)
                        }
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun refreshData() {
        if (!isRefreshing.compareAndSet(false, true)) return
        scope.launch {
            try {
                val activeSessions = database.blockSessionDao().getAllActiveSessions()
                val enforcingSessions = activeSessions.filter { sessionManager.isCurrentlyInBlockingWindow(it) }
                val enforcingIds = enforcingSessions.map { it.id }

                val activeAppPackages = database.sessionAppCrossRefDao().getAppsForSessions(enforcingIds).toSet()
                val activeWebsiteDomains = database.sessionWebsiteCrossRefDao().getWebsitesForSessions(enforcingIds)
                    .map { WebsiteBlocker.extractDomain(it).lowercase() }.toSet()

                isBlockingSessionActive = enforcingSessions.isNotEmpty()
                blockedAppsSet = activeAppPackages
                blockedWebsitesDomainSet = activeWebsiteDomains
                lastLoadTime = System.currentTimeMillis()
            } catch (e: Exception) {
                FocusGuardLogger.logError("BlockingService", "Erro ao atualizar dados do banco", e)
            } finally {
                isRefreshing.set(false)
            }
        }
    }

    private fun startUsageLimitMonitor() {
        scope.launch {
            while (isActive) {
                checkUsageLimits()
                delay(30_000) // Verifica a cada 30 segundos agora
            }
        }
    }

    private suspend fun checkUsageLimits() {
        withContext(Dispatchers.IO) {
            try {
                val today = getTodayDate()
                
                // Limpeza de estatísticas antigas (mais de 7 dias)
                val cal = Calendar.getInstance()
                cal.add(Calendar.DAY_OF_YEAR, -7)
                val oldDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(cal.time)
                database.dailyUsageStatDao().deleteOldStats(oldDate)

                // App limits
                appUsageLimits = database.appUsageLimitDao().getAllEnabled().associate { it.packageName to it.dailyLimitMinutes }
                if (appUsageLimits.isNotEmpty()) {
                    val usageStatsManager = getSystemService(USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
                    val calendar = Calendar.getInstance()
                    calendar.set(Calendar.HOUR_OF_DAY, 0)
                    calendar.set(Calendar.MINUTE, 0)
                    calendar.set(Calendar.SECOND, 0)
                    val startTime = calendar.timeInMillis
                    val endTime = System.currentTimeMillis()

                    val stats = usageStatsManager.queryAndAggregateUsageStats(startTime, endTime)
                    for ((packageName, limitMinutes) in appUsageLimits) {
                        val limitMillis = limitMinutes * 60 * 1000L
                        val usage = stats[packageName]?.totalTimeInForeground ?: 0L
                        if (usage >= limitMillis) {
                            if (!usageExceededApps.contains(packageName)) {
                                usageExceededApps.add(packageName)
                                FocusGuardLogger.log("Limits", "App $packageName excedeu o limite")
                            }
                        } else {
                            usageExceededApps.remove(packageName)
                        }
                    }
                }

                // Website limits
                websiteUsageLimits = database.websiteUsageLimitDao().getAllEnabled().associate { it.domain to it.dailyLimitMinutes }
                if (websiteUsageLimits.isNotEmpty()) {
                    for ((domain, limitMinutes) in websiteUsageLimits) {
                        val limitMillis = limitMinutes * 60 * 1000L
                        val usage = websiteDailyUsageMs[domain] ?: 0L
                        if (usage >= limitMillis) {
                            if (!websiteExceededDomains.contains(domain)) {
                                websiteExceededDomains.add(domain)
                                FocusGuardLogger.log("Limits", "Site $domain excedeu o limite")
                            }
                        } else {
                            websiteExceededDomains.remove(domain)
                        }
                    }
                }

                // Reset diário via verificação de data (O MAIS SEGURO)
                val lastResetDate = getSharedPreferences("FocusGuardPrefs", MODE_PRIVATE).getString("last_reset_date", "")
                if (lastResetDate != today) {
                    websiteDailyUsageMs.clear()
                    websiteExceededDomains.clear()
                    usageExceededApps.clear()
                    getSharedPreferences("FocusGuardPrefs", MODE_PRIVATE).edit().putString("last_reset_date", today).apply()
                    FocusGuardLogger.log("Limits", "Reset diário realizado para $today")
                }
            } catch (e: Exception) {
                FocusGuardLogger.logError("BlockingService", "Erro ao verificar limites", e)
            }
        }
    }

    private fun handleWindowStateChanged(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        if (packageName == this.packageName || packageName == defaultLauncherPackage) return
        if (blockedAppsSet.contains(packageName)) {
            blockApp(packageName)
        } else if (browserPackages.contains(packageName)) {
            handleBrowserEvent(event)
        }
    }

    private fun handleBrowserEvent(event: AccessibilityEvent) {
        val source = event.source ?: return
        try {
            checkAndBlockWebsite(source)
        } finally {
            source.recycle()
        }
    }

    private fun blockApp(packageName: String) {
        FocusGuardLogger.log("BlockingService", "App bloqueado: $packageName")
        performGlobalAction(GLOBAL_ACTION_HOME)
        showToastThrottled("App bloqueado pelo FocusGuard")
    }

    private fun checkAndBlockWebsite(source: AccessibilityNodeInfo) {
        var addressBarNode: AccessibilityNodeInfo? = null
        try {
            addressBarNode = WebsiteBlocker.findAddressBarNode(source)
            if (addressBarNode?.text != null) {
                val url = addressBarNode.text.toString()
                if (url.isNotEmpty() && isWebsiteBlocked(url)) {
                    blockWebsite()
                }
            }
        } catch (_: Exception) {} finally {
            addressBarNode?.recycle()
        }
    }

    private fun trackAndCheckWebsiteUsage(event: AccessibilityEvent) {
        val source = event.source ?: return
        try {
            val addressBarNode = WebsiteBlocker.findAddressBarNode(source)
            if (addressBarNode?.text != null) {
                val url = addressBarNode.text.toString()
                val domain = WebsiteBlocker.extractDomain(url).lowercase()
                if (domain.length >= 4) {
                    val matchedDomain = findMatchingLimitedDomain(domain)
                    if (matchedDomain != null) {
                        if (websiteExceededDomains.contains(matchedDomain)) {
                            blockWebsite()
                            addressBarNode.recycle()
                            source.recycle()
                            return
                        }
                        updateBrowsingTick(matchedDomain)
                    } else {
                        stopBrowsingTick()
                    }
                }
            }
            addressBarNode?.recycle()
        } catch (_: Exception) {} finally {
            source.recycle()
        }
    }

    private fun updateBrowsingTick(domain: String) {
        val now = System.currentTimeMillis()
        if (currentBrowsingDomain == domain) {
            val elapsed = now - lastTickMs
            if (elapsed in 1..10000) { // Proteção contra saltos de tempo
                val newTotal = (websiteDailyUsageMs[domain] ?: 0L) + elapsed
                websiteDailyUsageMs[domain] = newTotal
                saveStatToDb(domain, newTotal)
            }
        } else {
            currentBrowsingDomain = domain
        }
        lastTickMs = now
    }

    private fun stopBrowsingTick() {
        currentBrowsingDomain = null
        lastTickMs = 0L
    }

    private fun saveStatToDb(identifier: String, timeMs: Long) {
        scope.launch {
            try {
                val today = getTodayDate()
                database.dailyUsageStatDao().insert(
                    DailyUsageStat(identifier, today, "WEBSITE", timeMs)
                )
            } catch (_: Exception) {}
        }
    }

    private fun findMatchingLimitedDomain(domain: String): String? {
        if (websiteUsageLimits.containsKey(domain)) return domain
        var current = domain
        while (current.contains(".")) {
            val firstDot = current.indexOf('.')
            if (firstDot == -1 || firstDot == current.lastIndex) break
            current = current.substring(firstDot + 1)
            if (websiteUsageLimits.containsKey(current)) return current
        }
        return null
    }

    private fun isWebsiteBlocked(url: String): Boolean {
        try {
            val domain = WebsiteBlocker.extractDomain(url).lowercase()
            if (domain.length < 4) return false
            if (blockedWebsitesDomainSet.contains(domain)) return true
            var currentDomain = domain
            while (currentDomain.contains(".")) {
                val firstDotIndex = currentDomain.indexOf('.')
                if (firstDotIndex == -1 || firstDotIndex == currentDomain.lastIndex) break
                currentDomain = currentDomain.substring(firstDotIndex + 1)
                if (blockedWebsitesDomainSet.contains(currentDomain)) return true
            }
            return false
        } catch (_: Exception) { return false }
    }

    private fun blockWebsite() {
        performGlobalAction(GLOBAL_ACTION_HOME)
        showToastThrottled("Site bloqueado pelo FocusGuard")
    }
    
    private fun showToastThrottled(message: String) {
        val now = System.currentTimeMillis()
        if (now - lastToastTime > 3000) {
            lastToastTime = now
            scope.launch(Dispatchers.Main) {
                try {
                    Toast.makeText(this@BlockingAccessibilityService, message, Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {}
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        FocusGuardLogger.log("BlockingService", "Acessibility Service Destruído")
        job.cancel()
    }
}
