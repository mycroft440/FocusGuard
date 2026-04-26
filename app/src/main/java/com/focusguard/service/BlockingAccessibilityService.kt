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
    @Volatile private var blockedAppsSet: Set<String> = setOf()
    @Volatile private var blockedWebsitesDomainSet: Set<String> = setOf()
    @Volatile private var isBlockingSessionActive = false
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

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.getDatabase(this)
        sessionManager = BlockingSessionManager.getInstance(this)
        deviceOwnerManager = DeviceOwnerManager(this)
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
            checkAndBlockWebsite(source)
        } finally {
            source.recycle()
        }
    }

    private fun blockApp(packageName: String) {
        try {
            performGlobalAction(GLOBAL_ACTION_HOME)
            showToastThrottled("App bloqueado pelo FocusGuard")
        } catch (_: Exception) {}
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
        job.cancel()
        try {
            Toast.makeText(this, "Serviço FocusGuard parado", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {}
    }
}
