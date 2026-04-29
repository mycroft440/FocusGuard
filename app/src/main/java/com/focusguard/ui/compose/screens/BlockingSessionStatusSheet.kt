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
import com.focusguard.ui.compose.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockingSessionStatusSheet(
    isBlocking: Boolean,
    hasSession: Boolean,
    details: String,
    onRenounce: () -> Unit,
    onEndSessions: () -> Unit,
    onDismiss: () -> Unit,
    authManager: com.focusguard.security.AuthManager
) {
    val statusText: String
    val statusColor: androidx.compose.ui.graphics.Color
    
    val isSafetyMode = authManager.isSafetyModeEnabled()

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
                .padding(bottom = 24.dp),
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

        if (hasSession) {
            if (isSafetyMode) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = DangerRed.copy(alpha = 0.1f)),
                    border = BorderStroke(1.dp, DangerRed.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                ) {
                    Text(
                        "O modo segurança está ativo, não é possível burlar ou alterar configurações de limite.",
                        color = DangerRed, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            } else {
                Button(
                    onClick = onEndSessions,
                    modifier = Modifier.fillMaxWidth().height(50.dp).padding(bottom = 16.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentCyan)
                ) {
                    Text("Encerrar Bloqueio por Senha", color = DarkBg, fontWeight = FontWeight.Bold)
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
