package com.focusguard.ui.compose.screens

import kotlin.OptIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.R
import com.focusguard.ui.compose.layout.FocusGuardScreenScaffold
import com.focusguard.ui.compose.layout.FocusGuardScrollableContent
import com.focusguard.ui.compose.layout.FocusGuardSectionHeader
import com.focusguard.ui.compose.theme.*

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
    FocusGuardScreenScaffold(
        title = stringResource(R.string.nav_settings),
        onBack = onBack
    ) { paddingValues ->
        FocusGuardScrollableContent(paddingValues = paddingValues) {
            FocusGuardSectionHeader(title = stringResource(R.string.settings_category_general))
            SettingsItem(
                icon = Icons.Default.Lock,
                title = stringResource(R.string.manage_passwords),
                subtitle = stringResource(R.string.settings_password_subtitle),
                onClick = onPasswordManagementClick
            )
            SettingsItem(
                icon = Icons.Default.Language,
                title = stringResource(R.string.language_settings),
                subtitle = stringResource(R.string.settings_language_subtitle),
                onClick = onLanguageClick
            )

            Spacer(modifier = Modifier.height(24.dp))

            FocusGuardSectionHeader(title = stringResource(R.string.settings_category_blocking))
            SettingsItem(
                icon = Icons.Default.Palette,
                title = stringResource(R.string.block_customization),
                subtitle = stringResource(R.string.settings_block_customization_subtitle),
                onClick = onBlockCustomizationClick
            )

            Spacer(modifier = Modifier.height(24.dp))

            FocusGuardSectionHeader(title = stringResource(R.string.settings_category_advanced_security))
            SettingsItem(
                icon = Icons.Default.Security,
                title = stringResource(R.string.limits_and_security),
                subtitle = stringResource(R.string.settings_limits_subtitle),
                onClick = onLimitsClick
            )
            SettingsItem(
                icon = Icons.Default.PhotoCamera,
                title = stringResource(R.string.intruder_log),
                subtitle = stringResource(R.string.settings_intruder_log_subtitle),
                onClick = onIntruderLogClick
            )

            Spacer(modifier = Modifier.height(24.dp))

            FocusGuardSectionHeader(
                title = stringResource(R.string.settings_category_danger),
                color = DangerRed
            )
            SettingsItem(
                icon = Icons.Default.Warning,
                title = stringResource(R.string.nuclear_protection),
                subtitle = stringResource(R.string.settings_nuclear_subtitle),
                iconTint = DangerRed,
                titleColor = DangerRed,
                onClick = onDeviceOwnerClick
            )

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = stringResource(R.string.settings_app_version),
                modifier = Modifier.align(Alignment.CenterHorizontally),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
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
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (titleColor == Color.Unspecified) MaterialTheme.colorScheme.onSurface else titleColor
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