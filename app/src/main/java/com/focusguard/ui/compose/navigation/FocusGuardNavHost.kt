package com.focusguard.ui.compose.navigation

import androidx.compose.runtime.*
import android.content.Intent
import androidx.compose.ui.res.stringResource
import com.focusguard.R
import androidx.activity.compose.BackHandler
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.focusguard.manager.PomodoroManager
import com.focusguard.security.AuthManager
import com.focusguard.ui.CreateSessionActivity
import com.focusguard.ui.OfflineBookActivity
import com.focusguard.ui.PermissionsActivity
import com.focusguard.ui.compose.screens.AuthScreen
import com.focusguard.ui.compose.screens.BlockCustomizationScreen
import com.focusguard.ui.compose.screens.BlockTypeDetailScreen
import com.focusguard.ui.compose.screens.BlockTypeUi
import com.focusguard.ui.compose.screens.IntruderLogScreen
import com.focusguard.ui.compose.screens.LanguageScreen
import com.focusguard.ui.compose.screens.LimitsSecurityScreen
import com.focusguard.ui.compose.screens.MainScreen
import com.focusguard.ui.compose.screens.PomodoroScreen
import com.focusguard.ui.compose.screens.RecoveryBook
import com.focusguard.ui.compose.screens.RecoveryHubScreen
import com.focusguard.ui.compose.screens.SessionsListScreen
import com.focusguard.ui.compose.screens.SettingsScreen
import com.focusguard.ui.compose.screens.UsageLimitsScreen
import com.focusguard.ui.compose.screens.UsageStatsDashboardScreen
import com.focusguard.ui.compose.theme.DarkBg
import com.focusguard.utils.FocusGuardLogger
import com.focusguard.utils.PermissionUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private object FocusGuardRoute {
    const val Home = "HOME"
    const val Settings = "SETTINGS"
    const val Pomodoro = "POMODORO"
    const val Limits = "LIMITS"
    const val IntruderLog = "INTRUDER_LOG"
    const val Language = "LANGUAGE"
    const val UsageLimits = "USAGE_LIMITS"
    const val Dashboard = "DASHBOARD"
    const val BlockCustomization = "BLOCK_CUSTOMIZATION"
    const val SessionsList = "SESSIONS_LIST"
    const val BlockTypeDetail = "BLOCK_TYPE_DETAIL"
}

@Composable
fun FocusGuardNavHost(
    activity: AppCompatActivity,
    authManager: AuthManager,
    pomodoroManager: PomodoroManager
) {
    var isUnlocked by remember { mutableStateOf<Boolean?>(null) }
    // Reavalia a trava em toda volta ao primeiro plano. Isso inclui o retorno
    // do assistente logo após criar um bloqueio por senha.
    LaunchedEffect(resumeKey) {
        withContext(Dispatchers.IO) {
            val locked = authManager.isAppLocked()
            withContext(Dispatchers.Main) {
                isUnlocked = !locked
            }
        }
    }

    when (isUnlocked) {
        null -> {
            Box(modifier = Modifier.fillMaxSize().background(DarkBg))
            return
        }
        false -> {
            AuthScreen(
                authManager = authManager,
                activity = activity,
                onUnlock = { isUnlocked = true }
            )
            return
        }
        true -> Unit
    }

    var currentRoute by remember { mutableStateOf(FocusGuardRoute.Home) }
    var selectedBlockType by remember { mutableStateOf(BlockTypeUi.PASSWORD) }
    var selectedTab by remember { mutableIntStateOf(1) }
    var selectedSessionType by remember { mutableStateOf("PASSWORD") }
    var permissionsVisible by remember { mutableStateOf(false) }

    val currentPomodoro by pomodoroManager.currentSession.collectAsState()

    LaunchedEffect(currentPomodoro) {
        if (currentPomodoro?.isActive == true) {
            val now = System.currentTimeMillis()
            if (currentPomodoro!!.endTime <= now) {
                FocusGuardLogger.log("MainActivity", "Detectado Pomodoro expirado na abertura. Limpando...")
                pomodoroManager.stopSession()
                currentRoute = FocusGuardRoute.Home
            } else {
                FocusGuardLogger.log("MainActivity", "Pomodoro ativo detectado. Redirecionando para tela de foco.")
                currentRoute = FocusGuardRoute.Pomodoro
            }
        } else if (currentRoute == FocusGuardRoute.Pomodoro) {
            FocusGuardLogger.log("MainActivity", "Pomodoro inativado. Voltando para Home.")
            currentRoute = FocusGuardRoute.Home
        }
    }

    var resumeKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(resumeKey) {
        withContext(Dispatchers.IO) {
            val isA11yEnabled = PermissionUtils.isAccessibilityServiceEnabled(activity)
            val isUsageAccessEnabled = PermissionUtils.isUsageAccessEnabled(activity)
            withContext(Dispatchers.Main) {
                permissionsVisible = !PermissionUtils.hasEssentialPermissions(
                    accessibilityEnabled = isA11yEnabled,
                    usageAccessEnabled = isUsageAccessEnabled
                )
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

    if (currentPomodoro?.isActive == true && currentPomodoro!!.endTime > System.currentTimeMillis()) {
        FocusGuardLogger.log("MainActivity", "Pomodoro Ativo detectado na UI. Exibindo tela de foco.")
        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            PomodoroScreen(pomodoroManager = pomodoroManager, authManager = authManager, onBack = { /* Bloqueado */ })
        }
        return
    }

    BackHandler(enabled = currentRoute != FocusGuardRoute.Home) {
        currentRoute = when (currentRoute) {
            FocusGuardRoute.Limits,
            FocusGuardRoute.Language,
            FocusGuardRoute.BlockCustomization -> FocusGuardRoute.Settings
            FocusGuardRoute.IntruderLog,
            FocusGuardRoute.UsageLimits -> FocusGuardRoute.BlockTypeDetail
            else -> FocusGuardRoute.Home
        }
    }

    Surface(color = MaterialTheme.colorScheme.background) {
        AnimatedContent(
            targetState = currentRoute,
            transitionSpec = {
                fadeIn(animationSpec = tween(220)) togetherWith fadeOut(animationSpec = tween(220))
            },
            label = "NavigationTransition"
        ) { route ->
            when (route) {
                FocusGuardRoute.Home -> MainScreen(
                    selectedTab = selectedTab,
                    onTabChange = { selectedTab = it },
                    permissionsVisible = permissionsVisible,
                    onPermissionsClick = { activity.startActivity(Intent(activity, PermissionsActivity::class.java)) },
                    onBlockTypeClick = { type ->
                        selectedBlockType = type
                        currentRoute = FocusGuardRoute.BlockTypeDetail
                    },
                    onSettingsClick = { currentRoute = FocusGuardRoute.Settings },
                    usageStatsContent = {
                        UsageStatsDashboardScreen(onBack = { currentRoute = FocusGuardRoute.Home }, showTopBar = false)
                    },
                    pomodoroContent = {
                        PomodoroScreen(
                            pomodoroManager = pomodoroManager,
                            authManager = authManager,
                            onBack = { currentRoute = FocusGuardRoute.Home }
                        )
                    },
                    recoveryContent = {
                        RecoveryHubScreen(
                            onReadBook = { book ->
                                val offlineBook = when (book) {
                                    RecoveryBook.CREATOR_INSTRUCTIONS ->
                                        OfflineBookActivity.OfflineBook.CREATOR_INSTRUCTIONS
                                    RecoveryBook.EASYPEASY ->
                                        OfflineBookActivity.OfflineBook.EASYPEASY
                                }
                                activity.startActivity(
                                    OfflineBookActivity.createIntent(activity, offlineBook)
                                )
                            }
                        )
                    }
                )
                FocusGuardRoute.BlockTypeDetail -> BlockTypeDetailScreen(
                    type = selectedBlockType,
                    onAddClick = {
                        when (selectedBlockType) {
                            // Limites de uso já têm tela própria de configuração;
                            // os outros dois entram no assistente com o tipo fixo,
                            // em vez de perguntar de novo o que o usuário já disse
                            // ao escolher o card.
                            BlockTypeUi.DAILY_LIMIT ->
                                currentRoute = FocusGuardRoute.UsageLimits

                            BlockTypeUi.PASSWORD -> activity.startActivity(
                                Intent(activity, CreateSessionActivity::class.java)
                                    .putExtra("SESSION_TYPE", "PASSWORD")
                            )

                            BlockTypeUi.DOPAMINE_FAST -> activity.startActivity(
                                Intent(activity, CreateSessionActivity::class.java)
                                    .putExtra("SESSION_TYPE", "TIME")
                            )
                        }
                    },
                    onIntruderLogClick = {
                        selectedBlockType = BlockTypeUi.PASSWORD
                        currentRoute = FocusGuardRoute.IntruderLog
                    },
                    onBack = { currentRoute = FocusGuardRoute.Home }
                )
                FocusGuardRoute.Settings -> SettingsScreen(
                    onLimitsClick = { currentRoute = FocusGuardRoute.Limits },
                    onLanguageClick = { currentRoute = FocusGuardRoute.Language },
                    onBlockCustomizationClick = { currentRoute = FocusGuardRoute.BlockCustomization },
                    onBack = { currentRoute = FocusGuardRoute.Home }
                )
                FocusGuardRoute.Pomodoro -> PomodoroScreen(
                    pomodoroManager = pomodoroManager,
                    authManager = authManager,
                    onBack = { currentRoute = FocusGuardRoute.Home }
                )
                FocusGuardRoute.Limits -> LimitsSecurityScreen(authManager = authManager, onBack = { currentRoute = FocusGuardRoute.Settings })
                FocusGuardRoute.IntruderLog -> IntruderLogScreen(onBack = { currentRoute = FocusGuardRoute.BlockTypeDetail })
                FocusGuardRoute.Language -> LanguageScreen(onBack = { currentRoute = FocusGuardRoute.Settings })
                // Volta para a lista do tipo de bloqueio, não para a Home: é de lá
                // que se chega aqui, e é lá que o limite recém-criado aparece.
                FocusGuardRoute.UsageLimits -> UsageLimitsScreen(
                    authManager = authManager,
                    onBack = { currentRoute = FocusGuardRoute.BlockTypeDetail }
                )
                FocusGuardRoute.Dashboard -> UsageStatsDashboardScreen(onBack = { currentRoute = FocusGuardRoute.Home })
                FocusGuardRoute.BlockCustomization -> BlockCustomizationScreen(onBack = { currentRoute = FocusGuardRoute.Settings })
                FocusGuardRoute.SessionsList -> SessionsListScreen(sessionType = selectedSessionType, onBack = { currentRoute = FocusGuardRoute.Home })
            }
        }
    }
}
