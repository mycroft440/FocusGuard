package com.focusguard.ui.compose.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Timelapse
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.focusguard.R
import com.focusguard.manager.BlockingSessionManager
import com.focusguard.security.AuthManager
import com.focusguard.security.BlockCountdownPolicy
import com.focusguard.ui.compose.theme.AccentCyan
import com.focusguard.ui.compose.theme.CardBorder
import com.focusguard.ui.compose.theme.DangerRed
import com.focusguard.ui.compose.theme.DarkBg
import com.focusguard.ui.compose.theme.DarkCard
import com.focusguard.ui.compose.theme.TextHint
import com.focusguard.ui.compose.theme.TextPrimary
import com.focusguard.ui.compose.theme.TextSecondary
import com.focusguard.ui.compose.theme.WarningAmber

/**
 * The three kinds of protection, as the user chooses between them.
 *
 * Each carries its own colour and icon so the type is recognisable before the
 * text is read — the same card looks the same on the home screen and at the top
 * of its own list.
 */
enum class BlockTypeUi(
    val titleRes: Int,
    val subtitleRes: Int,
    val emptyRes: Int,
    val actionRes: Int,
    val icon: ImageVector,
    val accent: Color
) {
    PASSWORD(
        titleRes = R.string.block_type_password_title,
        subtitleRes = R.string.block_type_password_subtitle,
        emptyRes = R.string.block_type_password_empty,
        actionRes = R.string.block_type_password_action,
        icon = Icons.Outlined.Lock,
        accent = AccentCyan
    ),
    DAILY_LIMIT(
        titleRes = R.string.block_type_limit_title,
        subtitleRes = R.string.block_type_limit_subtitle,
        emptyRes = R.string.block_type_limit_empty,
        actionRes = R.string.block_type_limit_action,
        icon = Icons.Outlined.Timelapse,
        accent = WarningAmber
    ),
    DOPAMINE_FAST(
        titleRes = R.string.block_type_fast_title,
        subtitleRes = R.string.block_type_fast_subtitle,
        emptyRes = R.string.block_type_fast_empty,
        actionRes = R.string.block_type_fast_action,
        icon = Icons.Outlined.HourglassEmpty,
        accent = DangerRed
    );

    fun entriesOf(overview: BlockingSessionManager.BlockOverview) = when (this) {
        PASSWORD -> overview.passwordEntries
        DAILY_LIMIT -> overview.dailyLimitEntries
        DOPAMINE_FAST -> overview.dopamineFastEntries
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockTypeDetailScreen(
    type: BlockTypeUi,
    onAddClick: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val sessionManager = remember(context) { BlockingSessionManager.getInstance(context) }
    var entries by remember {
        mutableStateOf<List<BlockingSessionManager.BlockOverview.Entry>?>(null)
    }

    LaunchedEffect(type) {
        entries = runCatching { type.entriesOf(sessionManager.getBlockOverview()) }
            .getOrDefault(emptyList())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(type.titleRes), color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                            tint = TextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBg)
            )
        },
        containerColor = DarkBg
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp)
        ) {
            BlockTypeHeader(type)

            // Só na proteção por senha: os dois ajustes abaixo mudam como um app
            // protegido é aberto, então pertencem a esta tela e não a um menu
            // distante de configurações.
            if (type == BlockTypeUi.PASSWORD) {
                Spacer(Modifier.height(16.dp))
                PasswordProtectionToggles(accent = type.accent)
            }

            Spacer(Modifier.height(20.dp))

            Button(
                onClick = onAddClick,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = type.accent)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, tint = DarkBg)
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(type.actionRes),
                    color = DarkBg,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.height(24.dp))

            val current = entries
            when {
                current == null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator(color = type.accent)
                }

                current.isEmpty() -> EmptyBlockList(type)

                else -> {
                    Text(
                        text = stringResource(R.string.block_type_already_blocked, current.size),
                        color = TextSecondary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 10.dp)
                    )
                    LazyColumn(
                        contentPadding = PaddingValues(bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(current, key = { it.identifier }) { entry ->
                            BlockedEntryRow(entry = entry, accent = type.accent)
                        }
                    }
                }
            }
        }
    }
}

/**
 * The two switches that change how a password-protected app is opened.
 *
 * Both default to off. The intruder selfie needs the camera permission, so the
 * switch only turns on once Android grants it — flipping the UI first would
 * promise a photo the app cannot take.
 */
@Composable
private fun PasswordProtectionToggles(accent: Color) {
    val context = LocalContext.current
    val authManager = remember(context) { AuthManager(context) }

    var selfieEnabled by remember { mutableStateOf(authManager.isPhotoCaptureEnabled()) }
    var biometricEnabled by remember {
        mutableStateOf(authManager.isBiometricAppUnlockEnabled())
    }
    val biometricAvailable = remember { authManager.isBiometricAvailable() }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        selfieEnabled = granted
        authManager.setPhotoCaptureEnabled(granted)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        border = BorderStroke(1.dp, CardBorder)
    ) {
        Column(Modifier.padding(4.dp)) {
            ToggleRow(
                title = stringResource(R.string.limits_intruder_selfie),
                subtitle = stringResource(R.string.limits_intruder_selfie_desc),
                checked = selfieEnabled,
                enabled = true,
                accent = accent,
                onCheckedChange = { enable ->
                    if (enable) {
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    } else {
                        selfieEnabled = false
                        authManager.setPhotoCaptureEnabled(false)
                    }
                }
            )
            ToggleRow(
                title = stringResource(R.string.biometric_app_unlock_title),
                subtitle = if (biometricAvailable) {
                    stringResource(R.string.biometric_app_unlock_subtitle)
                } else {
                    stringResource(R.string.biometric_app_unlock_unavailable)
                },
                checked = biometricEnabled,
                enabled = biometricAvailable,
                accent = accent,
                onCheckedChange = { enable ->
                    authManager.setBiometricAppUnlockEnabled(enable)
                    biometricEnabled = enable
                }
            )
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    accent: Color,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                color = if (enabled) TextPrimary else TextHint,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(text = subtitle, color = TextSecondary, fontSize = 12.sp)
        }
        Spacer(Modifier.width(10.dp))
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = DarkBg,
                checkedTrackColor = accent
            )
        )
    }
}

@Composable
private fun BlockTypeHeader(type: BlockTypeUi) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(type.accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(type.icon, contentDescription = null, tint = type.accent)
        }
        Spacer(Modifier.width(14.dp))
        Text(
            text = stringResource(type.subtitleRes),
            color = TextSecondary,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun EmptyBlockList(type: BlockTypeUi) {
    Box(Modifier.fillMaxSize(), Alignment.TopCenter) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(top = 32.dp)
        ) {
            Icon(
                type.icon,
                contentDescription = null,
                tint = TextHint,
                modifier = Modifier.size(40.dp)
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(type.emptyRes),
                color = TextHint,
                fontSize = 14.sp
            )
        }
    }
}

@Composable
private fun BlockedEntryRow(
    entry: BlockingSessionManager.BlockOverview.Entry,
    accent: Color
) {
    val context = LocalContext.current
    val label = remember(entry.identifier) {
        if (entry.isWebsite) {
            entry.identifier
        } else {
            runCatching {
                val pm = context.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(entry.identifier, 0)).toString()
            }.getOrDefault(entry.identifier)
        }
    }
    val iconBitmap = remember(entry.identifier) {
        if (entry.isWebsite) {
            null
        } else {
            runCatching {
                context.packageManager.getApplicationIcon(entry.identifier)
                    .toBitmap(48, 48)
                    .asImageBitmap()
            }.getOrNull()
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        border = BorderStroke(1.dp, CardBorder)
    ) {
        Row(
            modifier = Modifier.padding(14.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (iconBitmap != null) {
                androidx.compose.foundation.Image(
                    bitmap = iconBitmap,
                    contentDescription = null,
                    modifier = Modifier.size(34.dp).clip(RoundedCornerShape(8.dp))
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(accent.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label.take(1).uppercase(),
                        color = accent,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = label,
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = entryStatusText(entry),
                    color = accent,
                    fontSize = 12.sp
                )
            }
        }
    }
}

/**
 * The "how long is left" line.
 *
 * Every type gets an honest answer rather than a blank: a daily limit has an
 * allowance instead of a deadline, and a password block lasts until the user ends
 * it. Leaving those empty would read as "no idea", which is worse than the truth.
 */
@Composable
private fun entryStatusText(
    entry: BlockingSessionManager.BlockOverview.Entry
): String {
    entry.dailyLimitMinutes?.let { minutes ->
        return stringResource(R.string.block_type_daily_allowance, minutes)
    }

    return when (val remaining = BlockCountdownPolicy.remaining(entry.unlockAtMillis)) {
        null -> stringResource(R.string.block_type_until_you_end_it)
        is BlockCountdownPolicy.Remaining.Days ->
            stringResource(R.string.block_type_remaining_days, remaining.days)
        is BlockCountdownPolicy.Remaining.Hours ->
            stringResource(R.string.block_type_remaining_hours, remaining.hours)
        BlockCountdownPolicy.Remaining.LessThanAnHour ->
            stringResource(R.string.block_type_remaining_less_than_hour)
        BlockCountdownPolicy.Remaining.Elapsed ->
            stringResource(R.string.block_type_remaining_elapsed)
    }
}
