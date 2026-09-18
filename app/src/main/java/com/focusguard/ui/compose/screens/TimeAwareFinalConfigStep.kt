package com.focusguard.ui.compose.screens

import androidx.compose.runtime.Composable
import com.focusguard.security.AuthManager
import com.focusguard.ui.FinalConfigStep

enum class TimeBlockConfigMode {
    CONTINUOUS,
    DAILY_PERIODS;

    companion object {
        fun fromSerialized(value: String?): TimeBlockConfigMode =
            entries.firstOrNull { it.name == value } ?: CONTINUOUS
    }
}

@Composable
fun TimeAwareFinalConfigStep(
    sessionType: String,
    authManager: AuthManager,
    sites: List<String>,
    apps: List<String>,
    appName: String,
    onFinish: () -> Unit,
    onBack: () -> Unit,
    timeBlockMode: TimeBlockConfigMode = TimeBlockConfigMode.CONTINUOUS
) {
    if (sessionType == "TIME") {
        TimeBlockSessionConfigScreen(
            appName = appName,
            apps = apps,
            sites = sites,
            onBack = onBack,
            onFinish = onFinish,
            mode = timeBlockMode
        )
    } else {
        FinalConfigStep(
            sessionType = sessionType,
            authManager = authManager,
            sites = sites,
            apps = apps,
            onFinish = onFinish,
            onBack = onBack
        )
    }
}
