package com.focusguard.ui.compose.screens

import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.BuildConfig
import com.focusguard.R
import com.focusguard.data.UserProfile
import com.focusguard.monetization.AdsConsentManager
import com.focusguard.security.DeactivationCredentialManager
import com.focusguard.security.PermissionRevocationFlow
import com.focusguard.security.PermissionRevocationStep
import com.focusguard.security.ProtectionPermissionGate
import com.focusguard.ui.MasterPasswordActivity
import com.focusguard.ui.RemoveAllBlocksActivity
import com.focusguard.ui.compose.layout.FocusGuardScreenScaffold
import com.focusguard.ui.compose.layout.FocusGuardScrollableContent
import com.focusguard.ui.compose.layout.FocusGuardSectionHeader
import com.focusguard.ui.compose.theme.AccentCyan
import com.focusguard.ui.compose.theme.AccentCyanEdge
import com.focusguard.ui.compose.theme.AccentIconBadge
import com.focusguard.ui.compose.theme.CardBorder
import com.focusguard.ui.compose.theme.DangerRed
import com.focusguard.ui.compose.theme.FocusCard
import com.focusguard.ui.compose.theme.TextHint
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    profile: UserProfile,
    onProfileClick: () -> Unit,
    onLanguageClick: () -> Unit,
    onTestedBrowsersClick: () -> Unit,
    onCreatorInstagramClick: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val coroutineScope = rememberCoroutineScope()
    val credentialManager = remember(context) {
        DeactivationCredentialManager(context.applicationContext)
    }

    var privacyOptionsRequired by remember {
        mutableStateOf(AdsConsentManager.isPrivacyOptionsRequired(context))
    }
    var showRevokeConfirmation by remember { mutableStateOf(false) }
    var showRevokeCredential by remember { mutableStateOf(false) }
    var revocationWorking by remember { mutableStateOf(false) }
    var revocationFlowActive by remember { mutableStateOf(false) }
    var pendingRevocationSteps by remember {
        mutableStateOf<List<PermissionRevocationStep>>(emptyList())
    }

    val masterPasswordLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { }
    val revocationSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (pendingRevocationSteps.isNotEmpty()) {
            pendingRevocationSteps = pendingRevocationSteps.drop(1)
        }
    }

    fun beginPermissionRevocation() {
        if (revocationWorking || revocationFlowActive) return

        revocationWorking = true
        coroutineScope.launch {
            val initialState = ProtectionPermissionGate.read(context)
            if (!PermissionRevocationFlow.hasRevocablePermissions(initialState)) {
                Toast.makeText(
                    context,
                    context.getString(R.string.settings_revoke_permissions_none_active),
                    Toast.LENGTH_LONG
                ).show()
                revocationWorking = false
                return@launch
            }

            runCatching {
                PermissionRevocationFlow.revokeAdministrativeAccess(context)
            }

            val stateAfterAdministrativeRelease = ProtectionPermissionGate.read(context)
            pendingRevocationSteps = PermissionRevocationFlow.manualSteps(
                stateAfterAdministrativeRelease
            )
            revocationWorking = false
            revocationFlowActive = true
        }
    }

    LaunchedEffect(activity) {
        val host = activity ?: return@LaunchedEffect
        AdsConsentManager.refresh(host) {
            privacyOptionsRequired = AdsConsentManager.isPrivacyOptionsRequired(host)
        }
    }

    LaunchedEffect(revocationFlowActive, pendingRevocationSteps.firstOrNull()) {
        if (!revocationFlowActive) return@LaunchedEffect

        val nextStep = pendingRevocationSteps.firstOrNull()
        if (nextStep == null) {
            revocationFlowActive = false
            val remaining = PermissionRevocationFlow.hasRevocablePermissions(
                ProtectionPermissionGate.read(context)
            )
            Toast.makeText(
                context,
                context.getString(
                    if (remaining) {
                        R.string.settings_revoke_permissions_incomplete
                    } else {
                        R.string.settings_revoke_permissions_success
                    }
                ),
                Toast.LENGTH_LONG
            ).show()
            return@LaunchedEffect
        }

        val launched = runCatching {
            revocationSettingsLauncher.launch(
                PermissionRevocationFlow.settingsIntent(context, nextStep)
            )
            true
        }.getOrDefault(false)

        if (!launched) {
            Toast.makeText(
                context,
                context.getString(R.string.settings_revoke_permissions_settings_failed),
                Toast.LENGTH_LONG
            ).show()
            pendingRevocationSteps = pendingRevocationSteps.drop(1)
        }
    }

    FocusGuardScreenScaffold(
        title = stringResource(R.string.nav_settings),
        onBack = onBack
    ) { paddingValues ->
        FocusGuardScrollableContent(paddingValues = paddingValues) {
            ProfileSettingsCard(
                profile = profile,
                onClick = onProfileClick
            )

            Spacer(Modifier.height(24.dp))
            FocusGuardSectionHeader(stringResource(R.string.settings_category_general))
            SettingsItem(
                Icons.Default.Language,
                stringResource(R.string.language_settings),
                stringResource(R.string.settings_language_subtitle),
                onClick = onLanguageClick
            )
            if (privacyOptionsRequired && activity != null) {
                SettingsItem(
                    Icons.Default.Security,
                    stringResource(R.string.ads_privacy_options_title),
                    stringResource(R.string.ads_privacy_options_subtitle),
                    onClick = {
                        AdsConsentManager.showPrivacyOptions(activity) {
                            privacyOptionsRequired = AdsConsentManager
                                .isPrivacyOptionsRequired(activity)
                        }
                    }
                )
            }

            Spacer(Modifier.height(24.dp))
            FocusGuardSectionHeader(stringResource(R.string.settings_category_blocking))
            SettingsItem(
                Icons.Default.Lock,
                stringResource(R.string.master_password_settings_title),
                stringResource(R.string.master_password_settings_subtitle),
                onClick = {
                    masterPasswordLauncher.launch(MasterPasswordActivity.createIntent(context))
                }
            )
            SettingsItem(
                Icons.Default.Language,
                stringResource(R.string.tested_browsers_settings_title),
                stringResource(R.string.tested_browsers_settings_subtitle),
                onClick = onTestedBrowsersClick
            )
            SettingsItem(
                Icons.Default.DeleteForever,
                stringResource(R.string.master_remove_all_blocks_title),
                stringResource(R.string.master_remove_all_blocks_subtitle),
                iconTint = DangerRed,
                titleColor = DangerRed,
                onClick = {
                    context.startActivity(
                        android.content.Intent(context, RemoveAllBlocksActivity::class.java)
                    )
                }
            )
            SettingsItem(
                Icons.Default.Security,
                stringResource(R.string.settings_revoke_permissions_title),
                stringResource(R.string.settings_revoke_permissions_subtitle),
                iconTint = DangerRed,
                titleColor = DangerRed,
                onClick = {
                    if (!revocationWorking && !revocationFlowActive) {
                        showRevokeConfirmation = true
                    }
                }
            )

            Spacer(Modifier.height(24.dp))
            SettingsItem(
                Icons.Default.CameraAlt,
                stringResource(R.string.creator_instagram_title),
                stringResource(R.string.settings_creator_instagram_subtitle),
                iconTint = Color(0xFFE1306C),
                onClick = onCreatorInstagramClick
            )

            Spacer(Modifier.height(32.dp))
            Text(
                text = "HardBlock ${BuildConfig.VERSION_NAME}",
                modifier = Modifier.align(Alignment.CenterHorizontally),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }

    if (showRevokeConfirmation) {
        AlertDialog(
            onDismissRequest = { showRevokeConfirmation = false },
            title = {
                Text(stringResource(R.string.settings_revoke_permissions_confirm_title))
            },
            text = {
                Text(stringResource(R.string.settings_revoke_permissions_confirm_message))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRevokeConfirmation = false
                        if (credentialManager.hasCredential()) {
                            showRevokeCredential = true
                        } else {
                            beginPermissionRevocation()
                        }
                    }
                ) {
                    Text(
                        stringResource(R.string.settings_revoke_permissions_confirm_action),
                        color = DangerRed
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showRevokeConfirmation = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showRevokeCredential) {
        ConfirmMasterCredentialDialog(
            promptRes = R.string.settings_revoke_permissions_master_prompt,
            onDismiss = { showRevokeCredential = false },
            onConfirmed = {
                showRevokeCredential = false
                beginPermissionRevocation()
            }
        )
    }

    if (revocationWorking) {
        AlertDialog(
            onDismissRequest = { },
            title = {
                Text(stringResource(R.string.settings_revoke_permissions_title))
            },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.settings_revoke_permissions_progress))
                }
            },
            confirmButton = { }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileSettingsCard(
    profile: UserProfile,
    onClick: () -> Unit
) {
    FocusCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, AccentCyanEdge),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ProfileAvatar(
                avatarId = profile.avatarId,
                modifier = Modifier.size(64.dp)
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = profile.displayName.ifBlank {
                        stringResource(R.string.settings_profile_not_configured)
                    },
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = stringResource(R.string.settings_profile_subtitle),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = stringResource(R.string.action_open),
                tint = TextHint,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    iconTint: Color = AccentCyan,
    titleColor: Color = Color.Unspecified,
    onClick: () -> Unit
) {
    FocusCard(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, CardBorder),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AccentIconBadge(
                icon = icon,
                accent = iconTint,
                size = 40.dp,
                iconSize = 20.dp,
                shape = RoundedCornerShape(12.dp)
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (titleColor == Color.Unspecified) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        titleColor
                    }
                )
                Text(
                    text = subtitle,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = stringResource(R.string.action_open),
                tint = TextHint,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
