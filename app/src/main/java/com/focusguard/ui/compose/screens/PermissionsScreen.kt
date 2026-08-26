package com.focusguard.ui.compose.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.focusguard.R
import com.focusguard.admin.DeviceOwnerManager
import com.focusguard.security.DeviceAdminActivationWindow
import com.focusguard.security.ProtectionPermissionGate
import com.focusguard.ui.compose.theme.AccentCyan
import com.focusguard.ui.compose.theme.CardBorder
import com.focusguard.ui.compose.theme.DangerRed
import com.focusguard.ui.compose.theme.DarkBg
import com.focusguard.ui.compose.theme.FocusCard
import com.focusguard.ui.compose.theme.DarkCardElevated
import com.focusguard.ui.compose.theme.DarkSurface
import com.focusguard.ui.compose.theme.SuccessGreen
import com.focusguard.ui.compose.theme.TextHint
import com.focusguard.ui.compose.theme.TextPrimary
import com.focusguard.ui.compose.theme.TextSecondary
import com.focusguard.ui.compose.theme.WarningAmber
import com.focusguard.utils.FocusGuardLogger

enum class PermissionFlowMode {
    FullSetup,
    PendingProtection
}

internal enum class PermissionStepType {
    Notifications,
    BatteryOptimization,
    Accessibility,
    UsageAccess,
    DeviceAdmin
}

private val orderedSteps = listOf(
    PermissionStepType.Accessibility,
    PermissionStepType.UsageAccess,
    PermissionStepType.Notifications,
    PermissionStepType.BatteryOptimization,
    PermissionStepType.DeviceAdmin
)

data class PermissionState(
    val notifications: Boolean = false,
    val batteryOptimization: Boolean = false,
    val accessibility: Boolean = false,
    val usageAccess: Boolean = false,
    val deviceAdmin: Boolean = false
)

@Composable
fun PermissionsScreen(
    flowMode: PermissionFlowMode = PermissionFlowMode.FullSetup,
    onFinish: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val deviceOwnerManager = remember { DeviceOwnerManager.getInstance(context) }
    val initialPermissionState = remember {
        readPermissionState(context)
    }
    val steps = remember(flowMode, initialPermissionState) {
        permissionStepsForFlow(flowMode, initialPermissionState)
    }
    val initialStepIndex = remember(steps, initialPermissionState) {
        firstActionablePermissionStepIndex(
            steps = steps,
            state = initialPermissionState
        )
    }

    var permissionState by remember { mutableStateOf(initialPermissionState) }
    var currentStepIndex by rememberSaveable(flowMode, steps) {
        mutableIntStateOf(initialStepIndex)
    }
    var pendingExternalStepName by rememberSaveable { mutableStateOf<String?>(null) }
    var unsuccessfulStepName by rememberSaveable { mutableStateOf<String?>(null) }
    var showRestrictedDialog by remember { mutableStateOf(false) }
    var resumeCount by remember { mutableIntStateOf(0) }

    fun refreshPermissions(): PermissionState {
        val updated = readPermissionState(context)
        permissionState = updated
        return updated
    }

    fun advanceToNextStep() {
        unsuccessfulStepName = null
        currentStepIndex = (currentStepIndex + 1).coerceAtMost(steps.size)
    }

    fun stayOnStep(step: PermissionStepType) {
        val index = steps.indexOf(step)
        if (index >= 0) {
            currentStepIndex = index
        }
    }

    fun skipAlreadyGrantedSteps(state: PermissionState) {
        currentStepIndex = firstActionablePermissionStepIndex(
            steps = steps,
            state = state,
            startIndex = currentStepIndex
        )
    }

    fun applyPermissionResult(step: PermissionStepType, state: PermissionState) {
        if (isStepGranted(step, state)) {
            advanceToNextStep()
            skipAlreadyGrantedSteps(state)
        } else {
            unsuccessfulStepName = step.name
            stayOnStep(step)
        }
    }

    LaunchedEffect(Unit) {
        val updated = refreshPermissions()
        skipAlreadyGrantedSteps(updated)
    }

    LaunchedEffect(resumeCount) {
        val pendingStep = pendingExternalStepName?.let { stepName ->
            runCatching { PermissionStepType.valueOf(stepName) }.getOrNull()
        }
        if (pendingStep == PermissionStepType.DeviceAdmin) {
            DeviceAdminActivationWindow.close(context)
        }
        val updated = refreshPermissions()

        if (pendingStep != null) {
            pendingExternalStepName = null
            applyPermissionResult(pendingStep, updated)
        } else {
            skipAlreadyGrantedSteps(updated)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val callback = object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                resumeCount++
            }
        }
        lifecycleOwner.lifecycle.addObserver(callback)
        onDispose { lifecycleOwner.lifecycle.removeObserver(callback) }
    }

    val currentStep = steps.getOrNull(currentStepIndex)
    val progress = if (currentStep == null) {
        1f
    } else {
        (currentStepIndex + 1).toFloat() / steps.size.toFloat()
    }

    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        applyPermissionResult(PermissionStepType.Notifications, refreshPermissions())
    }

    val deviceAdminLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        DeviceAdminActivationWindow.close(context)
        applyPermissionResult(PermissionStepType.DeviceAdmin, refreshPermissions())
    }

    fun markPending(step: PermissionStepType) {
        unsuccessfulStepName = null
        pendingExternalStepName = step.name
    }

    fun openAuthorizedDeviceAdminSettings(): Boolean {
        if (!DeviceAdminActivationWindow.open(context)) {
            FocusGuardLogger.log(
                "Permissions",
                "Não foi possível autorizar temporariamente a ativação do administrador"
            )
            return false
        }
        markPending(PermissionStepType.DeviceAdmin)
        val launched = deviceOwnerManager.openDeviceAdminSettings(context)
        if (!launched) {
            DeviceAdminActivationWindow.close(context)
            pendingExternalStepName = null
        }
        return launched
    }

    fun openCurrentStep(step: PermissionStepType) {
        // Never trust the state captured when this screen was first composed.
        // Android preserves most grants across app updates, and a grant can also
        // change while the screen is open. Re-read the platform immediately
        // before launching any permission dialog or Settings page.
        val latestState = refreshPermissions()
        if (!shouldRequestPermission(step, latestState)) {
            applyPermissionResult(step, latestState)
            return
        }

        when (step) {
            PermissionStepType.Notifications -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    val updated = refreshPermissions()
                    advanceToNextStep()
                    skipAlreadyGrantedSteps(updated)
                }
            }
            PermissionStepType.BatteryOptimization -> {
                markPending(step)
                openBatterySettings(context)
            }
            PermissionStepType.Accessibility -> {
                markPending(step)
                openAccessibilitySettings(context)
            }
            PermissionStepType.UsageAccess -> {
                markPending(step)
                openUsageAccessSettings(context)
            }
            PermissionStepType.DeviceAdmin -> {
                unsuccessfulStepName = null
                if (!DeviceAdminActivationWindow.open(context)) {
                    unsuccessfulStepName = step.name
                    return
                }
                runCatching {
                    deviceAdminLauncher.launch(deviceOwnerManager.createDeviceAdminActivationIntent())
                }.onFailure { error ->
                    DeviceAdminActivationWindow.close(context)
                    FocusGuardLogger.logError(
                        "Permissions",
                        "Falha ao abrir confirmação de Device Admin",
                        error
                    )
                    if (!openAuthorizedDeviceAdminSettings()) {
                        unsuccessfulStepName = step.name
                    }
                }
            }
        }
    }

    if (showRestrictedDialog) {
        RestrictedAccessibilityDialog(
            onOpenAppSettings = {
                showRestrictedDialog = false
                markPending(PermissionStepType.Accessibility)
                openAppInfo(context)
            },
            onOpenAccessibilitySettings = {
                showRestrictedDialog = false
                markPending(PermissionStepType.Accessibility)
                openAccessibilitySettings(context)
            },
            onDismiss = {
                showRestrictedDialog = false
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(DarkBg)) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = AccentCyan,
                trackColor = DarkSurface
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (currentStep == null) {
                    stringResource(R.string.permissions_review_complete)
                } else {
                    stringResource(
                        R.string.permissions_step_progress,
                        currentStepIndex + 1,
                        steps.size
                    )
                },
                color = TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(32.dp))
            Icon(
                painter = painterResource(id = R.drawable.ic_shield),
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = AccentCyan
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(
                    if (flowMode == PermissionFlowMode.PendingProtection) {
                        R.string.pending_permissions_title
                    } else {
                        R.string.permissions_welcome_title
                    }
                ),
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = when {
                    currentStep == null -> stringResource(R.string.permissions_reviewed_desc)
                    flowMode == PermissionFlowMode.PendingProtection -> {
                        stringResource(R.string.permissions_pending_flow_desc)
                    }
                    else -> stringResource(R.string.permissions_flow_desc)
                },
                fontSize = 14.sp,
                color = TextSecondary,
                textAlign = TextAlign.Center,
                lineHeight = 20.sp,
                modifier = Modifier.padding(bottom = 32.dp)
            )

            AnimatedContent(
                targetState = currentStep,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "permission_step_content"
            ) { step ->
                if (step == null) {
                    PermissionsSummaryCard(
                        permissionState = permissionState,
                        steps = steps,
                        pendingProtectionOnly = flowMode == PermissionFlowMode.PendingProtection,
                        onReviewEssentials = {
                            unsuccessfulStepName = null
                            currentStepIndex = firstMissingProtectionStepIndex(permissionState, steps)
                        },
                        onFinish = onFinish
                    )
                } else {
                    val ui = permissionStepUi(step)
                    SequentialPermissionCard(
                        stepNumber = currentStepIndex + 1,
                        totalSteps = steps.size,
                        title = ui.title,
                        description = ui.description,
                        detail = ui.detail,
                        badge = ui.badge,
                        badgeColor = ui.badgeColor,
                        actionLabel = ui.actionLabel,
                        isGranted = isStepGranted(step, permissionState),
                        unsuccessful = unsuccessfulStepName == step.name,
                        manualHelpLabel = when {
                            step == PermissionStepType.Accessibility &&
                                unsuccessfulStepName == step.name &&
                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                                stringResource(R.string.permission_accessibility_help)
                            }
                            step == PermissionStepType.DeviceAdmin &&
                                unsuccessfulStepName == step.name -> {
                                stringResource(R.string.permission_device_admin_manual_action)
                            }
                            step == PermissionStepType.Notifications &&
                                unsuccessfulStepName == step.name -> {
                                stringResource(R.string.permission_notifications_settings_action)
                            }
                            else -> null
                        },
                        onManualHelp = {
                            when (step) {
                                PermissionStepType.Accessibility -> showRestrictedDialog = true
                                PermissionStepType.DeviceAdmin -> {
                                    if (!openAuthorizedDeviceAdminSettings()) {
                                        unsuccessfulStepName = step.name
                                    }
                                }
                                PermissionStepType.Notifications -> {
                                    markPending(step)
                                    openNotificationSettings(context)
                                }
                                else -> Unit
                            }
                        },
                        onAllow = { openCurrentStep(step) },
                        onSkip = {
                            advanceToNextStep()
                            val updated = refreshPermissions()
                            skipAlreadyGrantedSteps(updated)
                        }
                    )
                }
            }
        }
    }
}

private data class PermissionStepUi(
    val title: String,
    val description: String,
    val detail: String,
    val badge: String,
    val badgeColor: Color,
    val actionLabel: String
)

@Composable
private fun permissionStepUi(step: PermissionStepType): PermissionStepUi {
    return when (step) {
        PermissionStepType.Notifications -> PermissionStepUi(
            title = stringResource(R.string.permission_notifications_title),
            description = stringResource(R.string.permission_notifications_desc),
            detail = stringResource(R.string.permission_notifications_detail),
            badge = stringResource(R.string.permissions_badge_required),
            badgeColor = DangerRed,
            actionLabel = stringResource(R.string.permission_notifications_action)
        )
        PermissionStepType.BatteryOptimization -> PermissionStepUi(
            title = stringResource(R.string.permission_battery_title),
            description = stringResource(R.string.permission_battery_desc),
            detail = stringResource(R.string.permission_battery_detail),
            badge = stringResource(R.string.permissions_badge_required),
            badgeColor = DangerRed,
            actionLabel = stringResource(R.string.permission_battery_action)
        )
        PermissionStepType.Accessibility -> PermissionStepUi(
            title = stringResource(R.string.permission_accessibility_title),
            description = stringResource(R.string.permission_accessibility_desc),
            detail = stringResource(R.string.permission_accessibility_detail),
            badge = stringResource(R.string.permissions_badge_required),
            badgeColor = DangerRed,
            actionLabel = stringResource(R.string.permission_accessibility_action)
        )
        PermissionStepType.UsageAccess -> PermissionStepUi(
            title = stringResource(R.string.permission_usage_access_title),
            description = stringResource(R.string.permission_usage_access_desc),
            detail = stringResource(R.string.permission_usage_access_detail),
            badge = stringResource(R.string.permissions_badge_required),
            badgeColor = DangerRed,
            actionLabel = stringResource(R.string.permission_usage_access_action)
        )
        PermissionStepType.DeviceAdmin -> PermissionStepUi(
            title = stringResource(R.string.permission_device_admin_title),
            description = stringResource(R.string.permission_device_admin_desc),
            detail = stringResource(R.string.permission_device_admin_detail),
            badge = stringResource(R.string.permissions_badge_required),
            badgeColor = DangerRed,
            actionLabel = stringResource(R.string.permission_device_admin_action)
        )
    }
}

@Composable
private fun SequentialPermissionCard(
    stepNumber: Int,
    totalSteps: Int,
    title: String,
    description: String,
    detail: String,
    badge: String,
    badgeColor: Color,
    actionLabel: String,
    isGranted: Boolean,
    unsuccessful: Boolean,
    manualHelpLabel: String?,
    onManualHelp: () -> Unit,
    onAllow: () -> Unit,
    onSkip: () -> Unit
) {
    FocusCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, CardBorder)
    ) {
        Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(
                color = if (isGranted) SuccessGreen.copy(alpha = 0.16f) else AccentCyan.copy(alpha = 0.12f),
                shape = RoundedCornerShape(22.dp)
            ) {
                Icon(
                    imageVector = if (isGranted) Icons.Default.Check else Icons.Default.Settings,
                    contentDescription = null,
                    tint = if (isGranted) SuccessGreen else AccentCyan,
                    modifier = Modifier.padding(18.dp).size(44.dp)
                )
            }
            Spacer(modifier = Modifier.height(18.dp))
            Text(
                text = stringResource(R.string.permissions_step_progress, stepNumber, totalSteps),
                color = AccentCyan,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = title,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(6.dp))
            Surface(color = badgeColor.copy(alpha = 0.14f), shape = RoundedCornerShape(50)) {
                Text(
                    text = badge,
                    color = badgeColor,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(text = description, color = TextSecondary, fontSize = 14.sp, textAlign = TextAlign.Center, lineHeight = 20.sp)
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = detail, color = TextHint, fontSize = 12.sp, textAlign = TextAlign.Center, lineHeight = 18.sp)

            if (unsuccessful) {
                Spacer(modifier = Modifier.height(14.dp))
                Surface(
                    color = WarningAmber.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = WarningAmber,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                stringResource(R.string.permission_not_detected),
                                color = TextPrimary,
                                fontSize = 12.sp,
                                lineHeight = 17.sp,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (manualHelpLabel != null) {
                            TextButton(onClick = onManualHelp) {
                                Text(
                                    manualHelpLabel,
                                    color = AccentCyan,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(20.dp))

            if (isGranted) {
                Surface(color = SuccessGreen.copy(alpha = 0.12f), shape = RoundedCornerShape(14.dp)) {
                    Row(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = SuccessGreen, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.permission_granted), color = SuccessGreen, fontWeight = FontWeight.Bold)
                    }
                }
            } else {
                Button(
                    onClick = onAllow,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentCyan)
                ) {
                    Text(actionLabel, color = DarkBg, fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = onSkip) {
                    Text(stringResource(R.string.permissions_skip), color = TextSecondary)
                }
            }
        }
    }
}

@Composable
private fun PermissionsSummaryCard(
    permissionState: PermissionState,
    steps: List<PermissionStepType>,
    pendingProtectionOnly: Boolean,
    onReviewEssentials: () -> Unit,
    onFinish: () -> Unit
) {
    val grantedCount = countGranted(permissionState, steps)
    val protectionReady = permissionState.hasAllProtectionPermissions()
    val statusColor = if (protectionReady) SuccessGreen else WarningAmber
    FocusCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, CardBorder)
    ) {
        Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(color = statusColor.copy(alpha = 0.12f), shape = RoundedCornerShape(22.dp)) {
                Icon(
                    imageVector = if (protectionReady) Icons.Default.Check else Icons.Default.Warning,
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.padding(18.dp).size(44.dp)
                )
            }
            Spacer(modifier = Modifier.height(18.dp))
            Text(
                text = stringResource(
                    if (protectionReady) {
                        R.string.permissions_ready_title
                    } else {
                        R.string.permissions_incomplete_title
                    }
                ),
                color = TextPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = when {
                    protectionReady && pendingProtectionOnly -> {
                        stringResource(R.string.permissions_pending_resolved_desc)
                    }
                    protectionReady -> {
                        stringResource(R.string.permissions_ready_desc, grantedCount, steps.size)
                    }
                    else -> stringResource(R.string.permissions_incomplete_desc)
                },
                color = TextSecondary,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                lineHeight = 20.sp
            )
            Spacer(modifier = Modifier.height(24.dp))
            if (protectionReady) {
                Button(
                    onClick = onFinish,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentCyan)
                ) {
                    Text(stringResource(R.string.permissions_enter_app), color = DarkBg, fontWeight = FontWeight.Bold)
                }
            } else {
                Button(
                    onClick = onReviewEssentials,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentCyan)
                ) {
                    Text(stringResource(R.string.permissions_review_essentials), color = DarkBg, fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = onFinish) {
                    Text(stringResource(R.string.permissions_continue_limited), color = TextSecondary)
                }
            }
        }
    }
}

@Composable
private fun RestrictedAccessibilityDialog(
    onOpenAppSettings: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkSurface,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Settings, contentDescription = null, tint = AccentCyan)
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.permission_restricted_title),
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = TextPrimary
                )
            }
        },
        text = {
            Column {
                Text(
                    stringResource(R.string.permission_restricted_intro),
                    fontSize = 14.sp,
                    color = TextPrimary
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.permission_restricted_steps_title),
                    fontSize = 13.sp,
                    color = AccentCyan,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(12.dp))
                PermissionStep(number = 1, text = stringResource(R.string.permission_restricted_step_1))
                Spacer(Modifier.height(8.dp))
                PermissionStep(number = 2, text = stringResource(R.string.permission_restricted_step_2))
                Spacer(Modifier.height(8.dp))
                PermissionStep(number = 3, text = stringResource(R.string.permission_restricted_step_3))
                Spacer(Modifier.height(8.dp))
                PermissionStep(number = 4, text = stringResource(R.string.permission_restricted_step_4))
                
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.permission_restricted_tip),
                    fontSize = 11.sp,
                    color = TextSecondary,
                    lineHeight = 14.sp
                )
            }
        },
        confirmButton = {
            Button(onClick = onOpenAppSettings, colors = ButtonDefaults.buttonColors(containerColor = AccentCyan)) {
                Text(stringResource(R.string.permission_open_app_info), color = DarkBg)
            }
        },
        dismissButton = {
            TextButton(onClick = onOpenAccessibilitySettings) {
                Text(stringResource(R.string.permission_accessibility_action), color = TextSecondary)
            }
        }
    )
}

@Composable
private fun PermissionStep(number: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(24.dp).background(DarkCardElevated, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text("$number", color = AccentCyan, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 14.sp, color = TextPrimary)
    }
}

private fun readPermissionState(context: Context): PermissionState {
    val state = ProtectionPermissionGate.read(context)
    return PermissionState(
        notifications = state.notifications,
        batteryOptimization = state.batteryOptimization,
        accessibility = state.accessibility,
        usageAccess = state.usageAccess,
        deviceAdmin = state.deviceAdmin
    )
}

internal fun isStepGranted(step: PermissionStepType, state: PermissionState): Boolean {
    return when (step) {
        PermissionStepType.Notifications -> state.notifications
        PermissionStepType.BatteryOptimization -> state.batteryOptimization
        PermissionStepType.Accessibility -> state.accessibility
        PermissionStepType.UsageAccess -> state.usageAccess
        PermissionStepType.DeviceAdmin -> state.deviceAdmin
    }
}

internal fun shouldRequestPermission(
    step: PermissionStepType,
    state: PermissionState
): Boolean = !isStepGranted(step, state)

internal fun firstActionablePermissionStepIndex(
    steps: List<PermissionStepType>,
    state: PermissionState,
    startIndex: Int = 0
): Int {
    var index = startIndex.coerceIn(0, steps.size)
    while (index < steps.size && !shouldRequestPermission(steps[index], state)) {
        index++
    }
    return index
}

private fun countGranted(state: PermissionState, steps: List<PermissionStepType>): Int {
    return steps.count { step -> isStepGranted(step, state) }
}

private fun PermissionState.hasAllProtectionPermissions(): Boolean =
    notifications && batteryOptimization && accessibility && usageAccess && deviceAdmin

internal fun permissionStepsForFlow(
    flowMode: PermissionFlowMode,
    state: PermissionState
): List<PermissionStepType> {
    return when (flowMode) {
        PermissionFlowMode.FullSetup -> orderedSteps
        PermissionFlowMode.PendingProtection -> orderedSteps.filterNot { step ->
            isStepGranted(step, state)
        }
    }
}

private fun firstMissingProtectionStepIndex(
    state: PermissionState,
    steps: List<PermissionStepType>
): Int {
    return firstActionablePermissionStepIndex(steps = steps, state = state)
}

private fun openAccessibilitySettings(context: Context) {
    runCatching {
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }.recoverCatching {
        context.startActivity(Intent(Settings.ACTION_SETTINGS))
    }
}

private fun openUsageAccessSettings(context: Context) {
    val appSettingsIntent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
        data = Uri.parse("package:${context.packageName}")
    }
    runCatching {
        context.startActivity(appSettingsIntent)
    }.recoverCatching {
        context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    }.recoverCatching {
        context.startActivity(Intent(Settings.ACTION_SETTINGS))
    }
}

private fun openBatterySettings(context: Context) {
    val appSettingsIntent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
        data = Uri.parse("package:${context.packageName}")
    }
    runCatching {
        context.startActivity(appSettingsIntent)
    }.recoverCatching {
        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }.recoverCatching {
        context.startActivity(Intent(Settings.ACTION_SETTINGS))
    }
}

private fun openNotificationSettings(context: Context) {
    val notificationSettings = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    }
    runCatching {
        context.startActivity(notificationSettings)
    }.onFailure {
        openAppInfo(context)
    }
}

private fun openAppInfo(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.parse("package:${context.packageName}")
    }
    runCatching {
        context.startActivity(intent)
    }.recoverCatching {
        openAccessibilitySettings(context)
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
    FocusCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, CardBorder)
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(32.dp).background(color = DarkCardElevated, shape = RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(text = "$number", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AccentCyan)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                Spacer(modifier = Modifier.height(3.dp))
                Text(text = description, fontSize = 12.sp, color = TextSecondary)
                Spacer(modifier = Modifier.height(3.dp))
                Text(text = detail, fontSize = 11.sp, color = TextHint)
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
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                }
                Text(text = if (isGranted) "Concedida" else "Ativar", fontSize = 12.sp)
            }
        }
    }
}
