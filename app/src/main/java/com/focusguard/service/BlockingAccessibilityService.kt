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
import com.focusguard.admin.DeviceOwnerManager
import com.focusguard.utils.WebsiteBlocker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Service that monitors app usage and blocks access when necessary.
 * Now includes support for daily usage limits.
 */
class BlockingAccessibilityService : AccessibilityService() {

    private val job = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + job)
    
    private val isRunning = AtomicBoolean(false)
    private lateinit var database: AppDatabase
    private lateinit var sessionManager: BlockingSessionManager
    
    private var lastCheckTime = 0L
    private val CHECK_INTERVAL_MS = 2000L

    override fun onServiceConnected() {
        super.onServiceConnected()
        val info = AccessibilityServiceInfo()
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or 
                         AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        serviceInfo = info
        
        database = AppDatabase.getDatabase(this)
        sessionManager = BlockingSessionManager.getInstance(this)
        isRunning.set(true)
        
        android.util.Log.d("FocusGuard", "BlockingAccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!isRunning.get()) return

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastCheckTime < CHECK_INTERVAL_MS) return
        lastCheckTime = currentTime

        val packageName = event.packageName?.toString() ?: return
        
        // Don't block our own app or system UI
        if (packageName == "com.focusguard" || packageName == "com.android.systemui" || packageName == "com.focusguard.v2") {
            return
        }

        serviceScope.launch {
            checkAndEnforce(packageName)
        }
    }

    private suspend fun checkAndEnforce(currentPackage: String) {
        withContext(Dispatchers.IO) {
            try {
                val activeSessions = database.blockingSessionDao().getActiveSessions()
                if (activeSessions.isEmpty() && database.appUsageLimitDao().getAllActiveLimits().isEmpty()) {
                    return@withContext
                }

                // Check standard sessions
                val enforcingSessions = activeSessions.filter { session ->
                    sessionManager.isCurrentlyInBlockingWindow(session)
                }

                val enforcingIds = enforcingSessions.map { it.id }

                val sessionApps = database.sessionAppCrossRefDao().getAppsForSessions(enforcingIds).toSet()
                val activeWebsiteDomains = database.sessionWebsiteCrossRefDao().getWebsitesForSessions(enforcingIds)
                    .map { WebsiteBlocker.extractDomain(it).lowercase() }.toSet()

                // Daily Limits Enforcement
                val limitApps = mutableSetOf<String>()
                val activeLimits = database.appUsageLimitDao().getAllActiveLimits()
                
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
                            val appStats = stats.find { it.packageName == limit.packageName }
                            val totalTimeMin = (appStats?.totalTimeInForeground ?: 0L) / 60000L
                            
                            if (totalTimeMin >= limit.limitMinutes) {
                                limitApps.add(limit.packageName)
                            }
                        }
                    }
                }

                val allBlockedApps = sessionApps + limitApps

                if (allBlockedApps.contains(currentPackage)) {
                    withContext(Dispatchers.Main) {
                        performBlock()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("FocusGuard", "Error in checkAndEnforce", e)
            }
        }
    }

    private fun performBlock() {
        val intent = Intent(this, com.focusguard.ui.BlockingActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(intent)
    }

    override fun onInterrupt() {
        isRunning.set(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
