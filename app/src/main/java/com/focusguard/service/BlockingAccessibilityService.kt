package com.focusguard.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BlockingAccessibilityService : AccessibilityService() {

    private lateinit var database: AppDatabase
    private lateinit var sessionManager: BlockingSessionManager
    private val scope = CoroutineScope(Dispatchers.Default)
    
    private var blockedApps = listOf<BlockedApp>()
    private var blockedWebsites = listOf<BlockedWebsite>()
    private var isBlockingSessionActive = false
    private var lastLoadTime = 0L
    private val CACHE_TIMEOUT = 5000L // 5 seconds cache

    private val browserPackages = listOf(
        "com.android.chrome",
        "org.mozilla.firefox",
        "com.opera.browser",
        "com.microsoft.emmx",
        "com.sec.android.app.sbrowser",
        "com.brave.browser",
        "com.kiwibrowser.browser",
        "com.duckduckgo.mobile.android"
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        database = AppDatabase.getDatabase(this)
        sessionManager = BlockingSessionManager(this)
        
        refreshData()
        
        // Configure accessibility service
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or 
                        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                        AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                        AccessibilityEvent.TYPE_VIEW_FOCUSED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
        }
        setServiceInfo(info)
        
        Toast.makeText(this, "FocusGuard Accessibility Service Started", Toast.LENGTH_SHORT).show()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
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
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                handleBrowserEvent(event)
            }
        }
    }

    private fun refreshData() {
        scope.launch {
            try {
                val active = sessionManager.isBlockingActive()
                val apps = database.blockedAppDao().getAllBlockedApps()
                val websites = database.blockedWebsiteDao().getAllBlockedWebsites()
                
                withContext(Dispatchers.Main) {
                    isBlockingSessionActive = active
                    blockedApps = apps
                    blockedWebsites = websites
                    lastLoadTime = System.currentTimeMillis()
                }
            } catch (e: Exception) {
                // Handle error
            }
        }
    }

    private fun handleWindowStateChanged(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        
        // Don't block our own app or the launcher
        if (packageName == this.packageName) return
        
        if (isAppBlocked(packageName)) {
            blockApp(packageName)
        }
    }

    private fun handleBrowserEvent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        
        // Check if it's a browser
        if (!isBrowser(packageName)) return
        
        val source = event.source ?: return
        checkAndBlockWebsite(source)
    }

    private fun isAppBlocked(packageName: String): Boolean {
        return blockedApps.any { it.packageName == packageName && it.isBlocked }
    }

    private fun blockApp(packageName: String) {
        // Open the home screen
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        startActivity(intent)
        
        // Show a toast notification
        scope.launch(Dispatchers.Main) {
            Toast.makeText(this@BlockingAccessibilityService, "App blocked by FocusGuard", Toast.LENGTH_SHORT).show()
        }
    }

    private fun isBrowser(packageName: String): Boolean {
        return browserPackages.contains(packageName)
    }

    private fun checkAndBlockWebsite(source: AccessibilityNodeInfo) {
        val addressBarNode = WebsiteBlocker.findAddressBarNode(source)
        
        if (addressBarNode != null && addressBarNode.text != null) {
            val url = addressBarNode.text.toString()
            
            if (url.isNotEmpty() && isWebsiteBlocked(url)) {
                blockWebsite(url)
                addressBarNode.recycle()
                return
            }
            addressBarNode.recycle()
        }
        
        // Deeper check if needed
        traverseAccessibilityTree(source)
    }

    private fun traverseAccessibilityTree(node: AccessibilityNodeInfo) {
        try {
            if (node.text != null) {
                val text = node.text.toString()
                if (WebsiteBlocker.isValidUrl(text) && isWebsiteBlocked(text)) {
                    blockWebsite(text)
                    return
                }
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) {
                    traverseAccessibilityTree(child)
                    child.recycle()
                }
            }
        } catch (e: Exception) {}
    }

    private fun isWebsiteBlocked(url: String): Boolean {
        val domain = WebsiteBlocker.extractDomain(url)
        return blockedWebsites.any { blockedSite ->
            blockedSite.isBlocked && (
                domain.contains(blockedSite.domain, ignoreCase = true) ||
                blockedSite.domain.contains(domain, ignoreCase = true)
            )
        }
    }

    private fun blockWebsite(url: String) {
        performGlobalAction(GLOBAL_ACTION_BACK)
        scope.launch(Dispatchers.Main) {
            Toast.makeText(this@BlockingAccessibilityService, "Website blocked by FocusGuard", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        Toast.makeText(this, "FocusGuard Accessibility Service Stopped", Toast.LENGTH_SHORT).show()
    }
}
