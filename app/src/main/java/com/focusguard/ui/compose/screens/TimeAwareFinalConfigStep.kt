package com.focusguard.ui.compose.screens

import androidx.compose.runtime.Composable
import com.focusguard.manager.BlockingSessionManager
import com.focusguard.security.AuthManager
import com.focusguard.security.DeactivationCredentialManager
import com.focusguard.security.PasswordAppUnlockStore
import com.focusguard.ui.FinalConfigStep

@Composable
fun TimeAwareFinalConfigStep(
    sessionType: String,
    authManager: AuthManager,
    blockingSessionManager: BlockingSessionManager,
    deactivationCredentialManager: DeactivationCredentialManager,
    passwordAppUnlockStore: PasswordAppUnlockStore,
    sites: List<String>,
    apps: List<String>,
    appName: String,
    onFinish: () -> Unit,
    onBack: () -> Unit
) {
    if (sessionType == "TIME") {
        TimeBlockSessionConfigScreen(
            sessionManager = blockingSessionManager,
            credentialManager = deactivationCredentialManager,
            appName = appName,
            apps = apps,
            sites = sites,
            onBack = onBack,
            onFinish = onFinish
        )
    } else {
        FinalConfigStep(
            sessionType = sessionType,
            authManager = authManager,
            sessionManager = blockingSessionManager,
            credentialManager = deactivationCredentialManager,
            appUnlockStore = passwordAppUnlockStore,
            sites = sites,
            apps = apps,
            onFinish = onFinish,
            onBack = onBack
        )
    }
}
