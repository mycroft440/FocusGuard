package com.focusguard

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.focusguard.admin.DeviceOwnerManager
import com.focusguard.manager.BlockingSessionManager
import com.focusguard.ui.PermissionsActivity
import com.focusguard.ui.RecurringSessionActivity
import com.focusguard.ui.TimeSessionActivity
import com.focusguard.ui.compose.screens.BlockingSessionStatusSheet
import com.focusguard.ui.compose.screens.MainScreen
import com.focusguard.ui.compose.screens.UsageStatsScreen
import com.focusguard.ui.compose.theme.FocusGuardTheme
import com.focusguard.utils.PermissionUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private lateinit var deviceOwnerManager: DeviceOwnerManager
    private lateinit var sessionManager: BlockingSessionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("FocusGuardPrefs", Context.MODE_PRIVATE)

        // REGRA NUCLEAR: Imprimir os dados das primeiras 5 tentativas, sem exceção.
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

        setContent {
            FocusGuardTheme {
                MainActivityContent(
                    activity = this,
                    deviceOwnerManager = deviceOwnerManager,
                    sessionManager = sessionManager
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainActivityContent(
    activity: ComponentActivity,
    deviceOwnerManager: DeviceOwnerManager,
    sessionManager: BlockingSessionManager
) {
    var permissionsVisible by remember { mutableStateOf(false) }
    var showSessionSheet by remember { mutableStateOf(false) }
    var isBlocking by remember { mutableStateOf(false) }
    var hasSession by remember { mutableStateOf(false) }
    var sessionDetails by remember { mutableStateOf("") }

    // Refresh permission state on resume
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

    // Auto-refresh when returning to activity
    DisposableEffect(Unit) {
        val callback = object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onResume(owner: androidx.lifecycle.LifecycleOwner) {
                resumeKey++
            }
        }
        activity.lifecycle.addObserver(callback)
        onDispose { activity.lifecycle.removeObserver(callback) }
    }

    // Session sheet auto-update
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

    MainScreen(
        permissionsVisible = permissionsVisible,
        onPermissionsClick = {
            activity.startActivity(Intent(activity, PermissionsActivity::class.java))
        },
        onTimeSessionClick = {
            activity.startActivity(Intent(activity, TimeSessionActivity::class.java))
        },
        onRecurringSessionClick = {
            activity.startActivity(Intent(activity, RecurringSessionActivity::class.java))
        },
        onActiveSessionsClick = {
            showSessionSheet = true
        },
        onDeviceOwnerClick = {
            deviceOwnerManager.setAsDeviceOwner()
        },
        usageStatsContent = { UsageStatsScreen() }
    )

    // Session Status Bottom Sheet
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
}
