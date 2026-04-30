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
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
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
                    Text("Desbloquear Acessibilidade", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
            },
            text = {
                Column {
                    Text(
                        "O Android ocultou essa permissão por segurança. Para ativá-la, siga os passos exatos:",
                        fontSize = 14.sp,
                        color = TextSecondary
                    )
                    Spacer(Modifier.height(16.dp))
                    
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(24.dp).background(DarkCardElevated, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { Text("1", color = AccentCyan, fontWeight = FontWeight.Bold) }
                        Spacer(Modifier.width(8.dp))
                        Text("Toque em 'Abrir Configurações' abaixo.", fontSize = 14.sp)
                    }
                    Spacer(Modifier.height(12.dp))
                    
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(24.dp).background(DarkCardElevated, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { Text("2", color = AccentCyan, fontWeight = FontWeight.Bold) }
                        Spacer(Modifier.width(8.dp))
                        Text("No canto superior direito, toque nos três pontinhos (⋮).", fontSize = 14.sp)
                    }
                    Spacer(Modifier.height(12.dp))
                    
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(24.dp).background(DarkCardElevated, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { Text("3", color = AccentCyan, fontWeight = FontWeight.Bold) }
                        Spacer(Modifier.width(8.dp))
                        Text("Toque em 'Permitir configurações restritas'.", fontSize = 14.sp)
                    }
                    Spacer(Modifier.height(12.dp))
                    
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(24.dp).background(DarkCardElevated, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { Text("4", color = AccentCyan, fontWeight = FontWeight.Bold) }
                        Spacer(Modifier.width(8.dp))
                        Text("Volte para cá e tente ativar novamente.", fontSize = 14.sp)
                    }
                    
                    Spacer(Modifier.height(16.dp))
                    Surface(
                        color = DangerRed.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            "Dica: Se a opção não aparecer nos 3 pontinhos, tente ativar a Acessibilidade primeiro para que o Android exiba o alerta, depois tente novamente.",
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
                    Text("Abrir Configurações", color = DarkBg)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showRestrictedDialog = false
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                ) {
                    Text("Já liberei, ativar agora", color = TextSecondary)
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
                contentDescription = "FocusGuard",
                modifier = Modifier.size(72.dp),
                tint = AccentCyan
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Bem-vindo ao FocusGuard",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Para que possamos bloquear aplicativos e sites que distraem você, precisamos de algumas permissões do sistema.",
                fontSize = 14.sp,
                color = TextSecondary,
                textAlign = TextAlign.Center,
                lineHeight = 20.sp,
                modifier = Modifier.padding(bottom = 32.dp)
            )

            PermissionCard(
                number = 1,
                title = "Acessibilidade",
                description = "Permite ler a tela e bloquear apps/sites",
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
                title = "Acesso a Uso de Dados",
                description = "Permite rastrear o tempo gasto",
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
                title = "Admin do Dispositivo",
                description = "Proteção contra desinstalação",
                isGranted = permState.deviceAdmin,
                onClick = {
                    if (!deviceOwnerManager.isDeviceAdminActive()) {
                        deviceOwnerManager.requestDeviceAdmin()
                    }
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            PermissionCard(
                number = 4,
                title = "Bateria Irrestrita",
                description = "Impede o sistema de encerrar o bloqueio",
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
        }

        var showSkipOverlay by rememberSaveable { mutableStateOf(false) }
        var showBatteryOverlay by rememberSaveable { mutableStateOf(false) }
        var skipInteracted by rememberSaveable { mutableStateOf(false) }
        val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            if (isGranted) {
                onFinish()
            } else {
                onFinish()
            }
        }

        if (showBatteryOverlay) {
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
                        Icon(androidx.compose.material.icons.Icons.Default.BatteryChargingFull, null, tint = AccentCyan, modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Bateria Irrestrita",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Para garantir que o bloqueio nunca falhe, o Android precisa ignorar as restrições de bateria para o FocusGuard.",
                            fontSize = 14.sp,
                            color = TextSecondary,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(24.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(
                                onClick = { 
                                    showBatteryOverlay = false
                                    showSkipOverlay = true
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, CardBorder)
                            ) {
                                Text("Negar", color = TextSecondary)
                            }
                            Button(
                                onClick = {
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
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = AccentCyan)
                            ) {
                                Text("Permitir", color = DarkBg)
                            }
                        }
                    }
                }
            }
        }

        if (showSkipOverlay) {
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
                        Icon(Icons.Default.NotificationsActive, null, tint = AccentCyan, modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Notificações de Foco",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Deseja permitir que o FocusGuard envie avisos sobre seus bloqueios ativos?",
                            fontSize = 14.sp,
                            color = TextSecondary,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(24.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(
                                onClick = { onFinish() },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, CardBorder)
                            ) {
                                Text("Negar", color = TextSecondary)
                            }
                            Button(
                                onClick = {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                                    } else {
                                        onFinish()
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = AccentCyan)
                            ) {
                                Text("Permitir", color = DarkBg)
                            }
                        }
                    }
                }
            }
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
                text = if (permState.accessibility && permState.usageAccess) "Concluir Configuração" else "Pular por enquanto",
                fontSize = 14.sp,
                color = AccentCyan,
                fontWeight = FontWeight.Bold
            )
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
                Text(
                    text = title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = description,
                    fontSize = 12.sp,
                    color = TextSecondary
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
                    text = if (isGranted) "Concedido" else "Conceder",
                    fontSize = 12.sp
                )
            }
        }
    }
}
