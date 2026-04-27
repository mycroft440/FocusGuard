package com.focusguard.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

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
    @Volatile private var blockedAppsSet: Set<String> = emptySet()
    @Volatile private var blockedWebsitesDomainSet: Set<String> = emptySet()
    @Volatile private var isBlockingSessionActive = false
    private var lastLoadTime = 0L
    private val CACHE_TIMEOUT = 2000L // 2 seconds cache to reduce DB load
    private var lastScrollCheck = 0L
    private var lastToastTime = 0L

    private var appUsageLimits: Map<String, Int> = emptyMap()
    private val usageExceededApps = mutableSetOf<String>()

    // Website usage limit tracking
    private var websiteUsageLimits: Map<String, Int> = emptyMap()
    private val websiteExceededDomains = mutableSetOf<String>()
    private val websiteDailyUsageMs = mutableMapOf<String, Long>()
    private var currentBrowsingDomain: String? = null
    private var currentBrowsingStartMs: Long = 0L

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

    override fun onCreate() {
        super.onCreate()
        com.focusguard.utils.FocusGuardLogger.init(applicationContext)
        com.focusguard.utils.FocusGuardLogger.log("BlockingService", "Acessibility Service Criado")
        database = AppDatabase.getDatabase(this)
        sessionManager = BlockingSessionManager.getInstance(this)
        deviceOwnerManager = DeviceOwnerManager(this)
        
        startUsageLimitMonitor()
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

            val packageName = event.packageName?.toString()

            // 3. Verificar limite de tempo de uso diário
            if (packageName != null && usageExceededApps.contains(packageName)) {
                blockApp(packageName)
                return
            }

            // 4. Bloqueio de Sessão Padrão ou Limite de Sites
            when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    // Se saiu do browser, finaliza tracking de tempo do site
                    if (packageName != null && !browserPackages.contains(packageName)) {
                        flushBrowsingTime()
                    }

                    if (!isBlockingSessionActive) return
                    handleWindowStateChanged(event)
                }
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                    if (packageName == null || !browserPackages.contains(packageName)) return
                    
                    // SOTA Optimization: Skip UI scraping for Enterprise Managed Browsers
                    if (::deviceOwnerManager.isInitialized && deviceOwnerManager.isDeviceOwnerActive()) {
                        if (packageName == "com.android.chrome" || packageName == "com.microsoft.emmx") {
                            if (isBlockingSessionActive) return
                        }
                    }
                    
                    val now = System.currentTimeMillis()
                    if (now - lastScrollCheck > 500) {
                        lastScrollCheck = now
                        // Tracking de tempo do site atual + verificação de limites
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
        // Prevent concurrent identical DB polling requests
        if (!isRefreshing.compareAndSet(false, true)) return

        scope.launch {
            try {
                val activeSessions = database.blockSessionDao().getAllActiveSessions()
                val enforcingSessions = activeSessions.filter { sessionManager.isCurrentlyInBlockingWindow(it) }
                val enforcingIds = enforcingSessions.map { it.id }

                val activeAppPackages = database.sessionAppCrossRefDao().getAppsForSessions(enforcingIds).toSet()
                val activeWebsiteDomains = database.sessionWebsiteCrossRefDao().getWebsitesForSessions(enforcingIds)
                    .map { WebsiteBlocker.extractDomain(it).lowercase() }.toSet()

                // Update volatile state atomically from background
                isBlockingSessionActive = enforcingSessions.isNotEmpty()
                blockedAppsSet = activeAppPackages
                blockedWebsitesDomainSet = activeWebsiteDomains
                lastLoadTime = System.currentTimeMillis()
            } catch (e: Exception) {
                com.focusguard.utils.FocusGuardLogger.logError("BlockingService", "Erro ao atualizar dados do banco", e)
            } finally {
                isRefreshing.set(false)
            }
        }
    }

    private fun startUsageLimitMonitor() {
        scope.launch {
            while (isActive) {
                checkUsageLimits()
                delay(60_000) // Verifica a cada 1 minuto
            }
        }
    }

    private suspend fun checkUsageLimits() {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                // App limits
                appUsageLimits = database.appUsageLimitDao().getAllEnabled().associate { it.packageName to it.dailyLimitMinutes }
                
                if (appUsageLimits.isNotEmpty()) {
                    val usageStatsManager = getSystemService(android.content.Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
                    val calendar = java.util.Calendar.getInstance()
                    calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
                    calendar.set(java.util.Calendar.MINUTE, 0)
                    calendar.set(java.util.Calendar.SECOND, 0)
                    val startTime = calendar.timeInMillis
                    val endTime = System.currentTimeMillis()

                    val stats = usageStatsManager.queryAndAggregateUsageStats(startTime, endTime)
                    for ((packageName, limitMinutes) in appUsageLimits) {
                        val limitMillis = limitMinutes * 60 * 1000L
                        val usage = stats[packageName]?.totalTimeInForeground ?: 0L
                        
                        if (usage >= limitMillis) {
                            if (!usageExceededApps.contains(packageName)) {
                                usageExceededApps.add(packageName)
                                com.focusguard.utils.FocusGuardLogger.log("Limits", "App $packageName excedeu o limite diário de $limitMinutes min")
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
                                com.focusguard.utils.FocusGuardLogger.log("Limits", "Site $domain excedeu o limite diário de $limitMinutes min (${usage / 60000}min usados)")
                            }
                        } else {
                            websiteExceededDomains.remove(domain)
                        }
                    }
                }

                // Reset diário à meia-noite
                val cal = java.util.Calendar.getInstance()
                if (cal.get(java.util.Calendar.HOUR_OF_DAY) == 0 && cal.get(java.util.Calendar.MINUTE) < 2) {
                    websiteDailyUsageMs.clear()
                    websiteExceededDomains.clear()
                    usageExceededApps.clear()
                }
            } catch (e: Exception) {
                com.focusguard.utils.FocusGuardLogger.logError("BlockingService", "Erro ao verificar limites de uso", e)
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
            checkAndBlockWebsite(source)
        } finally {
            source.recycle()
        }
    }

    private fun blockApp(packageName: String) {
        try {
            com.focusguard.utils.FocusGuardLogger.log("BlockingService", "App bloqueado: $packageName")
            performGlobalAction(GLOBAL_ACTION_HOME)
            showToastThrottled("App bloqueado pelo FocusGuard")
        } catch (e: Exception) {
            com.focusguard.utils.FocusGuardLogger.logError("BlockingService", "Erro ao bloquear app: $packageName", e)
        }
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
        } catch (_: Exception) {
        } finally {
            addressBarNode?.recycle()
        }
    }

    private fun trackAndCheckWebsiteUsage(event: AccessibilityEvent) {
        if (websiteUsageLimits.isEmpty()) return
        val source = event.source ?: return
        try {
            val addressBarNode = WebsiteBlocker.findAddressBarNode(source)
            if (addressBarNode?.text != null) {
                val url = addressBarNode.text.toString()
                val domain = WebsiteBlocker.extractDomain(url).lowercase()
                if (domain.length >= 4) {
                    // Encontrar qual domínio limitado corresponde
                    val matchedDomain = findMatchingLimitedDomain(domain)
                    if (matchedDomain != null) {
                        // Verificar se já excedeu
                        if (websiteExceededDomains.contains(matchedDomain)) {
                            blockWebsite()
                            addressBarNode.recycle()
                            source.recycle()
                            return
                        }
                        // Tracking de tempo
                        if (currentBrowsingDomain == matchedDomain) {
                            // Continua no mesmo domínio, nada a fazer
                        } else {
                            // Mudou de domínio, salvar tempo anterior
                            flushBrowsingTime()
                            currentBrowsingDomain = matchedDomain
                            currentBrowsingStartMs = System.currentTimeMillis()
                        }
                    } else {
                        flushBrowsingTime()
                    }
                }
            }
            addressBarNode?.recycle()
        } catch (_: Exception) {
        } finally {
            source.recycle()
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

    private fun flushBrowsingTime() {
        val domain = currentBrowsingDomain ?: return
        if (currentBrowsingStartMs > 0) {
            val elapsed = System.currentTimeMillis() - currentBrowsingStartMs
            if (elapsed in 1..600_000) { // Máximo 10 min por flush para evitar drift
                websiteDailyUsageMs[domain] = (websiteDailyUsageMs[domain] ?: 0L) + elapsed
            }
        }
        currentBrowsingDomain = null
        currentBrowsingStartMs = 0L
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

    private fun blockWebsite() {
        try {
            performGlobalAction(GLOBAL_ACTION_HOME)
            showToastThrottled("Site bloqueado pelo FocusGuard")
        } catch (_: Exception) {}
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
        com.focusguard.utils.FocusGuardLogger.log("BlockingService", "Acessibility Service Destruído")
        job.cancel()
        try {
            Toast.makeText(this, "Serviço FocusGuard parado", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {}
    }
}
