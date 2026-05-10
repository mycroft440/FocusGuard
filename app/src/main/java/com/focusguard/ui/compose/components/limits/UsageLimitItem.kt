package com.focusguard.ui.compose.components.limits

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.graphics.drawable.Drawable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.focusguard.R
import com.focusguard.ui.compose.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class UsageLimitAppUi(
    val packageName: String,
    val appName: String,
    val currentLimitMinutes: Int?,
    val isEnabled: Boolean,
    val usageMs: Long,
    val lockMode: String,
    val lockPasswordHash: String?,
    val lockUntilTimestamp: Long?
)

@Composable
fun UsageLimitItem(
    app: UsageLimitAppUi,
    isActive: Boolean,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    var iconDrawable by remember(app.packageName) { mutableStateOf<Drawable?>(null) }
    
    LaunchedEffect(app.packageName) {
        withContext(Dispatchers.IO) {
            try {
                iconDrawable = context.packageManager.getApplicationIcon(app.packageName)
            } catch (_: Exception) {}
        }
    }

    val usageMin = app.usageMs / 60000L
    val targetProgress = if ((app.currentLimitMinutes ?: 0) > 0) (usageMin.toFloat() / app.currentLimitMinutes!!).coerceIn(0f, 1f) else 0f
    
    // Animação fluida da barra de progresso
    val progress by animateFloatAsState(
        targetValue = targetProgress,
        animationSpec = tween(durationMillis = 800),
        label = "progress_anim"
    )

    // Cores dinâmicas para estado de alerta
    val progressColor by animateColorAsState(
        targetValue = if (progress >= 0.9f) DangerRed else AccentCyan,
        animationSpec = tween(durationMillis = 500),
        label = "color_anim"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) AccentCyan.copy(alpha = 0.05f) else MaterialTheme.colorScheme.surfaceVariant
        ),
        border = BorderStroke(1.dp, if (isActive) AccentCyan.copy(alpha = 0.2f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (iconDrawable != null) {
                val bitmap = remember(iconDrawable) {
                    iconDrawable!!.toBitmap(80, 80).asImageBitmap()
                }
                Image(
                    bitmap = bitmap,
                    contentDescription = app.appName,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(AccentCyan.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(app.appName.take(1).uppercase(), color = AccentCyan, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(app.appName, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.minutes_ratio, usageMin, app.currentLimitMinutes ?: 0),
                        color = if (isActive) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.weight(1f))
                    if (progress >= 0.9f) {
                        Text(stringResource(R.string.limits_usage_alert), color = DangerRed, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    } else if (isActive) {
                        Text(stringResource(R.string.limits_status_monitoring), color = AccentCyan, fontSize = 10.sp)
                    }
                }
                
                if (app.currentLimitMinutes != null) {
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = progress,
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                        color = progressColor,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                }
            }

            if (app.currentLimitMinutes != null && app.lockMode != "NONE") {
                Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector = if (app.lockMode == "PASSWORD") Icons.Default.Lock else Icons.Default.Security,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = if (isActive) AccentCyan else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}