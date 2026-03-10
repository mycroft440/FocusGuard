package com.focusguard.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import com.focusguard.MainActivity
import com.focusguard.database.AppDatabase
import com.focusguard.database.BlockedApp
import com.focusguard.database.BlockedWebsite
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BlockingAccessibilityService : AccessibilityService() {

    private lateinit var database: AppDatabase
    private val scope = CoroutineScope(Dispatchers.Default)
    private var blockedApps = mutableListOf<BlockedApp>()
    private var blockedWebsites = mutableListOf<BlockedWebsite>()
    private val activityManager by lazy { getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager }

    override fun onServiceConnected() {
        super.onServiceConnected()
        database = AppDatabase.getDatabase(this)
        loadBlockedAppsAndWebsites()
        
        // Configure accessibility service
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or 
                        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                        AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
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
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                handleWindowContentChanged(event)
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                handleViewTextChanged(event)
            }
        }
    }

    private fun handleWindowStateChanged(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        
        // Check if the app is in the blocked list
        if (isAppBlocked(packageName)) {
            blockApp(packageName)
        }
    }

    private fun handleWindowContentChanged(event: AccessibilityEvent) {
        // Check for URL in browser address bar
        val source = event.source ?: return
        checkAndBlockWebsite(source)
    }

    private fun handleViewTextChanged(event: AccessibilityEvent) {
        // Additional check for website blocking
        val packageName = event.packageName?.toString() ?: return
        if (isBrowser(packageName)) {
            val source = event.source ?: return
            checkAndBlockWebsite(source)
        }
    }

    private fun isAppBlocked(packageName: String): Boolean {
        return blockedApps.any { it.packageName == packageName }
    }

    private fun blockApp(packageName: String) {
        // Open the home screen to prevent the app from launching
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
        
        // Show a toast notification
        Toast.makeText(this, "App blocked by FocusGuard", Toast.LENGTH_SHORT).show()
    }

    private fun isBrowser(packageName: String): Boolean {
        val browserPackages = listOf(
            "com.android.chrome",
            "org.mozilla.firefox",
            "com.opera.browser",
            "com.microsoft.emmx",
            "com.sec.android.app.sbrowser"
        )
        return browserPackages.contains(packageName)
    }

    private fun checkAndBlockWebsite(source: android.view.accessibility.AccessibilityNodeInfo) {
        // Traverse the accessibility tree to find URL text
        traverseAccessibilityTree(source)
    }

    private fun traverseAccessibilityTree(node: android.view.accessibility.AccessibilityNodeInfo) {
        if (node.text != null) {
            val text = node.text.toString()
            if (isWebsiteBlocked(text)) {
                blockWebsite(text)
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                traverseAccessibilityTree(child)
            }
        }
    }

    private fun isWebsiteBlocked(url: String): Boolean {
        return blockedWebsites.any { url.contains(it.domain) }
    }

    private fun blockWebsite(url: String) {
        // Go back or close the browser tab
        performGlobalAction(GLOBAL_ACTION_BACK)
        Toast.makeText(this, "Website blocked by FocusGuard", Toast.LENGTH_SHORT).show()
    }

    private fun loadBlockedAppsAndWebsites() {
        scope.launch {
            blockedApps = database.blockedAppDao().getAllBlockedApps().toMutableList()
            blockedWebsites = database.blockedWebsiteDao().getAllBlockedWebsites().toMutableList()
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
