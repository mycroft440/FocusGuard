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
import com.focusguard.ui.PomodoroLockActivity
import com.focusguard.utils.WebsiteBlocker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Accessibility Service that monitors and blocks distracting apps and websites.
 * Utilizes optimized caching mechanisms and Atomic concurrency guards.
 */
class BlockingAccessibilityService : AccessibilityService() {

    private lateinit var database: AppDatabase
    private lateinit var sessionManager: BlockingSessionManager
    private lateinit var deviceOwnerManager: DeviceOwnerManager
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.IO)

    private var blockedAppsSet: Set<String> = setOf()
    private var blockedWebsitesDomainSet: Set<String> = setOf()

    private var isBlockingSessionActive = false
    private var lastLoadTime = 0L
    private val cacheTimeout = 5000L
    private var lastScrollCheck = 0L
    private var lastToastTime = 0L
    private val channelId = "focusguard_service_channel"
    private val notificationId = 101

    private var isRefreshing = AtomicBoolean(false)
    private var isPomodoroStrictActive = false

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
        "com.samsung.android.sm_cn"
    )
    private val criticalClassNames = arrayOf(
        "InstalledAppDetails", "AppDetailsActivity", "SubSettings",
        "AccessibilitySettings", "ManageApplications",
        "AppPermissionsEditor", "AppManager", "AppDetail", "AppControl"
    )
    private val incognitoTerms = arrayOf("incognito", "incógnito", "anônimo", "private", "privada")

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
    private var usageStatsManager: android.app.usage.UsageStatsManager? = null
    private val cachedCalendar = Calendar.getInstance()
    private val cachedDateFormat = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())

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
        usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as? android.app.usage.UsageStatsManager

        val packageFilter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        registerReceiver(packageReceiver, packageFilter)

        val refreshFilter = IntentFilter("com.focusguard.ACTION_REFRESH_BLOCKING")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(refreshReceiver, refreshFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(refreshReceiver, refreshFilter)
        }

        createNotificationChannel()
        startAsForeground()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                channelId,
                "FocusGuard Protection Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Mantém o FocusGuard ativo para garantir seus bloqueios"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    private fun startAsForeground() {
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("FocusGuard Ativo")
            .setContentText("Proteção contra distrações em execução")
            .setSmallIcon(R.drawable.ic_shield)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(Notification.CATEGORY_SERVICE)
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

        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 300
        }
        setServiceInfo(info)
    }

    private fun calculateBrowserPackages() {
        browserPackages = try {
            val browserIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("http://www.google.com"))
            val dynamicBrowsers = packageManager.queryIntentActivities(browserIntent, android.content.pm.PackageManager.MATCH_ALL)
                .mapNotNull { it.activityInfo?.packageName }
                .toSet()
            browserPackagesOriginal + dynamicBrowsers
        } catch (_: Exception) {
            browserPackagesOriginal
        }
    }

    private fun calculateDefaultLauncher(): String? {
        return try {
            val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME) }
            val resolveInfo = packageManager.resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
            resolveInfo?.activityInfo?.packageName
        } catch (_: Exception) {
            null
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            if (event == null) return

            if (System.currentTimeMillis() - lastLoadTime > cacheTimeout) {
                refreshData()
            }

            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                if (handleSettingsInterception(event)) return
            }

            if (!isBlockingSessionActive) return

            when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> handleWindowStateChanged(event)
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                    val packageName = event.packageName?.toString() ?: return
                    if (!browserPackages.contains(packageName)) return
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

    private fun refreshData() {
        if (!isRefreshing.compareAndSet(false, true)) return

        scope.launch {
            try {
                val activeSessions = database.blockSessionDao().getAllActiveSessionsStatic()
                val enforcingSessions = activeSessions.filter { sessionManager.isCurrentlyInBlockingWindow(it) }
                val enforcingIds = enforcingSessions.map { it.id }

                val sessionApps = database.sessionAppCrossRefDao().getAppsForSessions(enforcingIds).toSet()
                val activeWebsiteDomains = database.sessionWebsiteCrossRefDao().getWebsitesForSessions(enforcingIds)
                    .map { WebsiteBlocker.extractDomain(it).lowercase() }
                    .toSet()

                val limitApps = mutableSetOf<String>()
                val activeLimits = database.appUsageLimitDao().getAllActiveLimitsStatic()
                if (activeLimits.isNotEmpty() && usageStatsManager != null) {
                    cachedCalendar.timeInMillis = System.currentTimeMillis()
                    cachedCalendar.set(Calendar.HOUR_OF_DAY, 0)
                    cachedCalendar.set(Calendar.MINUTE, 0)
                    cachedCalendar.set(Calendar.SECOND, 0)
                    val startOfDay = cachedCalendar.timeInMillis
                    val stats = usageStatsManager!!.queryUsageStats(
                        android.app.usage.UsageStatsManager.INTERVAL_DAILY,
                        startOfDay,
                        System.currentTimeMillis()
                    )

                    activeLimits.forEach { limit ->
                        val stat = stats.find { it.packageName == limit.packageName }
                        val usageMinutes = (stat?.totalTimeInForeground ?: 0L) / 1000 / 60
                        if (usageMinutes >= limit.dailyLimitMinutes) {
                            limitApps.add(limit.packageName)
                        }
                    }
                }

                val limitWebsites = mutableSetOf<String>()
                val activeWebsiteLimits = database.websiteUsageLimitDao().getAllStatic().filter { it.isEnabled }
                if (activeWebsiteLimits.isNotEmpty()) {
                    val today = cachedDateFormat.format(java.util.Date())
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
                    val pomodoroStrict = enforcingSessions.any { it.sessionType == "POMODORO" && it.isBlockingEnabled }
                    isPomodoroStrictActive = pomodoroStrict
                    isBlockingSessionActive = enforcingSessions.isNotEmpty() || limitApps.isNotEmpty() || pomodoroStrict
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

        if (isPomodoroStrictActive) {
            // Durante Pomodoro rigoroso: TUDO é bloqueado exceto FocusGuard e telefone
            if (packageName == this.packageName) return
            if (phonePackages.contains(packageName)) return

            // Bloquear launcher (home button) - redirecionar para lock screen
            if (packageName == defaultLauncherPackage) {
                com.focusguard.utils.FocusGuardLogger.log("A11y", "Bloqueio Rigoroso: Launcher interceptado")
                launchPomodoroLockScreen()
                return
            }

                        // Bloquear SystemUI integralmente (Recents, Notification Shade, Quick Settings)
            if (packageName == "com.android.systemui") {
                com.focusguard.utils.FocusGuardLogger.log("A11y", "Bloqueio Rigoroso: SystemUI interceptado ($className)")
                performGlobalAction(GLOBAL_ACTION_HOME)
                performGlobalAction(GLOBAL_ACTION_BACK)
                launchPomodoroLockScreen()
                return
            }

            // Bloquear Settings (impedir desabilitar acessibilidade)
            if (settingsPackages.contains(packageName)) {
                com.focusguard.utils.FocusGuardLogger.log("A11y", "Bloqueio Rigoroso: Settings bloqueado")
                performGlobalAction(GLOBAL_ACTION_BACK)
                launchPomodoroLockScreen()
                return
            }

                        com.focusguard.utils.FocusGuardLogger.log("A11y", "Bloqueio Rigoroso Pomodoro: $packageName impedido")
            performGlobalAction(GLOBAL_ACTION_HOME)
            blockApp(packageName)
            return
        }

        // Modo normal: não bloquear o launcher
        if (packageName == this.packageName || packageName == defaultLauncherPackage) return

        if (blockedAppsSet.contains(packageName)) {
            blockApp(packageName)
        } else if (browserPackages.contains(packageName)) {
            handleBrowserEvent(event)
        }
    }

    private fun launchPomodoroLockScreen() {
        try {
            val intent = Intent(this, PomodoroLockActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
            }
            startActivity(intent)
        } catch (e: SecurityException) {
            com.focusguard.utils.FocusGuardLogger.logError("A11y", "Permissao negada ao lancar LockScreen. Verifique Device Admin.", e)
            performGlobalAction(GLOBAL_ACTION_HOME)
        } catch (e: Exception) {
            com.focusguard.utils.FocusGuardLogger.logError("A11y", "Erro inesperado ao lancar LockScreen", e)
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    private fun handleSettingsInterception(event: AccessibilityEvent): Boolean {
        val packageName = event.packageName?.toString() ?: return false
        if (!settingsPackages.contains(packageName)) return false

        // Durante Pomodoro rigoroso: bloquear QUALQUER acesso a Settings
        if (isPomodoroStrictActive) {
            performGlobalAction(GLOBAL_ACTION_BACK)
            launchPomodoroLockScreen()
            return true
        }

        val className = event.className?.toString() ?: ""
        val isCriticalSettingsPage = criticalClassNames.any { className.contains(it) }
        
        // Também interceptar a tela de Accessibility Settings para proteger o serviço
        val isAccessibilityPage = className.contains("AccessibilitySettings") || className.contains("AccessibilityServiceSettings")
        if (!isCriticalSettingsPage && !isAccessibilityPage) return false

        val eventTexts = event.text
        if (eventTexts != null) {
            for (text in eventTexts) {
                if (text != null && text.toString().contains("focusguard", ignoreCase = true)) {
                    executeProtectionAction()
                    return true
                }
            }
        }

        val rootNode = rootInActiveWindow ?: return false
        try {
            val nodes = rootNode.findAccessibilityNodeInfosByText("FocusGuard")
            if (nodes.isNotEmpty()) {
                nodes.forEach { it.recycle() }
                executeProtectionAction()
                return true
            }
        } catch (_: Exception) {
        } finally {
            rootNode.recycle()
        }

        return false
    }

    private fun executeProtectionAction() {
        performGlobalAction(GLOBAL_ACTION_BACK)
        showToastThrottled("Proteção Ativa: Ação restrita pelo FocusGuard")
    }

    private fun handleBrowserEvent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        if (!browserPackages.contains(packageName)) return
        if (::deviceOwnerManager.isInitialized && deviceOwnerManager.isDeviceOwnerActive()) {
            if (packageName == "com.android.chrome" || packageName == "com.microsoft.emmx") return
        }

        val source = event.source ?: return
        try {
            if (isIncognitoMode(source)) {
                blockWebsite()
            } else {
                checkAndBlockWebsite(source)
            }
        } finally {
            source.recycle()
        }
    }

    private fun blockApp(packageName: String) {
        try {
            val intent = Intent(this, com.focusguard.ui.BlockNoticeActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                putExtra("STRICT_BLOCK", isPomodoroStrictActive)
            }
            startActivity(intent)
        } catch (e: Exception) {
            com.focusguard.utils.FocusGuardLogger.log("A11y", "Erro ao bloquear app: ${e.message}")
            performGlobalAction(GLOBAL_ACTION_HOME)
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
        return try {
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
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun isIncognitoMode(source: AccessibilityNodeInfo): Boolean {
        return checkIncognitoSinglePass(source, 0)
    }

    private fun checkIncognitoSinglePass(node: AccessibilityNodeInfo?, depth: Int): Boolean {
        if (node == null || depth > 4) return false

        try {
            val text = node.text?.toString()?.lowercase()
            if (text != null && incognitoTerms.any { text.contains(it) }) return true

            val desc = node.contentDescription?.toString()?.lowercase()
            if (desc != null && incognitoTerms.any { desc.contains(it) }) return true

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                if (checkIncognitoSinglePass(child, depth + 1)) {
                    child.recycle()
                    return true
                }
                child.recycle()
            }
        } catch (_: Exception) {
        }

        return false
    }

    private fun blockWebsite() {
        try {
            val intent = Intent(this, com.focusguard.ui.BlockNoticeActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                putExtra("STRICT_BLOCK", isPomodoroStrictActive)
            }
            startActivity(intent)
        } catch (e: Exception) {
            com.focusguard.utils.FocusGuardLogger.log("A11y", "Erro ao bloquear browser: ${e.message}")
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    private fun showToastThrottled(message: String) {
        val now = System.currentTimeMillis()
        if (now - lastToastTime > 3000) {
            lastToastTime = now
            scope.launch(Dispatchers.Main) {
                try {
                    Toast.makeText(this@BlockingAccessibilityService, message, Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {
                }
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(packageReceiver)
        } catch (_: Exception) {
        }
        try {
            unregisterReceiver(refreshReceiver)
        } catch (_: Exception) {
        }
        job.cancel()

        // AUTO-HEAL: Se o Pomodoro rigoroso está ativo, o serviço não deveria morrer.
        // Garantir que o watchdog continua protegendo.
        if (StrictPomodoroLock.isActive(applicationContext)) {
            com.focusguard.utils.FocusGuardLogger.log("A11y", "ALERTA: AccessibilityService destruído durante Pomodoro rigoroso! Ativando failsafe.")
            // Iniciar/manter o watchdog foreground service
            PomodoroForegroundService.start(applicationContext)
            // REAGENDAR WATCHDOG V23
            PomodoroForegroundService.scheduleWatchdogAlarm(applicationContext)
        } else {
            try {
                Toast.makeText(this, "Serviço FocusGuard parado", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                com.focusguard.utils.FocusGuardLogger.logError("A11y", "Erro critico no onDestroy do AccessibilityService", e)
            }
        }
    }
}
