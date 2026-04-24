package com.focusguard

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.focusguard.admin.DeviceOwnerManager
import com.focusguard.manager.BlockingSessionManager
import com.focusguard.ui.PermissionsActivity
import com.focusguard.ui.compose.screens.BlockingSessionStatusSheet
import com.focusguard.ui.compose.screens.MainScreen
import com.focusguard.ui.compose.screens.UsageStatsScreen
import com.focusguard.ui.compose.theme.FocusGuardTheme
import com.focusguard.utils.PermissionUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

import androidx.appcompat.app.AppCompatActivity
import com.focusguard.security.AuthManager

class MainActivity : AppCompatActivity() {

    private lateinit var deviceOwnerManager: DeviceOwnerManager
    private lateinit var sessionManager: BlockingSessionManager
    private lateinit var authManager: AuthManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("FocusGuardPrefs", Context.MODE_PRIVATE)

        val attemptCount = prefs.getInt("launchAttemptCount", 0) + 1
        prefs.edit().putInt("launchAttemptCount", attemptCount).apply()

        if (attemptCount <= 5) {
            Log.d("FocusGuardNuclear", "========================================")
            Log.d("FocusGuardNuclear", "OPÇÃO NUCLEAR: Inicializando o FocusGuard v2")
            Log.d("FocusGuardNuclear", "Tentativa de inicialização: $attemptCount")
            Log.d("FocusGuardNuclear", "Pacote ativo: $packageName")
            Log.d("FocusGuardNuclear", "========================================")
        }

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
    var isUnlocked by remember { mutableStateOf(!authManager.isAppLocked()) }

    if (!isUnlocked) {
        com.focusguard.ui.compose.screens.AuthScreen(
            authManager = authManager,
            activity = activity,
            onUnlock = { isUnlocked = true }
        )
        return
    }

    var currentRoute by remember { mutableStateOf("HOME") }

    var permissionsVisible by remember { mutableStateOf(false) }
    var showSessionSheet by remember { mutableStateOf(false) }
    var isBlocking by remember { mutableStateOf(false) }
    var hasSession by remember { mutableStateOf(false) }
    var sessionDetails by remember { mutableStateOf("") }

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
            }
            delay(2000)
        }
    }

    if (currentRoute == "HOME") {
        MainScreen(
            permissionsVisible = permissionsVisible,
            onPermissionsClick = {
                activity.startActivity(Intent(activity, PermissionsActivity::class.java))
            },
            onPasswordSessionClick = {
                val intent = Intent(activity, com.focusguard.ui.CreateSessionActivity::class.java)
                intent.putExtra("SESSION_TYPE", "PASSWORD")
                activity.startActivity(intent)
            },
            onTimeSessionClick = {
                val intent = Intent(activity, com.focusguard.ui.CreateSessionActivity::class.java)
                intent.putExtra("SESSION_TYPE", "TIME")
                activity.startActivity(intent)
            },
            onActiveSessionsClick = {
                showSessionSheet = true
            },
            onDeviceOwnerClick = {
                deviceOwnerManager.setAsDeviceOwner()
            },
            onLimitsClick = { currentRoute = "LIMITS" },
            onIntruderLogClick = { currentRoute = "INTRUDER_LOG" },
            onLanguageClick = { currentRoute = "LANGUAGE" },
            onPasswordManagementClick = { currentRoute = "PASSWORD_MANAGEMENT" },
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
    } else if (currentRoute == "LIMITS") {
        com.focusguard.ui.compose.screens.LimitsSecurityScreen(
            authManager = authManager,
            onBack = { currentRoute = "HOME" }
        )
    } else if (currentRoute == "INTRUDER_LOG") {
        com.focusguard.ui.compose.screens.IntruderLogScreen(
            onBack = { currentRoute = "HOME" }
        )
    } else if (currentRoute == "LANGUAGE") {
        com.focusguard.ui.compose.screens.LanguageScreen(
            onBack = { currentRoute = "HOME" }
        )
    } else if (currentRoute == "PASSWORD_MANAGEMENT") {
        com.focusguard.ui.compose.screens.PasswordManagementScreen(
            authManager = authManager,
            onBack = { currentRoute = "HOME" }
        )
    }
}
