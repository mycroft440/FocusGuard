package com.focusguard

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.focusguard.admin.DeviceOwnerManager
import com.focusguard.manager.BlockingSessionManager
import com.focusguard.ui.compose.screens.*
import com.focusguard.ui.compose.theme.FocusGuardTheme
import com.focusguard.utils.PermissionUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.appcompat.app.AppCompatActivity
import com.focusguard.security.AuthManager

class MainActivity : AppCompatActivity() {

    private lateinit var deviceOwnerManager: DeviceOwnerManager
    private lateinit var sessionManager: BlockingSessionManager
    private lateinit var authManager: AuthManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.focusguard.utils.FocusGuardLogger.init(applicationContext)
        com.focusguard.utils.FocusGuardLogger.log("MainActivity", "App iniciado")

        val prefs = getSharedPreferences("FocusGuardPrefs", Context.MODE_PRIVATE)
        deviceOwnerManager = DeviceOwnerManager(this)
        sessionManager = BlockingSessionManager.getInstance(this)
        authManager = AuthManager(this)

        setContent {
            FocusGuardTheme {
                var routeStack by remember { 
                    mutableStateOf(listOf(if (prefs.getBoolean("hasSeenOnboarding", false)) "HOME" else "PERMISSIONS")) 
                }
                var sessionTypeToCreate by remember { mutableStateOf("PASSWORD") }
                var sessionTypeForDetail by remember { mutableStateOf("PASSWORD") }

                val currentRoute = routeStack.last()

                val onNavigate = { route: String -> 
                    routeStack = routeStack + route 
                }

                val onBack = {
                    if (routeStack.size > 1) {
                        routeStack = routeStack.dropLast(1)
                    } else {
                        finish()
                    }
                }

                MainActivityContent(
                    activity = this,
                    deviceOwnerManager = deviceOwnerManager,
                    sessionManager = sessionManager,
                    authManager = authManager,
                    currentRoute = currentRoute,
                    onNavigate = onNavigate,
                    onBack = onBack,
                    onStartCreateSession = { type -> 
                        sessionTypeToCreate = type
                        onNavigate("CREATE_SESSION")
                    },
                    sessionTypeToCreate = sessionTypeToCreate,
                    sessionTypeForDetail = sessionTypeForDetail,
                    onSetSessionTypeForDetail = { sessionTypeForDetail = it }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainActivityContent(
    activity: AppCompatActivity,
    deviceOwnerManager: DeviceOwnerManager,
    sessionManager: BlockingSessionManager,
    authManager: AuthManager,
    currentRoute: String,
    onNavigate: (String) -> Unit,
    onBack: () -> Unit,
    onStartCreateSession: (String) -> Unit,
    sessionTypeToCreate: String,
    sessionTypeForDetail: String,
    onSetSessionTypeForDetail: (String) -> Unit
) {
    var isUnlocked by remember { mutableStateOf(!authManager.isAppLocked()) }

    androidx.activity.compose.BackHandler(enabled = true) {
        onBack()
    }

    // Auth Guard
    if (!isUnlocked && currentRoute != "PERMISSIONS") {
        AuthScreen(
            authManager = authManager,
            activity = activity,
            onUnlock = { isUnlocked = true }
        )
        return
    }

    // Navigation Router
    when (currentRoute) {
        "PERMISSIONS" -> {
            PermissionsScreen(
                onFinish = { 
                    activity.getSharedPreferences("FocusGuardPrefs", Context.MODE_PRIVATE)
                        .edit().putBoolean("hasSeenOnboarding", true).apply()
                    onNavigate("HOME") 
                }
            )
        }
        "HOME" -> {
            HomeScreen(
                activity = activity,
                deviceOwnerManager = deviceOwnerManager,
                sessionManager = sessionManager,
                authManager = authManager,
                onNavigate = onNavigate,
                onStartCreateSession = onStartCreateSession,
                onSetSessionTypeForDetail = onSetSessionTypeForDetail
            )
        }
        "CREATE_SESSION" -> {
            com.focusguard.ui.CreateSessionWizard(
                sessionType = sessionTypeToCreate,
                onFinish = onBack
            )
        }
        "LIMITS" -> {
            LimitsSecurityScreen(
                authManager = authManager,
                onBack = onBack
            )
        }
        "INTRUDER_LOG" -> {
            IntruderLogScreen(
                onBack = onBack
            )
        }
        "LANGUAGE" -> {
            LanguageScreen(
                onBack = onBack
            )
        }
        "PASSWORD_MANAGEMENT" -> {
            PasswordManagementScreen(
                authManager = authManager,
                onBack = onBack
            )
        }
        "APP_USAGE_LIMITS" -> {
            com.focusguard.ui.compose.screens.UsageLimitsScreen(
                authManager = authManager,
                onBack = onBack
            )
        }
        "BLOCK_CUSTOMIZATION" -> {
            com.focusguard.ui.compose.screens.BlockCustomizationScreen(
                onBack = onBack
            )
        }
        "SESSION_DETAIL" -> {
            com.focusguard.ui.compose.screens.SessionDetailScreen(
                sessionType = sessionTypeForDetail,
                onBack = onBack,
                onAddNewBlock = {
                    onStartCreateSession(sessionTypeForDetail)
                }
            )
        }
        "ACTIVE_SESSIONS" -> {
            val sessions by sessionManager.getActiveSessionsFlow().collectAsState(initial = emptyList())
            val sessionApps by sessionManager.getBlockedAppsFlow().collectAsState(initial = emptyList())
            val sessionSites by sessionManager.getBlockedWebsitesFlow().collectAsState(initial = emptyList())
            
            val isBlocking = remember(sessions) {
                sessions.any { sessionManager.isCurrentlyInBlockingWindow(it) }
            }
            val hasSession = sessions.isNotEmpty()
            
            // Detalhes formatados (recalculados quando as sessões mudam)
            val sessionDetails = remember(sessions) {
                if (sessions.isEmpty()) return@remember "Nenhuma sessão ativa"
                val dateFormatter = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault())

                buildString {
                    appendLine("=== Sessões Ativas (${sessions.size}) ===")
                    sessions.forEachIndexed { index, session ->
                        appendLine("Sessão #${index + 1} (${session.sessionType})")
                        if (session.isFixed24h) {
                            appendLine("Modo: FIXO 24H")
                        } else {
                            appendLine("Modo: AGENDADO")
                            appendLine("Entre: ${String.format(java.util.Locale.getDefault(), "%02d:%02d", session.recurringStartHour, session.recurringStartMinute)} e ${String.format(java.util.Locale.getDefault(), "%02d:%02d", session.recurringEndHour, session.recurringEndMinute)}")
                        }
                        if (session.sessionType == "TIME" && session.endTime != null) {
                            appendLine("Término do Tempo: ${dateFormatter.format(session.endTime)}")
                        }
                        appendLine("---")
                    }
                }
            }

            // OPÇÃO NUCLEAR: Log das primeiras 5 tentativas
            LaunchedEffect(Unit) {
                withContext(Dispatchers.IO) {
                    val db = com.focusguard.database.AppDatabase.getDatabase(activity)
                    val sessions = db.blockSessionDao().getAllSessions()
                    com.focusguard.utils.FocusGuardLogger.log("NUCLEAR_OPTION", "--- INÍCIO DOS DADOS (Primeiras 5 Sessões/Tentativas) ---")
                    sessions.take(5).forEachIndexed { index, session ->
                        com.focusguard.utils.FocusGuardLogger.log("NUCLEAR_OPTION", "Sessão #${index + 1}: ID=${session.id}, Tipo=${session.sessionType}, Ativa=${session.isActive}")
                    }
                    
                    val appLimits = db.appUsageLimitDao().getAll().take(5)
                    com.focusguard.utils.FocusGuardLogger.log("NUCLEAR_OPTION", "--- LIMITES DE APPS (Primeiros 5) ---")
                    appLimits.forEach { limit ->
                        com.focusguard.utils.FocusGuardLogger.log("NUCLEAR_OPTION", "App: ${limit.packageName}, Limite: ${limit.dailyLimitMinutes}min, Ativo=${limit.isEnabled}")
                    }

                    val siteLimits = db.websiteUsageLimitDao().getAll().take(5)
                    com.focusguard.utils.FocusGuardLogger.log("NUCLEAR_OPTION", "--- LIMITES DE SITES (Primeiros 5) ---")
                    siteLimits.forEach { limit ->
                        com.focusguard.utils.FocusGuardLogger.log("NUCLEAR_OPTION", "Site: ${limit.domain}, Limite: ${limit.dailyLimitMinutes}min, Ativo=${limit.isEnabled}")
                    }

                    val inactive = sessions.filter { !it.isActive }.takeLast(5)
                    if (inactive.isNotEmpty()) {
                        com.focusguard.utils.FocusGuardLogger.log("NUCLEAR_OPTION", "--- SESSÕES INATIVAS RECENTES (Auditoria de Expiração) ---")
                        inactive.forEach { session ->
                            com.focusguard.utils.FocusGuardLogger.log("NUCLEAR_OPTION", "Inativa: ID=${session.id}, Tipo=${session.sessionType}")
                        }
                    }
                    com.focusguard.utils.FocusGuardLogger.log("NUCLEAR_OPTION", "--- FIM DOS DADOS ---")
                }
            }
            
            ActiveSessionsScreen(
                isBlocking = isBlocking,
                hasSession = hasSession,
                details = sessionDetails,
                apps = sessionApps,
                sites = sessionSites,
                onRenounce = {
                    if (!hasSession) {
                        try {
                            deviceOwnerManager.renounceDeviceOwner()
                        } catch (_: Exception) {}
                    }
                },
                onBack = onBack
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    activity: AppCompatActivity,
    deviceOwnerManager: DeviceOwnerManager,
    sessionManager: BlockingSessionManager,
    authManager: AuthManager,
    onNavigate: (String) -> Unit,
    onStartCreateSession: (String) -> Unit,
    onSetSessionTypeForDetail: (String) -> Unit
) {
    var permissionsVisible by remember { mutableStateOf(false) }
    var showTimeSessionAlert by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    var resumeKey by remember { mutableIntStateOf(0) }
    
    LaunchedEffect(resumeKey) {
        withContext(Dispatchers.IO) {
            val isA11yEnabled = PermissionUtils.isAccessibilityServiceEnabled(activity)
            val isAdminActive = deviceOwnerManager.isDeviceAdminActive() || deviceOwnerManager.isDeviceOwnerActive()
            val isUsageAccessEnabled = PermissionUtils.isUsageAccessEnabled(activity)
            val isBatteryIgnored = PermissionUtils.isBatteryOptimizationIgnored(activity)
            withContext(Dispatchers.Main) {
                permissionsVisible = !isA11yEnabled || !isAdminActive || !isUsageAccessEnabled || !isBatteryIgnored
            }
        }
    }

    DisposableEffect(Unit) {
        val callback = object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onResume(owner: androidx.lifecycle.LifecycleOwner) {
                resumeKey++
            }
        }
        activity.lifecycle.addObserver(callback)
        onDispose { activity.lifecycle.removeObserver(callback) }
    }

    MainScreen(
        permissionsVisible = permissionsVisible,
        onPermissionsClick = { onNavigate("PERMISSIONS") },
        onPasswordSessionClick = { 
            onSetSessionTypeForDetail("PASSWORD")
            onNavigate("SESSION_DETAIL")
        },
        onTimeSessionClick = {
            onSetSessionTypeForDetail("TIME")
            onNavigate("SESSION_DETAIL")
        },
        onActiveSessionsClick = { onNavigate("ACTIVE_SESSIONS") },
        onDeviceOwnerClick = { deviceOwnerManager.setAsDeviceOwner() },
        onLimitsClick = { onNavigate("LIMITS") },
        onIntruderLogClick = { onNavigate("INTRUDER_LOG") },
        onLanguageClick = { onNavigate("LANGUAGE") },
        onPasswordManagementClick = { onNavigate("PASSWORD_MANAGEMENT") },
        onBlockCustomizationClick = { onNavigate("BLOCK_CUSTOMIZATION") },
        onAppUsageLimitsClick = { onNavigate("APP_USAGE_LIMITS") },
        authManager = authManager,
        usageStatsContent = { UsageStatsScreen() }
    )

    if (showTimeSessionAlert) {
        AlertDialog(
            onDismissRequest = { showTimeSessionAlert = false },
            title = { Text("Acesso Negado", color = com.focusguard.ui.compose.theme.TextPrimary, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) },
            text = { Text("Você já tem um bloqueio por tempo ativo. Aguarde o término da sessão atual para configurar um novo limite.", color = com.focusguard.ui.compose.theme.TextSecondary) },
            confirmButton = {
                TextButton(onClick = { showTimeSessionAlert = false }) {
                    Text("OK", color = com.focusguard.ui.compose.theme.AccentCyan)
                }
            },
            containerColor = com.focusguard.ui.compose.theme.DarkSurface
        )
    }
}
