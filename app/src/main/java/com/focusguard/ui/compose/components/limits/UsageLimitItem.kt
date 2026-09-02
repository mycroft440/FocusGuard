package com.focusguard.ui.compose.components.limits

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.R
import com.focusguard.ui.compose.components.FocusGuardAppIcon
import com.focusguard.ui.compose.theme.*
import com.focusguard.utils.UsageLimitBehaviorPolicy
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

/**
 * Lightweight row for the usage-limit catalogue.
 *
 * This list can contain a large number of targets. Per-row progress/color
 * animations and remote favicon requests caused dozens of concurrent jobs while
 * scrolling, which made the screen feel slow or stuck on lower-end devices.
 * Values are intentionally rendered statically here; the data still updates when
 * the screen state changes, without continuously animating every visible item.
 */
@Composable
fun UsageLimitItem(
    app: UsageLimitAppUi,
    isActive: Boolean,
    onClick: () -> Unit
) {
    val now = System.currentTimeMillis()
    val dailyBehavior = UsageLimitBehaviorPolicy.isDailyBehaviorMode(app.lockMode)
    val ruleExpired = dailyBehavior &&
        app.lockUntilTimestamp?.let { it <= now } == true
    val effectiveActive = isActive && !ruleExpired
    val behaviorLabel = when {
        UsageLimitBehaviorPolicy.isPauseMode(app.lockMode) ->
            stringResource(R.string.limits_pause_30_option)
        UsageLimitBehaviorPolicy.isBlockUntilTomorrowMode(app.lockMode) ->
            stringResource(R.string.limits_block_tomorrow_option)
        else -> null
    }
    val behaviorStatus = when {
        ruleExpired -> stringResource(R.string.limits_rule_expired)
        behaviorLabel != null && app.lockUntilTimestamp != null -> {
            val date = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
                .format(Date(app.lockUntilTimestamp))
            stringResource(R.string.limits_rule_status_until, behaviorLabel, date)
        }
        else -> behaviorLabel
    }

    val usageMin = app.usageMs / 60000L
    val progress = if ((app.currentLimitMinutes ?: 0) > 0) {
        (usageMin.toFloat() / app.currentLimitMinutes!!).coerceIn(0f, 1f)
    } else {
        0f
    }
    val progressColor = if (progress >= 0.9f) DangerRed else AccentCyan

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (effectiveActive) {
                AccentCyan.copy(alpha = 0.05f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        ),
        border = BorderStroke(
            1.dp,
            if (effectiveActive) {
                AccentCyan.copy(alpha = 0.2f)
            } else {
                MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
            }
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FocusGuardAppIcon(
                packageName = app.packageName,
                appName = app.appName,
                modifier = Modifier.size(40.dp),
                cornerRadius = 10.dp,
                allowRemoteFallback = false
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    app.appName,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    maxLines = 1
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(
                            R.string.minutes_ratio,
                            usageMin,
                            app.currentLimitMinutes ?: 0
                        ),
                        color = if (effectiveActive) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.weight(1f))
                    if (progress >= 0.9f && effectiveActive) {
                        Text(
                            stringResource(R.string.limits_usage_alert),
                            color = DangerRed,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    } else if (effectiveActive) {
                        Text(
                            stringResource(R.string.limits_status_monitoring),
                            color = AccentCyan,
                            fontSize = 10.sp
                        )
                    }
                }

                behaviorStatus?.let { status ->
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = status,
                        color = if (ruleExpired) DangerRed else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp,
                        maxLines = 1
                    )
                }

                if (app.currentLimitMinutes != null) {
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                        color = progressColor,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                }
            }

            if (app.currentLimitMinutes != null && app.lockMode != "NONE") {
                Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector = if (app.lockMode == "PASSWORD") {
                        Icons.Default.Lock
                    } else {
                        Icons.Default.Security
                    },
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = if (effectiveActive) {
                        AccentCyan
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}