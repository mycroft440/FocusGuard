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
                var currentRoute by remember { 
                    mutableStateOf(if (prefs.getBoolean("hasSeenOnboarding", false)) "HOME" else "PERMISSIONS") 
                }
                var sessionTypeToCreate by remember { mutableStateOf("PASSWORD") }

                MainActivityContent(
                    activity = this,
                    deviceOwnerManager = deviceOwnerManager,
                    sessionManager = sessionManager,
                    authManager = authManager,
                    currentRoute = currentRoute,
                    onNavigate = { route -> currentRoute = route },
                    onStartCreateSession = { type -> 
                        sessionTypeToCreate = type
                        currentRoute = "CREATE_SESSION"
                    },
                    sessionTypeToCreate = sessionTypeToCreate
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
    onStartCreateSession: (String) -> Unit,
    sessionTypeToCreate: String
) {
    var isUnlocked by remember { mutableStateOf(!authManager.isAppLocked()) }

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
                onStartCreateSession = onStartCreateSession
            )
        }
        "CREATE_SESSION" -> {
            com.focusguard.ui.CreateSessionWizard(
                sessionType = sessionTypeToCreate,
                onFinish = { onNavigate("HOME") }
            )
        }
        "LIMITS" -> {
            LimitsSecurityScreen(
                authManager = authManager,
                onBack = { onNavigate("HOME") }
            )
        }
        "INTRUDER_LOG" -> {
            IntruderLogScreen(
                onBack = { onNavigate("HOME") }
            )
        }
        "LANGUAGE" -> {
            LanguageScreen(
                onBack = { onNavigate("HOME") }
            )
        }
        "PASSWORD_MANAGEMENT" -> {
            PasswordManagementScreen(
                authManager = authManager,
                onBack = { onNavigate("HOME") }
            )
        }
        "APP_USAGE_LIMITS" -> {
            com.focusguard.ui.compose.screens.UsageLimitsScreen(
                authManager = authManager,
                onBack = { onNavigate("HOME") }
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
    onStartCreateSession: (String) -> Unit
) {
    var permissionsVisible by remember { mutableStateOf(false) }
    var showSessionSheet by remember { mutableStateOf(false) }
    var showTimeSessionAlert by remember { mutableStateOf(false) }
    var isBlocking by remember { mutableStateOf(false) }
    var hasSession by remember { mutableStateOf(false) }
    var sessionDetails by remember { mutableStateOf("") }
    var sessionApps by remember { mutableStateOf<List<String>>(emptyList()) }
    var sessionSites by remember { mutableStateOf<List<String>>(emptyList()) }
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

    LaunchedEffect(showSessionSheet) {
        while (showSessionSheet) {
            withContext(Dispatchers.IO) {
                isBlocking = sessionManager.isBlockingActive()
                hasSession = sessionManager.hasRegisteredSession()
                sessionDetails = sessionManager.getSessionDetails()
                
                val sessions = sessionManager.getActiveSessions()
                val sessionIds = sessions.map { it.id }
                if (sessionIds.isNotEmpty()) {
                    val db = com.focusguard.database.AppDatabase.getDatabase(activity)
                    val apps = db.sessionAppCrossRefDao().getAppsForSessions(sessionIds).distinct()
                    val sites = db.sessionWebsiteCrossRefDao().getWebsitesForSessions(sessionIds).distinct()
                    sessionApps = apps
                    sessionSites = sites
                } else {
                    sessionApps = emptyList()
                    sessionSites = emptyList()
                }
            }
            delay(2000)
        }
    }

    MainScreen(
        permissionsVisible = permissionsVisible,
        onPermissionsClick = { onNavigate("PERMISSIONS") },
        onPasswordSessionClick = { onStartCreateSession("PASSWORD") },
        onTimeSessionClick = {
            scope.launch(Dispatchers.IO) {
                val hasTimeSession = sessionManager.hasTimeSession()
                withContext(Dispatchers.Main) {
                    if (hasTimeSession) {
                        showTimeSessionAlert = true
                        com.focusguard.utils.FocusGuardLogger.log("HomeScreen", "Criação de bloqueio por tempo abortada: já existe sessão ativa")
                    } else {
                        onStartCreateSession("TIME")
                    }
                }
            }
        },
        onActiveSessionsClick = { showSessionSheet = true },
        onDeviceOwnerClick = { deviceOwnerManager.setAsDeviceOwner() },
        onLimitsClick = { onNavigate("LIMITS") },
        onIntruderLogClick = { onNavigate("INTRUDER_LOG") },
        onLanguageClick = { onNavigate("LANGUAGE") },
        onPasswordManagementClick = { onNavigate("PASSWORD_MANAGEMENT") },
        onAppUsageLimitsClick = { onNavigate("APP_USAGE_LIMITS") },
        authManager = authManager,
        usageStatsContent = { UsageStatsScreen() }
    )

    if (showSessionSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSessionSheet = false },
            containerColor = com.focusguard.ui.compose.theme.DarkSurface,
            dragHandle = { BottomSheetDefaults.DragHandle(color = com.focusguard.ui.compose.theme.TextHint) }
        ) {
            BlockingSessionStatusSheet(
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
                onDismiss = { showSessionSheet = false }
            )
        }
    }

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
