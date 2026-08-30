package com.focusguard.ui.compose.screens

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
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.focusguard.admin.DeviceOwnerManager
import com.focusguard.data.UserProfile
import com.focusguard.manager.BlockingSessionManager
import com.focusguard.monetization.AdsConsentManager
import com.focusguard.security.DeactivationCredentialManager
import com.focusguard.ui.MasterPasswordActivity
import com.focusguard.ui.MasterRemovalActivity
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
import kotlin.math.ceil

@Composable
fun SettingsScreen(
    profile: UserProfile,
    onProfileClick: () -> Unit,
    onLimitsClick: () -> Unit,
    onLanguageClick: () -> Unit,
    onBlockCustomizationClick: () -> Unit,
    onCreatorInstagramClick: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val deactivationCredentialManager = remember(context) {
        DeactivationCredentialManager(context)
    }
    val blockingSessionManager = remember(context) {
        BlockingSessionManager.getInstance(context)
    }
    val deviceOwnerManager = remember(context) {
        DeviceOwnerManager.getInstance(context)
    }
    var showDeviceOwnerMaintenanceDialog by remember { mutableStateOf(false) }
    var showDeviceOwnerSetupGuideDialog by remember { mutableStateOf(false) }
    var deactivationCredentialRevision by remember { mutableIntStateOf(0) }
    var deviceOwnerRevision by remember { mutableIntStateOf(0) }
    var privacyOptionsRequired by remember {
        mutableStateOf(AdsConsentManager.isPrivacyOptionsRequired(context))
    }
    val masterPasswordLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        deactivationCredentialRevision++
    }

    LaunchedEffect(activity) {
        val host = activity ?: return@LaunchedEffect
        AdsConsentManager.refresh(host) {
            privacyOptionsRequired = AdsConsentManager.isPrivacyOptionsRequired(host)
        }
    }

    val isBlockingActive by blockingSessionManager.isBlockingActiveFlow.collectAsState(
        initial = true
    )
    val deactivationCredentialConfigured = remember(deactivationCredentialRevision) {
        deactivationCredentialManager.hasCredential()
    }
    val armoredProtectionArmed = remember(deviceOwnerRevision, isBlockingActive) {
        deviceOwnerManager.isArmoredProtectionArmed()
    }
    val credentialManagementLocked = isBlockingActive || armoredProtectionArmed
    val deactivationPasswordSubtitle = stringResource(
        when {
            credentialManagementLocked -> R.string.deactivation_password_locked_subtitle
            deactivationCredentialConfigured -> R.string.deactivation_password_configured
            else -> R.string.deactivation_password_not_configured
        }
    )
    val isDeviceOwnerActive = remember(deviceOwnerRevision) {
        deviceOwnerManager.isDeviceOwnerActive()
    }
    val deviceOwnerMaintenanceRemaining = remember(deviceOwnerRevision) {
        deviceOwnerManager.maintenanceRemainingMillis()
    }
    val deviceOwnerMaintenanceSubtitle = when {
        !isDeviceOwnerActive -> stringResource(
            R.string.device_owner_maintenance_owner_required
        )
        deviceOwnerMaintenanceRemaining > 0L -> {
            val remainingMinutes = ceil(
                deviceOwnerMaintenanceRemaining / 60_000.0
            ).toInt().coerceAtLeast(1)
            stringResource(
                R.string.device_owner_maintenance_active_subtitle,
                remainingMinutes
            )
        }
        else -> stringResource(R.string.device_owner_maintenance_subtitle)
    }
    val deviceOwnerSubtitle = stringResource(
        when {
            isDeviceOwnerActive -> R.string.device_owner_status_active
            deviceOwnerManager.isDeviceAdminActive() -> R.string.device_admin_status_only
            else -> R.string.device_owner_status_inactive
        }
    )
    val uninstallSubtitle = stringResource(R.string.uninstall_app_subtitle_auth)

    if (showDeviceOwnerMaintenanceDialog) {
        DeviceOwnerMaintenanceDialog(
            onDismiss = { showDeviceOwnerMaintenanceDialog = false },
            onStateChanged = { deviceOwnerRevision++ }
        )
    }

    if (showDeviceOwnerSetupGuideDialog) {
        DeviceOwnerSetupGuideDialog(
            onDismiss = { showDeviceOwnerSetupGuideDialog = false },
            onStateChanged = { deviceOwnerRevision++ }
        )
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
                stringResource(R.string.deactivation_password_title),
                deactivationPasswordSubtitle,
                onClick = {
                    masterPasswordLauncher.launch(
                        MasterPasswordActivity.createIntent(context)
                    )
                }
            )
            SettingsItem(
                Icons.Default.Palette,
                stringResource(R.string.block_customization),
                stringResource(R.string.settings_block_customization_subtitle),
                onClick = onBlockCustomizationClick
            )

            Spacer(Modifier.height(24.dp))
            FocusGuardSectionHeader(stringResource(R.string.settings_category_advanced_security))
            SettingsItem(
                Icons.Default.Security,
                stringResource(R.string.limits_and_security),
                stringResource(R.string.settings_limits_subtitle),
                onClick = onLimitsClick
            )

            Spacer(Modifier.height(24.dp))
            FocusGuardSectionHeader(
                stringResource(R.string.settings_category_danger),
                color = DangerRed
            )
            SettingsItem(
                Icons.Default.Security,
                stringResource(R.string.device_owner_maintenance_title),
                deviceOwnerMaintenanceSubtitle,
                iconTint = DangerRed,
                titleColor = DangerRed,
                onClick = { showDeviceOwnerMaintenanceDialog = true }
            )
            SettingsItem(
                Icons.Default.Warning,
                stringResource(R.string.nuclear_protection),
                deviceOwnerSubtitle,
                iconTint = DangerRed,
                titleColor = DangerRed,
                onClick = { showDeviceOwnerSetupGuideDialog = true }
            )
            SettingsItem(
                Icons.Default.DeleteForever,
                stringResource(R.string.uninstall_app_title),
                uninstallSubtitle,
                iconTint = DangerRed,
                titleColor = DangerRed,
                onClick = {
                    context.startActivity(
                        MasterRemovalActivity.createIntent(
                            context,
                            MasterRemovalActivity.Target.UNINSTALL
                        )
                    )
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileSettingsCard(
    profile: UserProfile,
    onClick: () -> Unit
) {
    // O cartão do perfil abre a tela, então ganha a borda ciano: é o único
    // item aqui que fala de quem usa o app, e não do que o app faz.
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
    // Cada linha ganha o selo de ícone padrão do app no lugar do ícone solto:
    // com quinze itens empilhados, o selo é o que dá ritmo à lista e faz a
    // vista pular de um item para o outro sem reler tudo.
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
