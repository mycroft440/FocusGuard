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

class MainActivity : AppCompatActivity() {

    private lateinit var deviceOwnerManager: DeviceOwnerManager
    private lateinit var sessionManager: BlockingSessionManager
    private lateinit var authManager: AuthManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("FocusGuardPrefs", Context.MODE_PRIVATE)
        val attemptCount = prefs.getInt("launchAttemptCount", 0) + 1
        prefs.edit().putInt("launchAttemptCount", attemptCount).apply()

        if (!prefs.getBoolean("hasSeenOnboarding", false)) {
            startActivity(Intent(this, PermissionsActivity::class.java))
            finish()
            return
        }

        deviceOwnerManager = DeviceOwnerManager(this)
        sessionManager = BlockingSessionManager.getInstance(this)
        authManager = AuthManager(this)

        setContent {
            FocusGuardTheme {
                MainActivityContent(
                    activity = this,
                    deviceOwnerManager = deviceOwnerManager,
                    sessionManager = sessionManager,
                    authManager = authManager
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
    authManager: AuthManager
) {
    var isUnlocked by remember { mutableStateOf(true) } // Assume unlocked initially to prevent flicker, or false for security
    LaunchedEffect(Unit) {
        isUnlocked = !authManager.isAppLocked()
    }

    if (!isUnlocked) {
        AuthScreen(
            authManager = authManager,
            activity = activity,
            onUnlock = { isUnlocked = true }
        )
        return
    }

    var currentRoute by remember { mutableStateOf("HOME") }
    var permissionsVisible by remember { mutableStateOf(false) }
    var showSessionSheet by remember { mutableStateOf(false) }
    
    val isBlocking by sessionManager.isBlockingActiveFlow.collectAsState(initial = false)
    val hasSession by sessionManager.hasRegisteredSessionFlow.collectAsState(initial = false)
    val sessionDetails by sessionManager.sessionDetailsFlow.collectAsState(initial = "Carregando...")

    val database = AppDatabase.getDatabase(activity)
    val stats by database.dailyUsageStatDao().getStatsForDate(java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())).collectAsState(initial = emptyList())

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
                        val intent = Intent(activity, com.focusguard.ui.CreateSessionActivity::class.java).apply { putExtra("SESSION_TYPE", "PASSWORD") }
                        activity.startActivity(intent)
                    },
                    onTimeSessionClick = { 
                        val intent = Intent(activity, com.focusguard.ui.CreateSessionActivity::class.java).apply { putExtra("SESSION_TYPE", "TIME") }
                        activity.startActivity(intent)
                    },
                    onActiveSessionsClick = { showSessionSheet = true },
                    onDeviceOwnerClick = { deviceOwnerManager.setAsDeviceOwner() },
                    onLimitsClick = { currentRoute = "LIMITS" },
                    onIntruderLogClick = { currentRoute = "INTRUDER_LOG" },
                    onLanguageClick = { currentRoute = "LANGUAGE" },
                    onPasswordManagementClick = { currentRoute = "PASSWORD_MANAGEMENT" },
                    onBlockCustomizationClick = { currentRoute = "DASHBOARD" }, // Usando para o Dashboard por enquanto
                    onAppUsageLimitsClick = { currentRoute = "USAGE_LIMITS" },
                    authManager = authManager,
                    usageStatsContent = { UsageStatsScreen() }
                )
                "LIMITS" -> LimitsSecurityScreen(authManager = authManager, onBack = { currentRoute = "HOME" })
                "INTRUDER_LOG" -> IntruderLogScreen(onBack = { currentRoute = "HOME" })
                "LANGUAGE" -> LanguageScreen(onBack = { currentRoute = "HOME" })
                "PASSWORD_MANAGEMENT" -> PasswordManagementScreen(authManager = authManager, onBack = { currentRoute = "HOME" })
                "USAGE_LIMITS" -> UsageLimitsScreen(authManager = authManager, onBack = { currentRoute = "HOME" })
                "DASHBOARD" -> UsageStatsDashboardScreen(stats = stats, onBack = { currentRoute = "HOME" })
            }
        }
    }

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
                onRenounce = {
                    if (!hasSession) {
                        try {
                            deviceOwnerManager.renounceDeviceOwner()
                        } catch (_: Exception) {}
                    }
                },
                onEndSessions = {
                    sessionManager.endPasswordSessions()
                    showSessionSheet = false
                },
                onDismiss = { showSessionSheet = false },
                authManager = authManager
            )
        }
    }
}