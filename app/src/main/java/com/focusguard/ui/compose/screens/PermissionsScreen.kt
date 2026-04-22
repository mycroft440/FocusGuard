package com.focusguard.ui.compose.screens

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.R
import com.focusguard.ui.compose.theme.*

data class PermissionState(
    val accessibility: Boolean = false,
    val usageAccess: Boolean = false,
    val deviceAdmin: Boolean = false,
    val batteryOptimization: Boolean = false
)

@Composable
fun PermissionsScreen(
    permissionState: PermissionState,
    onAccessibilityClick: () -> Unit,
    onUsageAccessClick: () -> Unit,
    onDeviceAdminClick: () -> Unit,
    onBatteryClick: () -> Unit,
    onSkipClick: () -> Unit
) {
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

            // App icon
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

            // Permission 1: Accessibility
            PermissionCard(
                number = 1,
                title = "Acessibilidade",
                description = "Permite ler a tela e bloquear apps/sites",
                isGranted = permissionState.accessibility,
                onClick = onAccessibilityClick
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Permission 2: Usage Access
            PermissionCard(
                number = 2,
                title = "Acesso a Uso de Dados",
                description = "Permite rastrear o tempo gasto",
                isGranted = permissionState.usageAccess,
                onClick = onUsageAccessClick
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Permission 3: Device Admin
            PermissionCard(
                number = 3,
                title = "Admin do Dispositivo",
                description = "Proteção contra desinstalação",
                isGranted = permissionState.deviceAdmin,
                onClick = onDeviceAdminClick
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Permission 4: Battery
            PermissionCard(
                number = 4,
                title = "Bateria Irrestrita",
                description = "Impede o sistema de encerrar o bloqueio",
                isGranted = permissionState.batteryOptimization,
                onClick = onBatteryClick
            )
        }

        // Skip button
        TextButton(
            onClick = onSkipClick,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(bottom = 20.dp)
        ) {
            Text(
                text = "Pular configurações por enquanto",
                fontSize = 13.sp,
                color = TextHint
            )
        }
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
            // Number badge
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
