package com.focusguard.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.focusguard.database.AppDatabase
import com.focusguard.database.BlockedApp
import com.focusguard.database.BlockedWebsite
import com.focusguard.manager.BlockingSessionManager
import com.focusguard.admin.DeviceOwnerManager
import com.focusguard.utils.WebsiteBlocker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicBoolean
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Accessibility Service that monitors and blocks distracting apps and websites.
 * Utilizes highly optimized caching mechanisms and Atomic concurrency guards.
 */
class BlockingAccessibilityService : AccessibilityService() {

    private lateinit var database: AppDatabase
    private lateinit var sessionManager: BlockingSessionManager
    private lateinit var deviceOwnerManager: DeviceOwnerManager
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.IO)

    // O(1) Lookup sets manually populated
    private var blockedAppsSet: Set<String> = setOf()
    private var blockedWebsitesDomainSet: Set<String> = setOf()
    
    private var isBlockingSessionActive = false
    private var lastLoadTime = 0L
    private val CACHE_TIMEOUT = 2000L // 2 seconds cache to reduce DB load
    private var lastScrollCheck = 0L
    private var lastToastTime = 0L

    // Guard Lock against Coroutine Flood
    private val isRefreshing = AtomicBoolean(false)

    private var browserPackages: Set<String> = setOf()
    private val browserPackagesOriginal = setOf(
        "com.android.chrome",
        "org.mozilla.firefox",
        "org.mozilla.firefox_beta",
        "com.opera.browser",
        "com.opera.mini.native",
        "com.microsoft.emmx",
        "com.sec.android.app.sbrowser",
        "com.brave.browser",
        "com.kiwibrowser.browser",
        "com.duckduckgo.mobile.android",
        "com.vivaldi.browser",
        "com.UCMobile.intl"
    )

    private var defaultLauncherPackage: String? = null

    // BroadcastReceiver to update browser list dynamically
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            calculateBrowserPackages()
        }
    }

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.getDatabase(this)
        sessionManager = BlockingSessionManager.getInstance(this)
        deviceOwnerManager = DeviceOwnerManager(this)

        // Register package change receiver
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        registerReceiver(packageReceiver, filter)
        
        val refreshFilter = android.content.IntentFilter("com.focusguard.ACTION_REFRESH_BLOCKING")
        registerReceiver(refreshReceiver, refreshFilter, android.content.Context.RECEIVER_NOT_EXPORTED)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

        // Cache persistent components once
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

            if (!isBlockingSessionActive) return

            when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    handleWindowStateChanged(event)
                }
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                    val packageName = event.packageName?.toString() ?: return
                    if (!browserPackages.contains(packageName)) return
                    
                    // SOTA Optimization: Skip UI scraping for Enterprise Managed Browsers
                    if (::deviceOwnerManager.isInitialized && deviceOwnerManager.isDeviceOwnerActive()) {
                        if (packageName == "com.android.chrome" || packageName == "com.microsoft.emmx") return
                    }
                    
                    val now = System.currentTimeMillis()
                    if (now - lastScrollCheck > 500) {
                        lastScrollCheck = now
                        handleBrowserEvent(event)
                    }
                }
            }
        } catch (e: Exception) {
            com.focusguard.utils.FocusGuardLogger.log("A11y", "Erro no onAccessibilityEvent: ${e.message}")
        }
    }

    private val refreshReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            lastLoadTime = 0 // Force refresh
            refreshData()
        }
    }

    private fun refreshData() {
        // Prevent concurrent identical DB polling requests
        if (!isRefreshing.compareAndSet(false, true)) return

        scope.launch {
            try {
                val activeSessions = database.blockSessionDao().getAllActiveSessionsStatic()
                val enforcingSessions = activeSessions.filter { sessionManager.isCurrentlyInBlockingWindow(it) }
                val enforcingIds = enforcingSessions.map { it.id }

                val sessionApps = database.sessionAppCrossRefDao().getAppsForSessions(enforcingIds).toSet()
                val activeWebsiteDomains = database.sessionWebsiteCrossRefDao().getWebsitesForSessions(enforcingIds)
                    .map { WebsiteBlocker.extractDomain(it).lowercase() }.toSet()

                // Daily Limits Enforcement
                val limitApps = mutableSetOf<String>()
                val activeLimits = database.appUsageLimitDao().getAllActiveLimitsStatic()
                
                if (activeLimits.isNotEmpty()) {
                    val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as? android.app.usage.UsageStatsManager
                    if (usageStatsManager != null) {
                        val cal = Calendar.getInstance()
                        cal.set(Calendar.HOUR_OF_DAY, 0)
                        cal.set(Calendar.MINUTE, 0)
                        cal.set(Calendar.SECOND, 0)
                        val startOfDay = cal.timeInMillis
                        
                        val stats = usageStatsManager.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_DAILY, startOfDay, System.currentTimeMillis())
                        
                        activeLimits.forEach { limit ->
                            val stat = stats.find { it.packageName == limit.packageName }
                            val usageMinutes = (stat?.totalTimeInForeground ?: 0L) / 1000 / 60
                            if (usageMinutes >= limit.dailyLimitMinutes) {
                                limitApps.add(limit.packageName)
                            }
                        }
                    }
                }

                // Website Daily Limits Enforcement
                val limitWebsites = mutableSetOf<String>()
                val activeWebsiteLimits = database.websiteUsageLimitDao().getAllStatic().filter { it.isEnabled }
                if (activeWebsiteLimits.isNotEmpty()) {
                    val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
                    val stats = database.dailyUsageStatDao().getStatsForDateStatic(today).associate { it.identifier to it.timeSpentMs }
                    
                    activeWebsiteLimits.forEach { limit ->
                        val usageMs = stats[limit.domain] ?: 0L
                        val usageMinutes = usageMs / 1000 / 60
                        if (usageMinutes >= limit.dailyLimitMinutes) {
                            limitWebsites.add(limit.domain.lowercase())
                        }
                    }
                }

                val allBlockedApps = sessionApps + limitApps
                val allBlockedWebsites = activeWebsiteDomains + limitWebsites

                withContext(Dispatchers.Main) {
                    isBlockingSessionActive = enforcingSessions.isNotEmpty() || limitApps.isNotEmpty()
                    blockedAppsSet = allBlockedApps
                    blockedWebsitesDomainSet = allBlockedWebsites
                    lastLoadTime = System.currentTimeMillis()
                }
            } catch (_: Exception) {
            } finally {
                isRefreshing.set(false)
            }
        }
    }

    private fun handleWindowStateChanged(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        val className = event.className?.toString() ?: ""

        if (className.contains("Toast") || className.contains("PopupWindow")) return

        if (packageName == this.packageName || packageName == defaultLauncherPackage) return

        if (blockedAppsSet.contains(packageName)) {
            blockApp(packageName)
        } else if (browserPackages.contains(packageName)) {
            handleBrowserEvent(event) // Anti-Bypass imediato
        }
    }

    private fun handleBrowserEvent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return

        if (!browserPackages.contains(packageName)) return
        
        // SOTA Optimization: Skip UI scraping for Enterprise Managed Browsers
        if (::deviceOwnerManager.isInitialized && deviceOwnerManager.isDeviceOwnerActive()) {
            if (packageName == "com.android.chrome" || packageName == "com.microsoft.emmx") return
        }

        val source = event.source ?: return
        try {
            if (isIncognitoMode(source)) { blockWebsite() } else { checkAndBlockWebsite(source) }
        } finally {
            source.recycle()
        }
    }

    private fun blockApp(packageName: String) {
        try {
            performGlobalAction(GLOBAL_ACTION_HOME)
            showToastThrottled("App bloqueado pelo FocusGuard")
        } catch (e: Exception) {
            com.focusguard.utils.FocusGuardLogger.log("A11y", "Erro no refreshData: ${e.message}")
        }
    }

    private fun checkAndBlockWebsite(source: AccessibilityNodeInfo) {
        try {
            val addressBarNode = WebsiteBlocker.findAddressBarNode(source)

            if (addressBarNode != null && addressBarNode.text != null) {
                val url = addressBarNode.text.toString()

                if (url.isNotEmpty() && isWebsiteBlocked(url)) {
                    blockWebsite()
                    addressBarNode.recycle()
                    return
                }
                addressBarNode.recycle()
            }
        } catch (e: Exception) {
            com.focusguard.utils.FocusGuardLogger.log("A11y", "Erro ao fechar app: ${e.message}")
        }
    }

    private fun isWebsiteBlocked(url: String): Boolean {
        try {
            val domain = WebsiteBlocker.extractDomain(url).lowercase()
            if (domain.length < 4) return false

            if (blockedWebsitesDomainSet.contains(domain)) return true

            // Domain walking for parent domain block checking (e.g. m.facebook.com matches facebook.com)
            var currentDomain = domain
            while (currentDomain.contains(".")) {
                val firstDotIndex = currentDomain.indexOf('.')
                if (firstDotIndex == -1 || firstDotIndex == currentDomain.lastIndex) break
                currentDomain = currentDomain.substring(firstDotIndex + 1)
                if (blockedWebsitesDomainSet.contains(currentDomain)) return true
            }

            return false
        } catch (_: Exception) {
            return false
        }
    }

        private fun isIncognitoMode(source: AccessibilityNodeInfo): Boolean {
        // Heurística para detectar modo anônimo/incógnito em navegadores comuns
        val textNodes = source.findAccessibilityNodeInfosByText("Incógnito") +
                         source.findAccessibilityNodeInfosByText("Incognito") +
                         source.findAccessibilityNodeInfosByText("Anônimo") +
                         source.findAccessibilityNodeInfosByText("Private") +
                         source.findAccessibilityNodeInfosByText("Privada")
        
        if (textNodes.isNotEmpty()) {
            textNodes.forEach { it.recycle() }
            return true
        }

        // Verificação via content description (alguns navegadores usam isso no ícone de incognito)
        return checkIncognitoHeuristics(source)
    }

    private fun checkIncognitoHeuristics(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        if (desc.contains("incognito") || desc.contains("anônimo") || desc.contains("privada")) return true
        
        for (i in 0 until node.childCount) {
            if (checkIncognitoHeuristics(node.getChild(i))) return true
        }
        return false
    }
    private fun blockWebsite() {
        try {
            performGlobalAction(GLOBAL_ACTION_HOME)
            showToastThrottled("Site bloqueado pelo FocusGuard")
        } catch (e: Exception) {
            com.focusguard.utils.FocusGuardLogger.log("A11y", "Erro ao bloquear browser: ${e.message}")
        }
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
        unregisterReceiver(packageReceiver)
        unregisterReceiver(refreshReceiver)
        job.cancel()
        try {
            Toast.makeText(this, "Serviço FocusGuard parado", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            com.focusguard.utils.FocusGuardLogger.log("A11y", "Erro no onDestroy: ${e.message}")
        }
    }
}