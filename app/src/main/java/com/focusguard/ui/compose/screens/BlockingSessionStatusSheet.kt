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
    onDismiss: () -> Unit
) {
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
