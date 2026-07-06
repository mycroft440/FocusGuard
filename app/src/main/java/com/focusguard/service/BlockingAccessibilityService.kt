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
import com.focusguard.security.AuthManager
import com.focusguard.ui.PomodoroLockActivity
import com.focusguard.utils.WebsiteBlocker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/**
 * Accessibility Service that monitors and blocks distracting apps and websites.
 * Utilizes optimized caching mechanisms and Atomic concurrency guards.
 *
 * Anotado com @AndroidEntryPoint para que Hilt injete [AuthManager] (singleton)
 * em vez de criar uma instância paralela via `AuthManager(this)` direto.
 */
@AndroidEntryPoint
class BlockingAccessibilityService : AccessibilityService() {

    private lateinit var database: AppDatabase
    private lateinit var sessionManager: BlockingSessionManager
    private lateinit var deviceOwnerManager: DeviceOwnerManager

    // Injetado via Hilt — usa a mesma instância singleton do app inteiro
    // (corrigido em P0-2 / P3-1).
    @Inject lateinit var authManager: AuthManager

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
        // Chromium-based
        "com.android.chrome",
        "com.android.chrome.beta",
        "com.android.chrome.dev",
        "com.android.chrome.canary",
        "com.microsoft.emmx",
        "com.brave.browser",
        "com.brave.browser_beta",
        "com.kiwibrowser.browser",
        "com.kiwibrowser.browser.dev",
        "com.vivaldi.browser",
        "com.vivaldi.browser.snapshot",
        "com.ecosia.android",
        "com.yandex.browser",
        "com.UCMobile.intl",
        "com.UCMobile.intl.mi",
        // Mozilla
        "org.mozilla.firefox",
        "org.mozilla.firefox_beta",
        "org.mozilla.fennec_aurora",
        "org.mozilla.focus",
        "org.mozilla.klar",
        // Opera
        "com.opera.browser",
        "com.opera.browser.beta",
        "com.opera.mini.native",
        "com.opera.gx",
        // Samsung
        "com.sec.android.app.sbrowser",
        "com.sec.android.app.sbrowser.beta",
        // DuckDuckGo
        "com.duckduckgo.mobile.android",
        // Outros
        "com.google.android.apps.chromeos",
        // Apps com in-app browsers (detectados via WebView)
        "com.facebook.katana",
        "com.facebook.lite",
        "com.instagram.android",
        "com.twitter.android",
        "com.zhiliaoapp.musically",
        "com.reddit.frontpage",
        "com.linkedin.android",
        "com.tinder",
        "com.whatsapp",
        "com.telegram.messenger",
        "org.telegram.messenger",
        "com.discord"
    )

    private var defaultLauncherPackage: String? = null
    private var usageStatsManager: android.app.usage.UsageStatsManager? = null
    // ANTIGO: cachedCalendar mutável compartilhado entre threads (refreshData
    // roda em scope.launch(Dispatchers.IO) mas onAccessibilityEvent roda no
    // main thread — ambas acessavam cachedCalendar sem sincronização).
    // NOVO: criamos um Calendar local em cada uso — o custo de criação
    // (~1μs) é desprezível vs. o custo de queryUsageStats (10-50ms) e evita
    // race conditions que podiam produzir horários incorretos.
    private val cachedDateFormat = ThreadLocal.withInitial {
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
    }

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
        // authManager é injetado via Hilt (@Inject lateinit var acima).
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
        // Em API 33+ (Tiramisu), registerReceiver sem flag de exported lança
        // SecurityException. Como este receiver só precisa receber broadcasts
        // do sistema (ACTION_PACKAGE_*), usamos RECEIVER_NOT_EXPORTED.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(packageReceiver, packageFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(packageReceiver, packageFilter)
        }

        val refreshFilter = IntentFilter(ACTION_REFRESH_BLOCKING)
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
                description = getString(R.string.mantem_o_focusguard_ativo_para_garantir_)
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
            val browserIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.google.com"))
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
        } catch (e: Throwable) {
            // Captura Throwable (não só Exception) — em release builds com R8,
            // NoClassDefFoundError/NoSuchMethodError/OutOfMemoryError podem
            // escapar de catch(Exception) e derrubar o Accessibility Service
            // permanentemente, desativando todo o bloqueio do app.
            com.focusguard.utils.FocusGuardLogger.logError("A11y", "Erro no onAccessibilityEvent: ${e.message}", e)
        }
    }

    private fun refreshData() {
        if (!isRefreshing.compareAndSet(false, true)) return

        scope.launch {
            try {
                val isAdultFilterActive = authManager.isAdultFilterEnabled()
                
                val adultDomains = if (isAdultFilterActive) {
                    com.focusguard.data.PredefinedApps.PREVENTIVE_APPS
                        .filter { it.category == com.focusguard.data.PredefinedApps.CATEGORY_PORNOGRAPHY }
                        .mapNotNull { it.domain?.lowercase() }
                        .toSet()
                } else emptySet()

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
                    val cal = Calendar.getInstance().apply {
                        set(Calendar.HOUR_OF_DAY, 0)
                        set(Calendar.MINUTE, 0)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    val startOfDay = cal.timeInMillis
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
                    val today = cachedDateFormat.get()!!.format(java.util.Date())
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
                val allBlockedWebsites = activeWebsiteDomains + limitWebsites + adultDomains

                withContext(Dispatchers.Main) {
                    val pomodoroStrict = enforcingSessions.any { it.sessionType == "POMODORO" && it.isBlockingEnabled }
                    isPomodoroStrictActive = pomodoroStrict
                    isBlockingSessionActive = enforcingSessions.isNotEmpty() || limitApps.isNotEmpty() || pomodoroStrict || isAdultFilterActive
                    blockedAppsSet = allBlockedApps
                    blockedWebsitesDomainSet = allBlockedWebsites
                    // Limpa cache O(1) do WebsiteBlocker — blocklist mudou,
                    // URLs em cache podem estar desatualizadas.
                    com.focusguard.utils.WebsiteBlocker.clearCache()
                    lastLoadTime = System.currentTimeMillis()
                }
            } catch (e: Throwable) {
                // Loga erro em vez de silenciar — antes era catch(_: Exception) {}
                // que mascarava falhas de bloqueio sem nenhum rastro.
                com.focusguard.utils.FocusGuardLogger.logError("A11y", "Falha em refreshData", e)
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

        // ─── FAST PATH O(1) — tenta extrair URL diretamente do event ───
        // Muito mais rápido que varrer árvore de nodes (~0ms vs 5-50ms).
        // Firefox Focus, DuckDuckGo e browsers com toolbar custom expõem
        // a URL via event.text ou event.contentDescription.
        val eventUrl = com.focusguard.utils.WebsiteBlocker.extractUrlFromEvent(event)
        if (eventUrl != null && isWebsiteBlocked(eventUrl)) {
            com.focusguard.utils.FocusGuardLogger.log("A11y", "Bloqueado via event URL (fast path): $eventUrl")
            blockWebsite()
            return
        }

        // ─── SLOW PATH — varre árvore de nodes ───
        // Para browsers que não expõem URL no event (Chrome, Edge, Firefox
        // standard), precisamos varrer a árvore para achar o nó url_bar.
        val root = rootInActiveWindow ?: event.source ?: return
        try {
            if (isIncognitoMode(root)) {
                com.focusguard.utils.FocusGuardLogger.log("A11y", "Modo Incognito detectado em $packageName")
                blockWebsite()
                return
            }

            // Verificacao explicita de YouTube (VIP para conter desvios)
            if (isYouTubeContent(root, packageName)) {
                if (blockedWebsitesDomainSet.contains("youtube.com") || blockedWebsitesDomainSet.contains("youtu.be")) {
                    com.focusguard.utils.FocusGuardLogger.log("A11y", "Conteudo YouTube detectado via heuristica de UI")
                    blockWebsite()
                    return
                }
            }

            checkAndBlockWebsite(root)
        } finally {
            root.recycle()
        }
    }

    private fun isYouTubeContent(root: AccessibilityNodeInfo, packageName: String): Boolean {
        // Se for o app nativo do YouTube, ja confirmamos
        if (packageName == "com.google.android.youtube" || packageName == "com.google.android.youtube.tv") return true
        
        // Em navegadores, procuramos por sinais do dominio na UI
        val youtubeSignals = listOf("youtube.com", "m.youtube.com", "youtu.be")
        for (signal in youtubeSignals) {
            val nodes = root.findAccessibilityNodeInfosByText(signal)
            if (nodes.isNotEmpty()) {
                nodes.forEach { it.recycle() }
                return true
            }
        }
        return false
    }

    /**
     * Heuristica para detectar se o conteudo atual pertence a um site bloqueado,
     * mesmo que a barra de enderecos nao seja capturada perfeitamente.
     */
    private fun isBlockedContentInUI(root: AccessibilityNodeInfo): Boolean {
        // Verifica indicadores visuais para sites criticos (como YouTube e redes sociais)
        // Usamos sinais mais especificos (dominios) para evitar falsos positivos em buscas/noticias
        val indicators = mapOf(
            "youtube.com" to listOf("youtube.com", "m.youtube.com", "youtu.be"),
            "facebook.com" to listOf("facebook.com", "fb.com", "facebook.com/"),
            "instagram.com" to listOf("instagram.com", "instagr.am"),
            "tiktok.com" to listOf("tiktok.com", "tiktok.com/"),
            "twitter.com" to listOf("twitter.com", "x.com", "t.co")
        )

        for (domain in blockedWebsitesDomainSet) {
            val key = indicators.keys.find { domain.contains(it) } ?: continue
            val signals = indicators[key] ?: continue
            
            for (signal in signals) {
                val nodes = root.findAccessibilityNodeInfosByText(signal)
                if (nodes.isNotEmpty()) {
                    nodes.forEach { it.recycle() }
                    com.focusguard.utils.FocusGuardLogger.log("A11y", "Conteudo bloqueado '$domain' detectado via sinal UI: $signal")
                    return true
                }
            }
        }
        return false
    }

    /**
     * Tenta extrair o titulo da janela ou conteudo principal para bloqueio secundario.
     */
    private fun checkTitleBlocking(root: AccessibilityNodeInfo): Boolean {
        // Em navegadores, o titulo da janela as vezes contem o nome do site
        // Ex: "YouTube - Chrome"
        for (domain in blockedWebsitesDomainSet) {
            val nodes = root.findAccessibilityNodeInfosByText(domain)
            if (nodes.isNotEmpty()) {
                nodes.forEach { it.recycle() }
                return true
            }
        }
        return false
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

    private fun checkAndBlockWebsite(root: AccessibilityNodeInfo) {
        try {
            // 1. Verificacao da Barra de Enderecos (Metodo Principal)
            val addressBarNode = WebsiteBlocker.findAddressBarNode(root)
            if (addressBarNode != null && addressBarNode.text != null) {
                val url = addressBarNode.text.toString()
                if (url.isNotEmpty() && isWebsiteBlocked(url)) {
                    com.focusguard.utils.FocusGuardLogger.log("A11y", "Bloqueado via URL: $url")
                    blockWebsite()
                    addressBarNode.recycle()
                    return
                }
                addressBarNode.recycle()
            }

            // 2. Verificacao de Heuristica de Conteudo (Metodo Secundario)
            if (isBlockedContentInUI(root)) {
                blockWebsite()
                return
            }

            // 3. Verificacao de Titulo/Texto geral
            if (checkTitleBlocking(root)) {
                com.focusguard.utils.FocusGuardLogger.log("A11y", "Bloqueado via Titulo/Texto UI")
                blockWebsite()
                return
            }
        } catch (e: Exception) {
            com.focusguard.utils.FocusGuardLogger.log("A11y", "Erro em checkAndBlockWebsite: ${e.message}")
        }
    }

    private fun isWebsiteBlocked(url: String): Boolean {
        return try {
            WebsiteBlocker.isUrlBlocked(url, blockedWebsitesDomainSet)
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
            // ─── ESTRATÉGIA 1: Voltar para a página anterior (fecha aba atual) ───
            // GLOBAL_ACTION_BACK simula o botão voltar. Em navegadores, isso
            // volta para a página anterior ou fecha a aba se não houver histórico.
            // Fazemos 2x para garantir que sai da página bloqueada.
            performGlobalAction(GLOBAL_ACTION_BACK)
            try { Thread.sleep(50) } catch (_: Throwable) {}
            performGlobalAction(GLOBAL_ACTION_BACK)

            // ─── ESTRATÉGIA 2: Redirecionar para google.com (nova aba) ───
            // Abre uma URL segura no navegador padrão. Se o navegador já estiver
            // na página bloqueada, o ACTION_VIEW abre nova aba com google.com.
            runCatching {
                val redirectIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.google.com")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                startActivity(redirectIntent)
            }.onFailure { e ->
                com.focusguard.utils.FocusGuardLogger.log("A11y", "Falha ao redirecionar para google.com: ${e.message}")
            }

            // ─── ESTRATÉGIA 3: Mostrar tela de bloqueio FocusGuard ───
            // Overlay por cima de tudo explicando o bloqueio.
            val intent = Intent(this, com.focusguard.ui.BlockNoticeActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                putExtra("STRICT_BLOCK", isPomodoroStrictActive)
            }
            startActivity(intent)

            com.focusguard.utils.FocusGuardLogger.log("A11y", "Site bloqueado: back×2 + redirect google.com + BlockNotice")
        } catch (e: Throwable) {
            com.focusguard.utils.FocusGuardLogger.log("A11y", "Erro ao bloquear browser: ${e.message}")
            // Fallback final: ir para home screen
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
                Toast.makeText(this, getString(R.string.servico_focusguard_parado), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                com.focusguard.utils.FocusGuardLogger.logError("A11y", "Erro critico no onDestroy do AccessibilityService", e)
            }
        }
    }

    companion object {
        const val ACTION_REFRESH_BLOCKING = "com.focusguard.ACTION_REFRESH_BLOCKING"
    }
}