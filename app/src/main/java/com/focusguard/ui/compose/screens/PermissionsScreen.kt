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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
                onClick = { handleAccessibilityPermission(context) }
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

        TextButton(
            onClick = onFinish,
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

private fun handleAccessibilityPermission(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && isAccessibilityServiceRestricted(context)) {
        AlertDialog.Builder(context)
            .setTitle("Ativar Acessibilidade")
            .setMessage(
                "O FocusGuard precisa da permissão de Acessibilidade, mas o Android detectou uma restrição.\n\n" +
                "Siga estes passos:\n\n" +
                "1. Toque em \"Liberar Restrição\" abaixo\n" +
                "2. Procure a opção \"Permitir configurações restritas\"\n" +
                "3. Volte e toque em \"Ativar Acessibilidade\""
            )
            .setPositiveButton("Ativar Acessibilidade") { _, _ ->
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNeutralButton("Liberar Restrição") { _, _ ->
                try {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                    context.startActivity(intent)
                } catch (_: Exception) {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    } else {
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
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
