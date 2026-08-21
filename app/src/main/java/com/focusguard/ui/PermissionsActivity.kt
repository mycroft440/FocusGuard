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
import com.focusguard.admin.DeviceOwnerManager
import com.focusguard.security.ProtectionPermissionGate
import com.focusguard.security.SelfProtectionConsent
import com.focusguard.security.SensitivePermissionsConsent
import com.focusguard.ui.compose.screens.PermissionFlowMode
import com.focusguard.ui.compose.screens.PermissionsScreen
import com.focusguard.ui.compose.screens.SelfProtectionConsentScreen
import com.focusguard.ui.compose.screens.SensitivePermissionsDisclosureScreen
import com.focusguard.ui.compose.theme.FocusGuardTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class PermissionsActivity : ComponentActivity() {
    @Inject lateinit var deviceOwnerManager: DeviceOwnerManager
    @Inject lateinit var protectionPermissionGate: ProtectionPermissionGate

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pendingProtectionOnly =
            intent.getBooleanExtra(EXTRA_PENDING_PROTECTION_ONLY, false) ||
                intent.getBooleanExtra(LEGACY_EXTRA_PENDING_ESSENTIALS_ONLY, false)
        val flowMode = if (pendingProtectionOnly) {
            PermissionFlowMode.PendingProtection
        } else {
            PermissionFlowMode.FullSetup
        }

        setContent {
            FocusGuardTheme {
                var consentAccepted by remember {
                    mutableStateOf(SensitivePermissionsConsent.hasAccepted(this))
                }
                var selfProtectionAccepted by remember {
                    mutableStateOf(SelfProtectionConsent.hasAccepted(this))
                }

                when {
                    !selfProtectionAccepted -> SelfProtectionConsentScreen(
                        onAccept = {
                            SelfProtectionConsent.accept(this)
                            selfProtectionAccepted = true
                        },
                        onDecline = ::finishPermissionFlow
                    )
                    !consentAccepted -> SensitivePermissionsDisclosureScreen(
                        onAccept = {
                            SensitivePermissionsConsent.accept(this)
                            consentAccepted = true
                        },
                        onDecline = ::finishPermissionFlow
                    )
                    else -> PermissionsScreen(
                        flowMode = flowMode,
                        deviceOwnerManager = deviceOwnerManager,
                        protectionPermissionGate = protectionPermissionGate,
                        onFinish = ::finishPermissionFlow
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
        private const val EXTRA_PENDING_PROTECTION_ONLY =
            "com.focusguard.extra.PENDING_PROTECTION_ONLY"
        private const val LEGACY_EXTRA_PENDING_ESSENTIALS_ONLY =
            "com.focusguard.extra.PENDING_ESSENTIALS_ONLY"

        fun createPendingProtectionIntent(context: Context): Intent {
            return Intent(context, PermissionsActivity::class.java).apply {
                putExtra(EXTRA_PENDING_PROTECTION_ONLY, true)
            }
        }
    }
}
