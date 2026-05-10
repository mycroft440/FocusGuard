package com.focusguard.ui.compose.screens

import kotlin.OptIn
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.res.stringResource
import com.focusguard.R
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
import androidx.compose.animation.core.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
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
            statusText = stringResource(R.string.status_blocking_active)
            statusColor = DangerRed
        }
        hasSession -> {
            statusText = stringResource(R.string.status_session_registered)
            statusColor = WarningAmber
        }
        else -> {
            statusText = stringResource(R.string.status_no_session)
            statusColor = SuccessGreen
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp)
    ) {
        // [L3] Status indicator with pulse animation
        val infiniteTransition = rememberInfiniteTransition()
        val pulseAlpha by infiniteTransition.animateFloat(
            initialValue = 0.4f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1000, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            )
        )

        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = statusColor.copy(alpha = 0.05f)),
            border = BorderStroke(1.dp, statusColor.copy(alpha = 0.2f))
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .graphicsLayer { alpha = pulseAlpha }
                        .background(statusColor, RoundedCornerShape(6.dp))
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = statusText,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = statusColor
                )
                if (isBlocking) {
                    Spacer(modifier = Modifier.weight(1f))
                    Icon(Icons.Default.Shield, null, tint = statusColor, modifier = Modifier.size(20.dp))
                }
            }
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
                        stringResource(R.string.status_safety_mode_warning),
                        color = DangerRed, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            } else {
                Button(
                    onClick = onEndSessions,
                    modifier = Modifier.fillMaxWidth().height(54.dp).padding(bottom = 16.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                    contentPadding = PaddingValues()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Brush.linearGradient(listOf(AccentCyan, AccentCyanDark))),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(stringResource(R.string.status_end_password_block), color = DarkBg, fontWeight = FontWeight.ExtraBold)
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
                text = if (isBlocking) stringResource(R.string.status_revoke_do_blocked)
                       else stringResource(R.string.status_revoke_do),
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
            Text(stringResource(R.string.status_close), color = TextPrimary)
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}