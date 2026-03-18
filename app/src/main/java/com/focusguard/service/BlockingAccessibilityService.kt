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
import com.focusguard.utils.WebsiteBlocker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Accessibility Service that monitors and blocks distracting apps and websites.
 * Uses accessibility events to detect app launches and browser URL changes.
 */
class BlockingAccessibilityService : AccessibilityService() {

    private lateinit var database: AppDatabase
    private lateinit var sessionManager: BlockingSessionManager
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.IO)

    private var blockedApps = listOf<BlockedApp>()
    private var blockedWebsites = listOf<BlockedWebsite>()
    private var isBlockingSessionActive = false
    private var lastLoadTime = 0L
    private val CACHE_TIMEOUT = 1000L // 1 second cache
    private var lastScrollCheck = 0L // Debounce for content change

    private val browserPackages = setOf(
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

    override fun onServiceConnected() {
        super.onServiceConnected()
        database = AppDatabase.getDatabase(this)
        sessionManager = BlockingSessionManager.getInstance(this)

        refreshData()

        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 300 // 300ms - balanced between responsiveness and battery
        }
        setServiceInfo(info)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            if (event == null) return

            // Refresh data if cache expired
            if (System.currentTimeMillis() - lastLoadTime > CACHE_TIMEOUT) {
                refreshData()
            }

            if (!isBlockingSessionActive) return

            when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    handleWindowStateChanged(event)
                }
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                    val now = System.currentTimeMillis()
                    if (now - lastScrollCheck > 500) {
                        lastScrollCheck = now
                        handleBrowserEvent(event)
                    }
                }
            }
        } catch (_: Exception) {
            // Silently handle to prevent service crash
        }
    }

    private fun refreshData() {
        scope.launch {
            try {
                val isWindowActive = sessionManager.isBlockingActive()
                val apps = database.blockedAppDao().getAllBlockedApps()
                val websites = database.blockedWebsiteDao().getAllBlockedWebsites()

                withContext(Dispatchers.Main) {
                    isBlockingSessionActive = isWindowActive
                    blockedApps = apps
                    blockedWebsites = websites
                    lastLoadTime = System.currentTimeMillis()
                }
            } catch (_: Exception) {
                // Handle error silently
            }
        }
    }

    private fun getDefaultLauncherPackage(): String? {
        val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME) }
        val resolveInfo = packageManager.resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
        return resolveInfo?.activityInfo?.packageName
    }

    private fun handleWindowStateChanged(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        val className = event.className?.toString() ?: ""

        // Ignora Toasts e Menus do sistema ou sobreposições ingênuas (Falso/Positivo)
        if (className.contains("Toast") || className.contains("PopupWindow")) return

        // Proteção dinâmica contra loop infinito no Launcher
        val defaultLauncher = getDefaultLauncherPackage()
        if (packageName == this.packageName || packageName == defaultLauncher) return

        if (isAppBlocked(packageName)) {
            blockApp(packageName)
        }
    }

    private fun handleBrowserEvent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return

        // Check if it's a browser
        if (!isBrowser(packageName)) return

        val source = event.source ?: return
        try {
            checkAndBlockWebsite(source)
        } finally {
            source.recycle()
        }
    }

    private fun isAppBlocked(packageName: String): Boolean {
        return blockedApps.any { it.packageName == packageName && it.isBlocked }
    }

    private fun blockApp(packageName: String) {
        try {
            // Em vez de Intent home que falha no Split-Screen, usa ação global raiz
            performGlobalAction(GLOBAL_ACTION_HOME)

            scope.launch(Dispatchers.Main) {
                Toast.makeText(this@BlockingAccessibilityService, "App bloqueado pelo FocusGuard", Toast.LENGTH_SHORT).show()
            }
        } catch (_: Exception) {
            // Failed to redirect to home
        }
    }

    private fun isBrowser(packageName: String): Boolean {
        return browserPackages.contains(packageName)
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
        } catch (_: Exception) {
            // Handle error silently
        }
    }

    private fun isWebsiteBlocked(url: String): Boolean {
        return try {
            val domain = WebsiteBlocker.extractDomain(url).lowercase()
            if (domain.length < 4) return false // Avoid matching very short strings

            blockedWebsites.any { blockedSite ->
                if (!blockedSite.isBlocked) return@any false
                val blockedDomain = blockedSite.domain.lowercase()
                // Match: domain ends with blocked domain or vice versa
                domain == blockedDomain ||
                        domain.endsWith(".$blockedDomain") ||
                        blockedDomain.endsWith(".$domain")
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun blockWebsite() {
        try {
            performGlobalAction(GLOBAL_ACTION_HOME)

            scope.launch(Dispatchers.Main) {
                Toast.makeText(this@BlockingAccessibilityService, "Site bloqueado pelo FocusGuard", Toast.LENGTH_SHORT).show()
            }
        } catch (_: Exception) {
            // Failed to redirect to home
        }
    }

    override fun onInterrupt() {
        // Service interrupted - nothing to clean up
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
        try {
            Toast.makeText(this, "Serviço FocusGuard parado", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            // Context may be invalid during destruction
        }
    }
}
