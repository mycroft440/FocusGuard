package com.focusguard.ui.compose.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import android.content.pm.PackageManager
import com.focusguard.ui.compose.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockingSessionStatusSheet(
    isBlocking: Boolean,
    hasSession: Boolean,
    details: String,
    apps: List<String>,
    sites: List<String>,
    onRenounce: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val statusText: String
    val statusColor: androidx.compose.ui.graphics.Color

    when {
        isBlocking -> {
            statusText = "Bloqueio Ativo"
            statusColor = DangerRed
        }
        hasSession -> {
            statusText = "Sessão Registrada (Aguardando janela)"
            statusColor = WarningAmber
        }
        else -> {
            statusText = "Nenhuma Sessão Ativa"
            statusColor = SuccessGreen
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp)
    ) {
        // Status indicator
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(statusColor, RoundedCornerShape(6.dp))
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = statusText,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = statusColor
            )
        }

        // Details
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = DarkCardElevated),
            border = BorderStroke(1.dp, CardBorder)
        ) {
            Text(
                text = details,
                fontSize = 13.sp,
                color = TextSecondary,
                modifier = Modifier.padding(16.dp),
                lineHeight = 20.sp
            )
        }

        if (apps.isNotEmpty() || sites.isNotEmpty()) {
            Text(
                text = "Itens Bloqueados (${apps.size + sites.size})",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // Horizontal list of icons and domains
            androidx.compose.foundation.lazy.LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Apps
                items(apps.size) { index ->
                    val pkg = apps[index]
                    var iconBmp by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
                    var appName by remember { mutableStateOf(pkg) }

                    LaunchedEffect(pkg) {
                        try {
                            val pm = context.packageManager
                            val info = pm.getApplicationInfo(pkg, 0)
                            appName = pm.getApplicationLabel(info).toString()
                            val drawable = pm.getApplicationIcon(info)
                            iconBmp = drawable.toBitmap(80, 80).asImageBitmap()
                        } catch (e: Exception) {
                            // Leave default values
                        }
                    }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.width(64.dp)
                    ) {
                        if (iconBmp != null) {
                            Image(
                                bitmap = iconBmp!!,
                                contentDescription = appName,
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(12.dp))
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(DarkCardElevated),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("📱", fontSize = 20.sp)
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = appName,
                            fontSize = 10.sp,
                            color = TextSecondary,
                            maxLines = 1,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                // Sites
                items(sites.size) { index ->
                    val site = sites[index]
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.width(64.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(AccentCyan.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("🌐", fontSize = 20.sp)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = site,
                            fontSize = 10.sp,
                            color = TextSecondary,
                            maxLines = 1,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }

        // Renounce button
        OutlinedButton(
            onClick = onRenounce,
            modifier = Modifier.fillMaxWidth(),
            enabled = !hasSession,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(
                1.5.dp,
                if (!hasSession) DangerRed else TextDisabled
            ),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = if (!hasSession) DangerRed else TextDisabled
            )
        ) {
            Text(
                text = if (isBlocking) "Não é possível revogar (Bloqueio ativo)"
                       else "Revogar Device Owner",
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Close button
        Button(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = DarkCardElevated)
        ) {
            Text("Fechar", color = TextPrimary)
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}
