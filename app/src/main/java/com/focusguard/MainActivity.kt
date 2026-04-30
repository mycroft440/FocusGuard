package com.focusguard

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import com.focusguard.admin.DeviceOwnerManager
import com.focusguard.manager.BlockingSessionManager
import com.focusguard.ui.PermissionsActivity
import com.focusguard.ui.compose.screens.*
import com.focusguard.ui.compose.theme.FocusGuardTheme
import com.focusguard.utils.PermissionUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.appcompat.app.AppCompatActivity
import com.focusguard.security.AuthManager
import com.focusguard.database.AppDatabase
import com.focusguard.utils.FocusGuardLogger
import com.focusguard.manager.PomodoroManager

class MainActivity : AppCompatActivity() {

    private lateinit var deviceOwnerManager: DeviceOwnerManager
    private lateinit var sessionManager: BlockingSessionManager
    private lateinit var authManager: AuthManager
    private lateinit var pomodoroManager: PomodoroManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FocusGuardLogger.init(this)
        FocusGuardLogger.log("MainActivity", "onCreate disparado")

        val prefs = getSharedPreferences("FocusGuardPrefs", Context.MODE_PRIVATE)
        val attemptCount = prefs.getInt("launchAttemptCount", 0) + 1
        prefs.edit().putInt("launchAttemptCount", attemptCount).apply()

        if (!prefs.getBoolean("hasSeenOnboarding", false)) {
            startActivity(Intent(this, PermissionsActivity::class.java))
            finish()
            return
        }

        deviceOwnerManager = DeviceOwnerManager(applicationContext)
        sessionManager = BlockingSessionManager.getInstance(applicationContext)
        authManager = AuthManager(applicationContext)
        pomodoroManager = PomodoroManager.getInstance(applicationContext)
        
        FocusGuardLogger.log("MainActivity", "Managers inicializados com sucesso")

        setContent {
            FocusGuardTheme {
                MainActivityContent(
                    activity = this,
                    deviceOwnerManager = deviceOwnerManager,
                    sessionManager = sessionManager,
                    authManager = authManager,
                    pomodoroManager = pomodoroManager
                )
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        FocusGuardLogger.log("MainActivity", "onResume disparado")
    }

    override fun onPause() {
        super.onPause()
        FocusGuardLogger.log("MainActivity", "onPause disparado")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainActivityContent(
    activity: AppCompatActivity,
    deviceOwnerManager: DeviceOwnerManager,
    sessionManager: BlockingSessionManager,
    authManager: AuthManager,
    pomodoroManager: PomodoroManager
) {
    var isUnlocked by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val locked = authManager.isAppLocked()
            withContext(Dispatchers.Main) {
                isUnlocked = !locked
            }
        }
    }

    if (isUnlocked == null) {
        Box(modifier = Modifier.fillMaxSize().background(com.focusguard.ui.compose.theme.DarkBg))
        return
    }

    if (isUnlocked == false) {
        AuthScreen(
            authManager = authManager,
            activity = activity,
            onUnlock = { isUnlocked = true }
        )
        return
    }

    var currentRoute by remember { mutableStateOf("HOME") }
    var selectedSessionType by remember { mutableStateOf("PASSWORD") }
    var permissionsVisible by remember { mutableStateOf(false) }
    
    val currentPomodoro by pomodoroManager.currentSession.collectAsState()
    
    LaunchedEffect(currentPomodoro) {
        if (currentPomodoro?.isActive == true) {
            val now = System.currentTimeMillis()
            if (currentPomodoro!!.endTime <= now) {
                FocusGuardLogger.log("MainActivity", "Detectado Pomodoro expirado na abertura. Limpando...")
                pomodoroManager.stopSession()
                currentRoute = "HOME"
            } else {
                FocusGuardLogger.log("MainActivity", "Pomodoro ativo detectado. Redirecionando para tela de foco.")
                currentRoute = "POMODORO"
            }
        } else {
            if (currentRoute == "POMODORO") {
                FocusGuardLogger.log("MainActivity", "Pomodoro inativado. Voltando para Home.")
                currentRoute = "HOME"
            }
        }
    }
    val hasSession by sessionManager.hasRegisteredSessionFlow.collectAsState(initial = false)
    val sessionDetails by sessionManager.sessionDetailsFlow.collectAsState(initial = "Carregando...")

    val database = remember { AppDatabase.getDatabase(activity.applicationContext) }
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

    if (currentPomodoro?.isActive == true) {
        val now = System.currentTimeMillis()
        if (currentPomodoro!!.endTime > now) {
            FocusGuardLogger.log("MainActivity", "Pomodoro Ativo detectado na UI. Exibindo tela de foco.")
            Box(modifier = Modifier.fillMaxSize().background(com.focusguard.ui.compose.theme.DarkBg)) {
                PomodoroScreen(pomodoroManager = pomodoroManager, authManager = authManager, onBack = { /* Bloqueado */ })
            }
            return
        }
    }

    Surface(color = MaterialTheme.colorScheme.background) {
        AnimatedContent(
            targetState = currentRoute,
            transitionSpec = {
                fadeIn(animationSpec = tween(300)) + slideInHorizontally(animationSpec = tween(300), initialOffsetX = { it }) togetherWith
                fadeOut(animationSpec = tween(300)) + slideOutHorizontally(animationSpec = tween(300), targetOffsetX = { -it })
            },
            label = "NavigationTransition"
        ) { route ->
            when (route) {
                "HOME" -> MainScreen(
                    permissionsVisible = permissionsVisible,
                    onPermissionsClick = { activity.startActivity(Intent(activity, PermissionsActivity::class.java)) },
                    onPasswordSessionClick = { 
                        selectedSessionType = "PASSWORD"
                        currentRoute = "SESSIONS_LIST"
                    },
                    onTimeSessionClick = { 
                        selectedSessionType = "TIME"
                        currentRoute = "SESSIONS_LIST"
                    },
                    onDeviceOwnerClick = { deviceOwnerManager.setAsDeviceOwner() },
                    onLimitsClick = { currentRoute = "LIMITS" },
                    onIntruderLogClick = { currentRoute = "INTRUDER_LOG" },
                    onLanguageClick = { currentRoute = "LANGUAGE" },
                    onPasswordManagementClick = { currentRoute = "PASSWORD_MANAGEMENT" },
                    onBlockCustomizationClick = { currentRoute = "BLOCK_CUSTOMIZATION" },
                    onAppUsageLimitsClick = { currentRoute = "USAGE_LIMITS" },
                    authManager = authManager,
                    usageStatsContent = { UsageStatsDashboardScreen(onBack = {}) },
                    pomodoroContent = { PomodoroScreen(pomodoroManager = pomodoroManager, authManager = authManager, onBack = { currentRoute = "HOME" }) }
                )
                "POMODORO" -> PomodoroScreen(pomodoroManager = pomodoroManager, authManager = authManager, onBack = { currentRoute = "HOME" })
                "LIMITS" -> LimitsSecurityScreen(authManager = authManager, onBack = { currentRoute = "HOME" })
                "INTRUDER_LOG" -> IntruderLogScreen(onBack = { currentRoute = "HOME" })
                "LANGUAGE" -> LanguageScreen(onBack = { currentRoute = "HOME" })
                "PASSWORD_MANAGEMENT" -> PasswordManagementScreen(authManager = authManager, onBack = { currentRoute = "HOME" })
                "USAGE_LIMITS" -> UsageLimitsScreen(authManager = authManager, onBack = { currentRoute = "HOME" })
                "DASHBOARD" -> UsageStatsDashboardScreen(onBack = { currentRoute = "HOME" })
                "BLOCK_CUSTOMIZATION" -> BlockCustomizationScreen(onBack = { currentRoute = "HOME" })
                "SESSIONS_LIST" -> SessionsListScreen(sessionType = selectedSessionType, onBack = { currentRoute = "HOME" })
            }
        }
    }
}