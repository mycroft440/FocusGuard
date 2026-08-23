package com.focusguard.ui.compose.navigation

import androidx.compose.runtime.*
import android.content.Intent
import com.focusguard.data.CreatorInstagramPromptPolicy
import com.focusguard.data.CreatorInstagramPromptStore
import com.focusguard.data.UserProfileStore
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
import com.focusguard.focusmode.FocusModeManager
import com.focusguard.security.AuthManager
import com.focusguard.security.ProtectionPermission
import com.focusguard.security.ProtectionPermissionGate
import com.focusguard.ui.CreateSessionActivity
import com.focusguard.ui.OfflineBookActivity
import com.focusguard.ui.PermissionsActivity
import com.focusguard.ui.compose.screens.AuthScreen
import com.focusguard.ui.compose.screens.BlockCustomizationScreen
import com.focusguard.ui.compose.screens.BlockTypeDetailScreen
import com.focusguard.ui.compose.screens.BlockTypeUi
import com.focusguard.ui.compose.screens.FocusModeScreen
import com.focusguard.ui.compose.screens.IntruderLogScreen
import com.focusguard.ui.compose.screens.LanguageScreen
import com.focusguard.ui.compose.screens.LimitsSecurityScreen
import com.focusguard.ui.compose.screens.MainScreen
import com.focusguard.ui.compose.screens.openCreatorInstagram
import com.focusguard.ui.compose.screens.PomodoroScreen
import com.focusguard.ui.compose.screens.ProfileScreen
import com.focusguard.ui.compose.screens.RecoveryBook
import com.focusguard.ui.compose.screens.RecoveryCourseGatewayScreen
import com.focusguard.ui.compose.screens.RecoveryHubScreen
import com.focusguard.ui.compose.screens.SessionsListScreen
import com.focusguard.ui.compose.screens.SettingsScreen
import com.focusguard.ui.compose.screens.UsageLimitsScreen
import com.focusguard.ui.compose.screens.UsageStatsDashboardScreen
import com.focusguard.ui.compose.theme.DarkBg
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private object FocusGuardRoute {
    const val Home = "HOME"
    const val Settings = "SETTINGS"
    const val Profile = "PROFILE"
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
    pomodoroManager: PomodoroManager,
    focusModeManager: FocusModeManager,
    onEnforceFocusModeLockTask: () -> Unit
) {
    var isUnlocked by remember { mutableStateOf<Boolean?>(null) }
    var resumeKey by remember { mutableIntStateOf(0) }

    DisposableEffect(Unit) {
        val callback = object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onResume(owner: androidx.lifecycle.LifecycleOwner) {
                resumeKey++
            }
        }
        activity.lifecycle.addObserver(callback)
        onDispose { activity.lifecycle.removeObserver(callback) }
    }

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
    var missingProtectionPermissions by remember {
        mutableStateOf(ProtectionPermission.entries.toList())
    }
    val navigationScope = rememberCoroutineScope()
    val userProfileStore = remember(activity.applicationContext) {
        UserProfileStore(activity.applicationContext)
    }
    val creatorInstagramPromptStore = remember(activity.applicationContext) {
        CreatorInstagramPromptStore(activity.applicationContext)
    }
    var userProfile by remember { mutableStateOf(userProfileStore.load()) }
    var creatorInstagramPresented by remember {
        mutableStateOf(creatorInstagramPromptStore.wasHomeCardPresented())
    }
    var showCreatorInstagramCard by remember { mutableStateOf(false) }

    val currentPomodoro by pomodoroManager.currentSession.collectAsState()
    val pomodoroCycle by pomodoroManager.cycleState.collectAsState()
    val activeFocusMode by focusModeManager.session.collectAsState()
    val focusModeActive = activeFocusMode?.isActive() == true

    LaunchedEffect(activeFocusMode?.startedAtMillis) {
        if (activeFocusMode?.isActive() == true) {
            currentRoute = FocusGuardRoute.Home
            selectedTab = 4
        }
    }

    LaunchedEffect(currentPomodoro, pomodoroCycle, focusModeActive) {
        val cycleActive = pomodoroCycle?.active == true
        if (currentPomodoro?.isActive == true || cycleActive) {
            val intervalStillRunning = (currentPomodoro?.endTime ?: 0L) > System.currentTimeMillis()
            if (!intervalStillRunning) {
                return@LaunchedEffect
            }
            if (focusModeActive) {
                currentRoute = FocusGuardRoute.Home
            } else {
                currentRoute = FocusGuardRoute.Pomodoro
            }
        } else if (currentRoute == FocusGuardRoute.Pomodoro) {
            currentRoute = FocusGuardRoute.Home
        }
    }

    LaunchedEffect(
        resumeKey,
        currentRoute,
        selectedTab,
        currentPomodoro?.isActive
    ) {
        val protectionHomeVisible = currentRoute == FocusGuardRoute.Home &&
            selectedTab == 1 &&
            currentPomodoro?.isActive != true

        if (!protectionHomeVisible || creatorInstagramPresented) {
            showCreatorInstagramCard = false
            return@LaunchedEffect
        }

        val remainingDelay = creatorInstagramPromptStore.remainingDelayMillis()
        if (remainingDelay > 0L) delay(remainingDelay)

        if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            return@LaunchedEffect
        }

        creatorInstagramPromptStore.markHomeCardPresented()
        creatorInstagramPresented = true
        showCreatorInstagramCard = true
        delay(CreatorInstagramPromptPolicy.HOME_CARD_VISIBLE_MILLIS)
        showCreatorInstagramCard = false
    }

    suspend fun refreshProtectionPermissions(): List<ProtectionPermission> {
        val missing = withContext(Dispatchers.IO) {
            ProtectionPermissionGate.read(activity).missingPermissions
        }
        missingProtectionPermissions = missing
        return missing
    }

    fun withProtectionPermissions(onReady: () -> Unit) {
        navigationScope.launch {
            if (refreshProtectionPermissions().isEmpty()) {
                onReady()
            } else {
                activity.startActivity(
                    PermissionsActivity.createPendingProtectionIntent(activity)
                )
            }
        }
    }

    LaunchedEffect(resumeKey) {
        refreshProtectionPermissions()
    }

    if (!focusModeActive &&
        currentPomodoro?.isActive == true &&
        (currentPomodoro?.endTime ?: 0L) > System.currentTimeMillis()
    ) {
        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            PomodoroScreen(
                pomodoroManager = pomodoroManager,
                authManager = authManager,
                onPermissionsRequired = {
                    activity.startActivity(
                        PermissionsActivity.createPendingProtectionIntent(activity)
                    )
                },
                onBack = { }
            )
        }
        return
    }

    BackHandler(enabled = currentRoute != FocusGuardRoute.Home) {
        currentRoute = when (currentRoute) {
            FocusGuardRoute.Limits,
            FocusGuardRoute.Language,
            FocusGuardRoute.Profile,
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
                    profile = userProfile,
                    selectedTab = selectedTab,
                    onTabChange = { selectedTab = it },
                    focusModeActive = focusModeActive,
                    missingProtectionPermissions = missingProtectionPermissions,
                    showCreatorInstagramCard = false,
                    showCreatorFeedbackButton =
                        CreatorInstagramPromptPolicy.shouldShowFeedbackButton(
                            homeCardPresented = creatorInstagramPresented,
                            homeCardVisible = showCreatorInstagramCard
                        ),
                    onPermissionsClick = {
                        activity.startActivity(
                            PermissionsActivity.createPendingProtectionIntent(activity)
                        )
                    },
                    onCreatorInstagramClick = {
                        showCreatorInstagramCard = false
                        openCreatorInstagram(activity)
                    },
                    onBlockTypeClick = { type ->
                        withProtectionPermissions {
                            selectedBlockType = type
                            currentRoute = FocusGuardRoute.BlockTypeDetail
                        }
                    },
                    onSettingsClick = { currentRoute = FocusGuardRoute.Settings },
                    usageStatsContent = {
                        UsageStatsDashboardScreen(
                            onBack = { currentRoute = FocusGuardRoute.Home },
                            showTopBar = false
                        )
                    },
                    pomodoroContent = {
                        PomodoroScreen(
                            pomodoroManager = pomodoroManager,
                            authManager = authManager,
                            onPermissionsRequired = {
                                activity.startActivity(
                                    PermissionsActivity.createPendingProtectionIntent(activity)
                                )
                            },
                            onBack = { currentRoute = FocusGuardRoute.Home },
                            compactLayout = focusModeActive
                        )
                    },
                    recoveryContent = {
                        RecoveryCourseGatewayScreen {
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
                    },
                    focusModeContent = {
                        FocusModeScreen(
                            manager = focusModeManager,
                            onStartLockTask = onEnforceFocusModeLockTask
                        )
                    }
                )
                FocusGuardRoute.BlockTypeDetail -> BlockTypeDetailScreen(
                    type = selectedBlockType,
                    onAddClick = {
                        withProtectionPermissions {
                            when (selectedBlockType) {
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
                        }
                    },
                    onIntruderLogClick = {
                        selectedBlockType = BlockTypeUi.PASSWORD
                        currentRoute = FocusGuardRoute.IntruderLog
                    },
                    onBack = { currentRoute = FocusGuardRoute.Home }
                )
                FocusGuardRoute.Settings -> SettingsScreen(
                    profile = userProfile,
                    onProfileClick = { currentRoute = FocusGuardRoute.Profile },
                    onLimitsClick = { currentRoute = FocusGuardRoute.Limits },
                    onLanguageClick = { currentRoute = FocusGuardRoute.Language },
                    onBlockCustomizationClick = { currentRoute = FocusGuardRoute.BlockCustomization },
                    onCreatorInstagramClick = { openCreatorInstagram(activity) },
                    onBack = { currentRoute = FocusGuardRoute.Home }
                )
                FocusGuardRoute.Profile -> ProfileScreen(
                    profile = userProfile,
                    onSave = { profile ->
                        userProfile = userProfileStore.save(profile)
                        currentRoute = FocusGuardRoute.Settings
                    },
                    onBack = { currentRoute = FocusGuardRoute.Settings }
                )
                FocusGuardRoute.Pomodoro -> PomodoroScreen(
                    pomodoroManager = pomodoroManager,
                    authManager = authManager,
                    onPermissionsRequired = {
                        activity.startActivity(
                            PermissionsActivity.createPendingProtectionIntent(activity)
                        )
                    },
                    onBack = { currentRoute = FocusGuardRoute.Home }
                )
                FocusGuardRoute.Limits -> LimitsSecurityScreen(
                    authManager = authManager,
                    onBack = { currentRoute = FocusGuardRoute.Settings }
                )
                FocusGuardRoute.IntruderLog -> IntruderLogScreen(
                    onBack = { currentRoute = FocusGuardRoute.BlockTypeDetail }
                )
                FocusGuardRoute.Language -> LanguageScreen(
                    onBack = { currentRoute = FocusGuardRoute.Settings }
                )
                FocusGuardRoute.UsageLimits -> UsageLimitsScreen(
                    authManager = authManager,
                    onPermissionsRequired = {
                        activity.startActivity(
                            PermissionsActivity.createPendingProtectionIntent(activity)
                    )
                    },
                    onBack = { currentRoute = FocusGuardRoute.BlockTypeDetail }
                )
                FocusGuardRoute.Dashboard -> UsageStatsDashboardScreen(
                    onBack = { currentRoute = FocusGuardRoute.Home }
                )
                FocusGuardRoute.BlockCustomization -> BlockCustomizationScreen(
                    onBack = { currentRoute = FocusGuardRoute.Settings }
                )
                FocusGuardRoute.SessionsList -> SessionsListScreen(
                    sessionType = selectedSessionType,
                    onBack = { currentRoute = FocusGuardRoute.Home }
                )
            }
        }
    }
}
