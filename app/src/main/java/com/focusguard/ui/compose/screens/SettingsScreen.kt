package com.focusguard.ui.compose.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.BuildConfig
import com.focusguard.R
import com.focusguard.security.AccessibilityProtectionGate
import com.focusguard.security.AuthManager
import com.focusguard.ui.compose.layout.FocusGuardScreenScaffold
import com.focusguard.ui.compose.layout.FocusGuardScrollableContent
import com.focusguard.ui.compose.layout.FocusGuardSectionHeader
import com.focusguard.ui.compose.theme.AccentCyan
import com.focusguard.ui.compose.theme.DangerRed
import com.focusguard.ui.compose.theme.TextHint
import kotlin.math.ceil
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    onLimitsClick: () -> Unit,
    onIntruderLogClick: () -> Unit,
    onLanguageClick: () -> Unit,
    onPasswordManagementClick: () -> Unit,
    onBlockCustomizationClick: () -> Unit,
    onDeviceOwnerClick: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var showAccessibilityUnlockDialog by remember { mutableStateOf(false) }
    val remainingUnlockMillis = AccessibilityProtectionGate.remainingMillis(context)
    val accessibilitySubtitle = if (remainingUnlockMillis > 0L) {
        val remainingMinutes = ceil(remainingUnlockMillis / 60_000.0).toInt().coerceAtLeast(1)
        stringResource(R.string.accessibility_unlock_active, remainingMinutes)
    } else {
        stringResource(R.string.accessibility_protection_subtitle)
    }

    if (showAccessibilityUnlockDialog) {
        AccessibilityUnlockDialog(
            onDismiss = { showAccessibilityUnlockDialog = false },
            onManagePasswords = {
                showAccessibilityUnlockDialog = false
                onPasswordManagementClick()
            },
            onUnlocked = {
                showAccessibilityUnlockDialog = false
                runCatching {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            }
        )
    }

    FocusGuardScreenScaffold(
        title = stringResource(R.string.nav_settings),
        onBack = onBack
    ) { paddingValues ->
        FocusGuardScrollableContent(paddingValues = paddingValues) {
            FocusGuardSectionHeader(stringResource(R.string.settings_category_general))
            SettingsItem(
                Icons.Default.Lock,
                stringResource(R.string.manage_passwords),
                stringResource(R.string.settings_password_subtitle),
                onClick = onPasswordManagementClick
            )
            SettingsItem(
                Icons.Default.Language,
                stringResource(R.string.language_settings),
                stringResource(R.string.settings_language_subtitle),
                onClick = onLanguageClick
            )

            Spacer(Modifier.height(24.dp))
            FocusGuardSectionHeader(stringResource(R.string.settings_category_blocking))
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
                stringResource(R.string.accessibility_protection_title),
                accessibilitySubtitle,
                onClick = { showAccessibilityUnlockDialog = true }
            )
            SettingsItem(
                Icons.Default.Security,
                stringResource(R.string.limits_and_security),
                stringResource(R.string.settings_limits_subtitle),
                onClick = onLimitsClick
            )
            SettingsItem(
                Icons.Default.PhotoCamera,
                stringResource(R.string.intruder_log),
                stringResource(R.string.settings_intruder_log_subtitle),
                onClick = onIntruderLogClick
            )

            Spacer(Modifier.height(24.dp))
            FocusGuardSectionHeader(
                stringResource(R.string.settings_category_danger),
                color = DangerRed
            )
            SettingsItem(
                Icons.Default.Warning,
                stringResource(R.string.nuclear_protection),
                stringResource(R.string.settings_nuclear_subtitle),
                iconTint = DangerRed,
                titleColor = DangerRed,
                onClick = onDeviceOwnerClick
            )

            Spacer(Modifier.height(32.dp))
            Text(
                text = "FocusGuard ${BuildConfig.VERSION_NAME}",
                modifier = Modifier.align(Alignment.CenterHorizontally),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun AccessibilityUnlockDialog(
    onDismiss: () -> Unit,
    onManagePasswords: () -> Unit,
    onUnlocked: () -> Unit
) {
    val context = LocalContext.current
    val authManager = remember(context) { AuthManager(context) }
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isChecking by remember { mutableStateOf(false) }
    var hasPassword by remember { mutableStateOf<Boolean?>(null) }
    var automaticDateTimeEnabled by remember {
        mutableStateOf(AccessibilityProtectionGate.isAutomaticDateAndTimeEnabled(context))
    }

    val passwordRequiredMessage = stringResource(R.string.accessibility_unlock_password_required)
    val wrongPasswordMessage = stringResource(R.string.accessibility_unlock_wrong_password)
    val automaticTimeMessage = stringResource(R.string.accessibility_unlock_auto_time_required)

    LaunchedEffect(authManager) {
        hasPassword = authManager.hasPasswordSet()
    }

    AlertDialog(
        onDismissRequest = {
            if (!isChecking) onDismiss()
        },
        title = {
            Text(stringResource(R.string.accessibility_unlock_dialog_title))
        },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.accessibility_unlock_dialog_description),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        errorMessage = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = {
                        Text(stringResource(R.string.accessibility_unlock_password_label))
                    },
                    singleLine = true,
                    enabled = !isChecking,
                    isError = errorMessage != null,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                )
                if (errorMessage != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = errorMessage.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp
                    )
                }
                if (!automaticDateTimeEnabled) {
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        onClick = {
                            runCatching {
                                context.startActivity(Intent(Settings.ACTION_DATE_SETTINGS))
                            }
                        }
                    ) {
                        Text(stringResource(R.string.accessibility_unlock_open_date_settings))
                    }
                }
                if (hasPassword == false) {
                    TextButton(onClick = onManagePasswords) {
                        Text(stringResource(R.string.accessibility_unlock_manage_passwords))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !isChecking && password.isNotBlank(),
                onClick = {
                    scope.launch {
                        isChecking = true
                        errorMessage = null
                        automaticDateTimeEnabled =
                            AccessibilityProtectionGate.isAutomaticDateAndTimeEnabled(context)
                        if (!automaticDateTimeEnabled) {
                            AccessibilityProtectionGate.revoke(context)
                            errorMessage = automaticTimeMessage
                            isChecking = false
                            return@launch
                        }

                        val passwordConfigured = authManager.hasPasswordSet()
                        hasPassword = passwordConfigured
                        if (!passwordConfigured) {
                            errorMessage = passwordRequiredMessage
                            isChecking = false
                            return@launch
                        }

                        if (!authManager.verifyPassword(password)) {
                            errorMessage = wrongPasswordMessage
                            isChecking = false
                            return@launch
                        }

                        when (AccessibilityProtectionGate.requestTemporaryUnlock(context)) {
                            AccessibilityProtectionGate.UnlockResult.UNLOCKED -> onUnlocked()
                            AccessibilityProtectionGate.UnlockResult.AUTOMATIC_DATE_TIME_REQUIRED -> {
                                automaticDateTimeEnabled = false
                                errorMessage = automaticTimeMessage
                            }
                        }
                        isChecking = false
                    }
                }
            ) {
                if (isChecking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(stringResource(R.string.accessibility_unlock_action))
                }
            }
        },
        dismissButton = {
            TextButton(
                enabled = !isChecking,
                onClick = onDismiss
            ) {
                Text(stringResource(R.string.accessibility_unlock_cancel))
            }
        }
    )
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
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(16.dp))
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
