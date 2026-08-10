package com.focusguard.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.focusguard.MainActivity
import com.focusguard.security.SensitivePermissionsConsent
import com.focusguard.ui.compose.screens.PermissionFlowMode
import com.focusguard.ui.compose.screens.PermissionsScreen
import com.focusguard.ui.compose.screens.SensitivePermissionsDisclosureScreen
import com.focusguard.ui.compose.theme.FocusGuardTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class PermissionsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val flowMode = if (intent.getBooleanExtra(EXTRA_PENDING_ESSENTIALS_ONLY, false)) {
            PermissionFlowMode.PendingEssentials
        } else {
            PermissionFlowMode.FullSetup
        }

        setContent {
            FocusGuardTheme {
                var consentAccepted by remember {
                    mutableStateOf(SensitivePermissionsConsent.hasAccepted(this))
                }

                if (consentAccepted) {
                    PermissionsScreen(
                        flowMode = flowMode,
                        onFinish = ::finishPermissionFlow
                    )
                } else {
                    SensitivePermissionsDisclosureScreen(
                        onAccept = {
                            SensitivePermissionsConsent.accept(this)
                            consentAccepted = true
                        },
                        onDecline = ::finishPermissionFlow
                    )
                }
            }
        }
    }

    private fun finishPermissionFlow() {
        getSharedPreferences("FocusGuardPrefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("hasSeenOnboarding", true)
            .apply()

        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        )
        finish()
    }

    companion object {
        private const val EXTRA_PENDING_ESSENTIALS_ONLY =
            "com.focusguard.extra.PENDING_ESSENTIALS_ONLY"

        fun createPendingEssentialsIntent(context: Context): Intent {
            return Intent(context, PermissionsActivity::class.java).apply {
                putExtra(EXTRA_PENDING_ESSENTIALS_ONLY, true)
            }
        }
    }
}
