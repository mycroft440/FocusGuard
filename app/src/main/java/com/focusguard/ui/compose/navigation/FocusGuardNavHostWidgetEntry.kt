package com.focusguard.ui.compose.navigation

import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.focusguard.focusmode.FocusModeManager
import com.focusguard.manager.PomodoroManager
import com.focusguard.security.AuthManager
import com.focusguard.ui.PermissionsActivity
import com.focusguard.ui.compose.screens.PomodoroScreen
import com.focusguard.ui.compose.theme.DarkBg

/**
 * Sobrecarga usada pela MainActivity para aceitar entrada direta do widget.
 * Mantém o NavHost existente intacto e abre a própria tela Pomodoro do app.
 */
@Composable
fun FocusGuardNavHost(
    activity: AppCompatActivity,
    authManager: AuthManager,
    pomodoroManager: PomodoroManager,
    focusModeManager: FocusModeManager,
    focusModeReturnNonce: Long = 0L,
    pomodoroNavigationNonce: Long,
    onEnforceFocusModeLockTask: () -> Unit
) {
    var lastHandledPomodoroNonce by remember { mutableLongStateOf(0L) }
    var directPomodoroVisible by remember { mutableStateOf(false) }
    val focusModeSession by focusModeManager.session.collectAsState()
    val focusModeActive = focusModeSession?.isActive() == true

    LaunchedEffect(pomodoroNavigationNonce, focusModeActive) {
        if (pomodoroNavigationNonce > lastHandledPomodoroNonce) {
            lastHandledPomodoroNonce = pomodoroNavigationNonce
            if (!focusModeActive) directPomodoroVisible = true
        }
        if (focusModeActive) directPomodoroVisible = false
    }

    if (directPomodoroVisible && !focusModeActive) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(DarkBg)
        ) {
            PomodoroScreen(
                pomodoroManager = pomodoroManager,
                authManager = authManager,
                onPermissionsRequired = {
                    activity.startActivity(
                        PermissionsActivity.createPendingProtectionIntent(activity)
                    )
                },
                onBack = { directPomodoroVisible = false }
            )
        }
    } else {
        FocusGuardNavHost(
            activity = activity,
            authManager = authManager,
            pomodoroManager = pomodoroManager,
            focusModeManager = focusModeManager,
            focusModeReturnNonce = focusModeReturnNonce,
            onEnforceFocusModeLockTask = onEnforceFocusModeLockTask
        )
    }
}
