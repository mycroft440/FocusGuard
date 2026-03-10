package com.focusguard.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.focusguard.MainActivity
import com.focusguard.database.AppDatabase
import com.focusguard.database.BlockedApp
import com.focusguard.database.BlockedWebsite
import com.focusguard.manager.BlockingSessionManager
import com.focusguard.utils.WebsiteBlocker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BlockingAccessibilityService : AccessibilityService() {

    private lateinit var database: AppDatabase
    private lateinit var sessionManager: BlockingSessionManager
    private val scope = CoroutineScope(Dispatchers.Default)
    private var blockedApps = mutableListOf<BlockedApp>()
    private var blockedWebsites = mutableListOf<BlockedWebsite>()
    private var isBlockingSessionActive = false
    private val activityManager by lazy { getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager }
    private val browserPackages = listOf(
        "com.android.chrome",
        "org.mozilla.firefox",
        "com.opera.browser",
        "com.microsoft.emmx",
        "com.sec.android.app.sbrowser",
        "com.brave.browser",
        "com.kiwibrowser.browser"
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        database = AppDatabase.getDatabase(this)
        sessionManager = BlockingSessionManager(this)
        loadBlockedAppsAndWebsites()
        checkBlockingSessionStatus()
        
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

    private fun handleWindowStateChanged(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        
        // Only block if there's an active blocking session
        if (!isBlockingSessionActive) return
        
        // Check if the app is in the blocked list
        if (isAppBlocked(packageName)) {
            blockApp(packageName)
        }
    }

    private fun handleBrowserEvent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        
        // Only block if there's an active blocking session
        if (!isBlockingSessionActive) return
        
        // Check if it's a browser
        if (!isBrowser(packageName)) return
        
        val source = event.source ?: return
        checkAndBlockWebsite(source)
    }

    private fun isAppBlocked(packageName: String): Boolean {
        return blockedApps.any { it.packageName == packageName }
    }

    private fun blockApp(packageName: String) {
        // Open the home screen to prevent the app from launching
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        startActivity(intent)
        
        // Show a toast notification
        Toast.makeText(this, "App blocked by FocusGuard", Toast.LENGTH_SHORT).show()
    }

    private fun isBrowser(packageName: String): Boolean {
        return browserPackages.contains(packageName)
    }

    private fun checkAndBlockWebsite(source: AccessibilityNodeInfo) {
        // Find the address bar in the browser
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
        
        // Fallback: traverse the entire tree to find URLs
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
        } catch (e: Exception) {
            // Silently handle exceptions
        }
    }

    private fun isWebsiteBlocked(url: String): Boolean {
        val domain = WebsiteBlocker.extractDomain(url)
        return blockedWebsites.any { blockedSite ->
            domain.contains(blockedSite.domain, ignoreCase = true) ||
            blockedSite.domain.contains(domain, ignoreCase = true)
        }
    }

    private fun blockWebsite(url: String) {
        // Go back to prevent access to the blocked website
        performGlobalAction(GLOBAL_ACTION_BACK)
        Toast.makeText(this, "Website blocked by FocusGuard", Toast.LENGTH_SHORT).show()
    }

    private fun loadBlockedAppsAndWebsites() {
        scope.launch {
            try {
                blockedApps = database.blockedAppDao().getAllBlockedApps().toMutableList()
                blockedWebsites = database.blockedWebsiteDao().getAllBlockedWebsites().toMutableList()
            } catch (e: Exception) {
                // Handle database errors silently
            }
        }
    }

    private fun checkBlockingSessionStatus() {
        scope.launch {
            try {
                isBlockingSessionActive = sessionManager.isBlockingActive()
            } catch (e: Exception) {
                // Handle errors silently
            }
        }
    }

    override fun onInterrupt() {
        // Called when the service is interrupted
    }

    override fun onDestroy() {
        super.onDestroy()
        Toast.makeText(this, "FocusGuard Accessibility Service Stopped", Toast.LENGTH_SHORT).show()
    }
}
