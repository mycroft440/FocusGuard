package com.focusguard.ui.compose.screens

import android.app.AppOpsManager
import androidx.compose.ui.res.stringResource
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.focusguard.R
import com.focusguard.admin.DeviceOwnerManager
import com.focusguard.ui.compose.theme.AccentCyan
import com.focusguard.ui.compose.theme.AccentPurple
import com.focusguard.ui.compose.theme.CardBorder
import com.focusguard.ui.compose.theme.DangerRed
import com.focusguard.ui.compose.theme.DarkBg
import com.focusguard.ui.compose.theme.DarkCard
import com.focusguard.ui.compose.theme.DarkCardElevated
import com.focusguard.ui.compose.theme.DarkSurface
import com.focusguard.ui.compose.theme.SuccessGreen
import com.focusguard.ui.compose.theme.TextHint
import com.focusguard.ui.compose.theme.TextPrimary
import com.focusguard.ui.compose.theme.TextSecondary
import com.focusguard.ui.compose.theme.WarningAmber
import com.focusguard.utils.PermissionUtils

private enum class PermissionStepType {
    Notifications,
    BatteryOptimization,
    Accessibility,
    UsageAccess,
    DeviceAdmin
}

private val orderedSteps = listOf(
    PermissionStepType.Notifications,
    PermissionStepType.BatteryOptimization,
    PermissionStepType.Accessibility,
    PermissionStepType.UsageAccess,
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
fun PermissionsScreen(onFinish: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val deviceOwnerManager = remember { DeviceOwnerManager.getInstance(context) }
    val steps = remember { orderedSteps }

    var permissionState by remember { mutableStateOf(readPermissionState(context, deviceOwnerManager)) }
    var currentStepIndex by rememberSaveable { mutableIntStateOf(0) }
    var pendingExternalStepName by rememberSaveable { mutableStateOf<String?>(null) }
    var showRestrictedDialog by remember { mutableStateOf(false) }
    var resumeCount by remember { mutableIntStateOf(0) }

    fun refreshPermissions(): PermissionState {
        val updated = readPermissionState(context, deviceOwnerManager)
        permissionState = updated
        return updated
    }

    fun advanceToNextStep() {
        currentStepIndex = (currentStepIndex + 1).coerceAtMost(steps.size)
    }

    fun stayOnStep(step: PermissionStepType) {
        val index = steps.indexOf(step)
        if (index >= 0) {
            currentStepIndex = index
        }
    }

    fun skipAlreadyGrantedSteps(state: PermissionState) {
        while (currentStepIndex < steps.size && isStepGranted(steps[currentStepIndex], state)) {
            currentStepIndex++
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
        val updated = refreshPermissions()

        if (pendingStep != null) {
            pendingExternalStepName = null
            if (isStepGranted(pendingStep, updated)) {
                advanceToNextStep()
                skipAlreadyGrantedSteps(updated)
            } else {
                stayOnStep(pendingStep)
            }
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

    val grantedCount = countGranted(permissionState)
    val currentStep = steps.getOrNull(currentStepIndex)
    val progress = currentStepIndex.coerceAtMost(steps.size).toFloat() / steps.size.toFloat()

    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        val updated = refreshPermissions()
        advanceToNextStep()
        skipAlreadyGrantedSteps(updated)
    }

    fun markPending(step: PermissionStepType) {
        pendingExternalStepName = step.name
    }

    fun openCurrentStep(step: PermissionStepType) {
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
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                    context.startActivity(intent)
                } catch (_: Exception) {
                    context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
            PermissionStepType.Accessibility -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && isAccessibilityServiceRestricted(context)) {
                    showRestrictedDialog = true
                } else {
                    markPending(step)
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            }
            PermissionStepType.UsageAccess -> {
                markPending(step)
                try {
                    val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                    context.startActivity(intent)
                } catch (_: Exception) {
                    context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                }
            }
            PermissionStepType.DeviceAdmin -> {
                markPending(step)
                deviceOwnerManager.requestDeviceAdmin()
            }
        }
    }

    if (showRestrictedDialog) {
        RestrictedAccessibilityDialog(
            onOpenAppSettings = {
                showRestrictedDialog = false
                markPending(PermissionStepType.Accessibility)
                try {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                    context.startActivity(intent)
                } catch (_: Exception) {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            },
            onOpenAccessibilitySettings = {
                showRestrictedDialog = false
                markPending(PermissionStepType.Accessibility)
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
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
                text = "$grantedCount de ${steps.size} concedidas",
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
                text = "Bem-vindo ao FocusGuard",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (currentStep == null) {
                    "Permissões revisadas. Você pode concluir agora."
                } else {
                    "Vamos pedir uma permissão por vez. Ao permitir, negar ou voltar, a próxima etapa será exibida."
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
                label = stringResource(R.string.permission_step_content)
            ) { step ->
                if (step == null) {
                    PermissionsSummaryCard(
                        permissionState = permissionState,
                        totalSteps = steps.size,
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
                        isGranted = isStepGranted(step, permissionState),
                        onAllow = { openCurrentStep(step) },
                        onDeny = {
                            advanceToNextStep()
                            refreshPermissions()
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
    val badgeColor: Color
)

@Composable
private fun permissionStepUi(step: PermissionStepType): PermissionStepUi {
    return when (step) {
        PermissionStepType.Notifications -> PermissionStepUi(
            title = stringResource(R.string.notificacoes),
            description = stringResource(R.string.permite_avisos_importantes_quando_sessoe),
            detail = "Esta permissão pode ser aceita diretamente no app.",
            badge = "Recomendado",
            badgeColor = WarningAmber
        )
        PermissionStepType.BatteryOptimization -> PermissionStepUi(
            title = stringResource(R.string.bateria),
            description = stringResource(R.string.evita_que_o_android_pause_o_focusguard_e),
            detail = "Pode abrir uma confirmação do sistema para manter a proteção ativa.",
            badge = "Recomendado",
            badgeColor = WarningAmber
        )
        PermissionStepType.Accessibility -> PermissionStepUi(
            title = stringResource(R.string.permission_accessibility_title),
            description = stringResource(R.string.permission_accessibility_desc),
            detail = "Sem esta permissão, o bloqueio em tempo real pode não funcionar.",
            badge = "Essencial",
            badgeColor = DangerRed
        )
        PermissionStepType.UsageAccess -> PermissionStepUi(
            title = stringResource(R.string.permission_usage_access_title),
            description = stringResource(R.string.permite_medir_o_tempo_usado_em_cada_app),
            detail = "Necessário para insights e limites diários.",
            badge = "Essencial",
            badgeColor = DangerRed
        )
        PermissionStepType.DeviceAdmin -> PermissionStepUi(
            title = stringResource(R.string.administrador),
            description = stringResource(R.string.reforca_o_bloqueio_e_reduz_formas_de_bur),
            detail = "Recomendado para bloqueios rigorosos.",
            badge = "Avançado",
            badgeColor = AccentPurple
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
    isGranted: Boolean,
    onAllow: () -> Unit,
    onDeny: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
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
                text = "Etapa $stepNumber de $totalSteps",
                color = AccentCyan,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    textAlign = TextAlign.Center
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
            Spacer(modifier = Modifier.height(10.dp))
            Text(text = description, color = TextSecondary, fontSize = 14.sp, textAlign = TextAlign.Center, lineHeight = 20.sp)
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = detail, color = TextHint, fontSize = 12.sp, textAlign = TextAlign.Center, lineHeight = 18.sp)
            Spacer(modifier = Modifier.height(20.dp))

            if (isGranted) {
                Surface(color = SuccessGreen.copy(alpha = 0.12f), shape = RoundedCornerShape(14.dp)) {
                    Row(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = SuccessGreen, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Concedida", color = SuccessGreen, fontWeight = FontWeight.Bold)
                    }
                }
            } else {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = onDeny,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, CardBorder)
                    ) {
                        Text("Negar", color = TextSecondary)
                    }
                    Button(
                        onClick = onAllow,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentCyan)
                    ) {
                        Text("Permitir", color = DarkBg, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionsSummaryCard(permissionState: PermissionState, totalSteps: Int, onFinish: () -> Unit) {
    val grantedCount = countGranted(permissionState)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        border = BorderStroke(1.dp, CardBorder)
    ) {
        Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(color = AccentCyan.copy(alpha = 0.12f), shape = RoundedCornerShape(22.dp)) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = AccentCyan,
                    modifier = Modifier.padding(18.dp).size(44.dp)
                )
            }
            Spacer(modifier = Modifier.height(18.dp))
            Text(
                text = "Configuração concluída",
                color = TextPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "$grantedCount de $totalSteps permissões estão ativas. Você pode ativar as demais depois em Configurações.",
                color = TextSecondary,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                lineHeight = 20.sp
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = onFinish,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentCyan)
            ) {
                Text("Concluir configuração", color = DarkBg, fontWeight = FontWeight.Bold)
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
                Text("Permissão restrita", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = TextPrimary)
            }
        },
        text = {
            Column {
                Text("Ative as configurações restritas do app e depois volte para habilitar a acessibilidade.", fontSize = 14.sp, color = TextSecondary)
                Spacer(Modifier.height(16.dp))
                PermissionStep(number = 1, text = "Abra as configurações do app")
                Spacer(Modifier.height(12.dp))
                PermissionStep(number = 2, text = "Toque nos três pontos do canto superior")
                Spacer(Modifier.height(12.dp))
                PermissionStep(number = 3, text = "Permita configurações restritas")
                Spacer(Modifier.height(12.dp))
                PermissionStep(number = 4, text = "Volte e ative o serviço de acessibilidade")
            }
        },
        confirmButton = {
            Button(onClick = onOpenAppSettings, colors = ButtonDefaults.buttonColors(containerColor = AccentCyan)) {
                Text("Abrir configurações", color = DarkBg)
            }
        },
        dismissButton = {
            TextButton(onClick = onOpenAccessibilitySettings) {
                Text("Abrir acessibilidade", color = TextSecondary)
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

private fun readPermissionState(context: Context, deviceOwnerManager: DeviceOwnerManager): PermissionState {
    return PermissionState(
        notifications = isNotificationPermissionGranted(context),
        batteryOptimization = PermissionUtils.isBatteryOptimizationIgnored(context),
        accessibility = PermissionUtils.isAccessibilityServiceEnabled(context),
        usageAccess = PermissionUtils.isUsageAccessEnabled(context),
        deviceAdmin = deviceOwnerManager.isDeviceAdminActive() || deviceOwnerManager.isDeviceOwnerActive()
    )
}

private fun isStepGranted(step: PermissionStepType, state: PermissionState): Boolean {
    return when (step) {
        PermissionStepType.Notifications -> state.notifications
        PermissionStepType.BatteryOptimization -> state.batteryOptimization
        PermissionStepType.Accessibility -> state.accessibility
        PermissionStepType.UsageAccess -> state.usageAccess
        PermissionStepType.DeviceAdmin -> state.deviceAdmin
    }
}

private fun countGranted(state: PermissionState): Int {
    return listOf(
        state.notifications,
        state.batteryOptimization,
        state.accessibility,
        state.usageAccess,
        state.deviceAdmin
    ).count { it }
}

private fun isNotificationPermissionGranted(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    } else {
        true
    }
}

private fun isAccessibilityServiceRestricted(context: Context): Boolean {
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val appOps = context.getSystemService(AppOpsManager::class.java)
            val mode = appOps.noteOpNoThrow(
                "android:access_restricted_settings",
                Process.myUid(),
                context.packageName
            )
            mode != AppOpsManager.MODE_ALLOWED
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