package com.focusguard.ui.compose.components.limits

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
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
import com.focusguard.ui.compose.theme.*
import com.focusguard.utils.WebsiteBlocker

data class WebsiteLimitUi(
    val domain: String,
    val dailyLimitMinutes: Int,
    val isEnabled: Boolean,
    val usageMs: Long,
    val lockMode: String,
    val lockPasswordHash: String?,
    val lockUntilTimestamp: Long?
)

@Composable
fun WebsiteLimitItem(
    site: WebsiteLimitUi,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val ruleLabel = WebsiteBlocker.displayRule(site.domain)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(AccentCyan.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    ruleLabel.take(1).uppercase(),
                    color = AccentCyan,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(ruleLabel, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                val usageMin = site.usageMs / 60000L
                Text(
                    stringResource(R.string.limits_daily_time_label, usageMin),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
                val progress = if (site.dailyLimitMinutes > 0) {
                    (usageMin.toFloat() / site.dailyLimitMinutes).coerceIn(0f, 1f)
                } else {
                    0f
                }
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = progress,
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                    color = if (progress >= 0.9f) DangerRed else AccentCyan,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    stringResource(R.string.minutes_label, site.dailyLimitMinutes),
                    color = if (site.isEnabled) AccentCyan else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (site.lockMode != "NONE") {
                        Icon(
                            imageVector = if (site.lockMode == "PASSWORD") Icons.Default.Lock else Icons.Default.Security,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = if (site.isEnabled) AccentCyan else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Delete, null, tint = DangerRed.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}
