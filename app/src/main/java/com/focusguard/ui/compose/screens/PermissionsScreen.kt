package com.focusguard.ui.compose.screens

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.focusguard.R
import com.focusguard.admin.DeviceOwnerManager
import com.focusguard.ui.compose.theme.*
import com.focusguard.utils.PermissionUtils

data class PermissionState(
    val accessibility: Boolean = false,
    val usageAccess: Boolean = false,
    val deviceAdmin: Boolean = false,
    val batteryOptimization: Boolean = false
)

@Composable
fun PermissionsScreen(
    onFinish: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val deviceOwnerManager = remember { DeviceOwnerManager(context) }

    var permState by remember { mutableStateOf(PermissionState()) }
    var resumeKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(resumeKey) {
        permState = PermissionState(
            accessibility = PermissionUtils.isAccessibilityServiceEnabled(context),
            usageAccess = PermissionUtils.isUsageAccessEnabled(context),
            deviceAdmin = deviceOwnerManager.isDeviceAdminActive() || deviceOwnerManager.isDeviceOwnerActive(),
            batteryOptimization = PermissionUtils.isBatteryOptimizationIgnored(context)
        )
    }

    DisposableEffect(lifecycleOwner) {
        val callback = object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                resumeKey++
            }
        }
        lifecycleOwner.lifecycle.addObserver(callback)
        onDispose { lifecycleOwner.lifecycle.removeObserver(callback) }
    }

    var showRestrictedDialog by remember { mutableStateOf(false) }

    if (showRestrictedDialog) {
        AlertDialog(
            onDismissRequest = { showRestrictedDialog = false },
            containerColor = DarkSurface,
            titleContentColor = AccentCyan,
            textContentColor = TextPrimary,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Settings, contentDescription = null, tint = AccentCyan)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.permission_restricted_title), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
            },
            text = {
                Column {
                    Text(
                        stringResource(R.string.permission_restricted_intro),
                        fontSize = 14.sp,
                        color = TextSecondary
                    )
                    Spacer(Modifier.height(16.dp))

                    PermissionStep(number = 1, text = stringResource(R.string.permission_restricted_step_1))
                    Spacer(Modifier.height(12.dp))
                    PermissionStep(number = 2, text = stringResource(R.string.permission_restricted_step_2))
                    Spacer(Modifier.height(12.dp))
                    PermissionStep(number = 3, text = stringResource(R.string.permission_restricted_step_3))
                    Spacer(Modifier.height(12.dp))
                    PermissionStep(number = 4, text = stringResource(R.string.permission_restricted_step_4))

                    Spacer(Modifier.height(16.dp))
                    Surface(
                        color = DangerRed.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            stringResource(R.string.permission_restricted_tip),
                            color = DangerRed,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showRestrictedDialog = false
                        try {
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                            context.startActivity(intent)
                        } catch (_: Exception) {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentCyan)
                ) {
                    Text(stringResource(R.string.permission_open_settings), color = DarkBg)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showRestrictedDialog = false
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                ) {
                    Text(stringResource(R.string.permission_already_allowed), color = TextSecondary)
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            Icon(
                painter = painterResource(id = R.drawable.ic_shield),
                contentDescription = stringResource(R.string.content_focusguard_logo),
                modifier = Modifier.size(72.dp),
                tint = AccentCyan
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.permissions_welcome_title),
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.permissions_welcome_desc),
                fontSize = 14.sp,
                color = TextSecondary,
                textAlign = TextAlign.Center,
                lineHeight = 20.sp,
                modifier = Modifier.padding(bottom = 32.dp)
            )

            PermissionCard(
                number = 1,
                title = stringResource(R.string.permission_accessibility_title),
                description = stringResource(R.string.permission_accessibility_desc),
                detail = stringResource(R.string.permission_accessibility_detail),
                badge = stringResource(R.string.permissions_badge_required),
                badgeColor = DangerRed,
                isGranted = permState.accessibility,
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && isAccessibilityServiceRestricted(context)) {
                        showRestrictedDialog = true
                    } else {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            PermissionCard(
                number = 2,
                title = stringResource(R.string.permission_usage_access_title),
                description = stringResource(R.string.permission_usage_access_desc),
                detail = stringResource(R.string.permission_usage_access_detail),
                badge = stringResource(R.string.permissions_badge_required),
                badgeColor = DangerRed,
                isGranted = permState.usageAccess,
                onClick = {
                    try {
                        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    }
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            PermissionCard(
                number = 3,
                title = stringResource(R.string.permission_battery_title),
                description = stringResource(R.string.permission_battery_desc),
                detail = stringResource(R.string.permission_battery_detail),
                badge = stringResource(R.string.permissions_badge_recommended),
                badgeColor = WarningAmber,
                isGranted = permState.batteryOptimization,
                onClick = {
                    try {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            PermissionCard(
                number = 4,
                title = stringResource(R.string.permission_device_admin_title),
                description = stringResource(R.string.permission_device_admin_desc),
                detail = stringResource(R.string.permission_device_admin_detail),
                badge = stringResource(R.string.permissions_badge_advanced),
                badgeColor = AccentPurple,
                isGranted = permState.deviceAdmin,
                onClick = {
                    if (!deviceOwnerManager.isDeviceAdminActive()) {
                        deviceOwnerManager.requestDeviceAdmin()
                    }
                }
            )
        }

        var showSkipOverlay by rememberSaveable { mutableStateOf(false) }
        var showBatteryOverlay by rememberSaveable { mutableStateOf(false) }
        var skipInteracted by rememberSaveable { mutableStateOf(false) }
        val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
        ) { _ ->
            onFinish()
        }

        if (showBatteryOverlay) {
            OverlayPermissionDialog(
                icon = { Icon(Icons.Default.BatteryChargingFull, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(48.dp)) },
                title = stringResource(R.string.permission_battery_overlay_title),
                description = stringResource(R.string.permission_battery_overlay_desc),
                onDeny = {
                    showBatteryOverlay = false
                    showSkipOverlay = true
                },
                onAllow = {
                    try {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }
                    showBatteryOverlay = false
                    showSkipOverlay = true
                }
            )
        }

        if (showSkipOverlay) {
            OverlayPermissionDialog(
                icon = { Icon(Icons.Default.NotificationsActive, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(48.dp)) },
                title = stringResource(R.string.permission_notifications_title),
                description = stringResource(R.string.permission_notifications_desc),
                onDeny = { onFinish() },
                onAllow = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        onFinish()
                    }
                }
            )
        }

        TextButton(
            onClick = {
                if (permState.accessibility && permState.usageAccess) {
                    onFinish()
                } else {
                    if (skipInteracted) {
                        onFinish()
                    } else {
                        skipInteracted = true
                        if (!permState.batteryOptimization) {
                            showBatteryOverlay = true
                        } else {
                            showSkipOverlay = true
                        }
                    }
                }
            },
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(bottom = 20.dp)
        ) {
            Text(
                text = if (permState.accessibility && permState.usageAccess) stringResource(R.string.permissions_finish) else stringResource(R.string.permissions_skip),
                fontSize = 14.sp,
                color = AccentCyan,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun PermissionStep(number: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(24.dp)
                .background(DarkCardElevated, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text("$number", color = AccentCyan, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 14.sp)
    }
}

@Composable
private fun OverlayPermissionDialog(
    icon: @Composable () -> Unit,
    title: String,
    description: String,
    onDeny: () -> Unit,
    onAllow: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.8f))
            .clickable(enabled = false) {},
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.padding(32.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            border = BorderStroke(1.dp, CardBorder)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                icon()
                Spacer(Modifier.height(16.dp))
                Text(
                    title,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    description,
                    fontSize = 14.sp,
                    color = TextSecondary,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(24.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = onDeny,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, CardBorder)
                    ) {
                        Text(stringResource(R.string.permission_deny), color = TextSecondary)
                    }
                    Button(
                        onClick = onAllow,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentCyan)
                    ) {
                        Text(stringResource(R.string.permission_allow), color = DarkBg)
                    }
                }
            }
        }
    }
}

private fun isAccessibilityServiceRestricted(context: Context): Boolean {
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val appOps = context.getSystemService(android.app.AppOpsManager::class.java)
            val mode = appOps.noteOpNoThrow(
                "android:access_restricted_settings",
                android.os.Process.myUid(),
                context.packageName
            )
            mode != android.app.AppOpsManager.MODE_ALLOWED
        } else {
            false
        }
    } catch (_: Exception) {
        true
    }
}

@Composable
fun PermissionCard(
    number: Int,
    title: String,
    description: String,
    detail: String,
    badge: String,
    badgeColor: Color,
    isGranted: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        border = BorderStroke(1.dp, CardBorder)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(
                        color = DarkCardElevated,
                        shape = RoundedCornerShape(10.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "$number",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = AccentCyan
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(color = badgeColor.copy(alpha = 0.14f), shape = RoundedCornerShape(50)) {
                        Text(
                            text = badge,
                            color = badgeColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = description,
                    fontSize = 12.sp,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = detail,
                    fontSize = 11.sp,
                    color = TextHint
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Button(
                onClick = onClick,
                enabled = !isGranted,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isGranted) SuccessGreen else AccentCyan,
                    disabledContainerColor = SuccessGreen.copy(alpha = 0.7f)
                ),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                modifier = Modifier.height(36.dp)
            ) {
                if (isGranted) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
                Text(
                    text = if (isGranted) stringResource(R.string.action_granted) else stringResource(R.string.action_enable),
                    fontSize = 12.sp
                )
            }
        }
    }
}
