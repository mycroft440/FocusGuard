package com.focusguard.performance

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import com.focusguard.ui.PasswordCredentialEditor
import com.focusguard.ui.compose.screens.AppLimitRedesignedSheet
import com.focusguard.ui.compose.screens.UsageLimitAppUi
import com.focusguard.ui.compose.theme.DarkBg

/**
 * Isolated host for Macrobenchmark/Baseline Profile input journeys.
 *
 * The class lives in the main source set so it exercises the real production
 * composables, but it is intentionally absent from the production manifest.
 * Only benchmarkRelease/nonMinifiedRelease manifest overlays register it.
 */
class PerformanceInputActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_PASSWORD

        setContent {
            MaterialTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(DarkBg)
                        .semantics { testTagsAsResourceId = true }
                ) {
                    when (mode) {
                        MODE_USAGE_LIMIT -> UsageLimitPerformanceContent()
                        else -> PasswordPerformanceContent()
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_MODE = "hardblock.performance.mode"
        const val MODE_USAGE_LIMIT = "usage_limit"
        const val MODE_PASSWORD = "password"
    }
}

@Composable
private fun PasswordPerformanceContent() {
    val passwordState = remember { TextFieldState() }
    val confirmationState = remember { TextFieldState() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        PasswordCredentialEditor(
            passwordState = passwordState,
            confirmationState = confirmationState,
            clearErrorOnEdit = false,
            onEdited = {}
        )
    }
}

@Composable
private fun UsageLimitPerformanceContent() {
    val app = remember {
        UsageLimitAppUi(
            packageName = "com.android.settings",
            appName = "Settings",
            currentLimitMinutes = null,
            isEnabled = false,
            usageMs = 0L,
            lockMode = "NONE",
            lockPasswordHash = null,
            lockUntilTimestamp = null
        )
    }

    AppLimitRedesignedSheet(
        app = app,
        permissionsMissing = false,
        hasMasterCredential = true,
        onConfigureMasterPassword = {},
        onDismiss = {},
        onSave = { _, _, _, _, _ -> }
    )
}
