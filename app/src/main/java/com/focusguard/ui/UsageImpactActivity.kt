package com.focusguard.ui

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.database.AppDatabase
import com.focusguard.ui.compose.theme.DarkBg
import com.focusguard.ui.compose.theme.DarkSurface
import com.focusguard.ui.compose.theme.FocusGuardTheme
import com.focusguard.ui.compose.theme.SuccessGreen
import com.focusguard.ui.compose.theme.TextPrimary
import com.focusguard.ui.compose.theme.TextSecondary
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class UsageImpactActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty()
        if (packageName.isBlank()) {
            finish()
            return
        }

        setContent {
            FocusGuardTheme {
                UsageImpactScreen(packageName = packageName, onClose = ::finish)
            }
        }
    }

    companion object {
        private const val EXTRA_PACKAGE_NAME = "usage_impact_package"

        fun createIntent(context: Context, packageName: String): Intent =
            Intent(context, UsageImpactActivity::class.java).apply {
                putExtra(EXTRA_PACKAGE_NAME, packageName)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
    }
}

private data class UsageImpactSnapshot(
    val appName: String,
    val beforeMillis: Long,
    val afterMillis: Long,
    val windowMillis: Long,
    val dailyLimitMinutes: Int
)

@Composable
private fun UsageImpactScreen(packageName: String, onClose: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val snapshot by produceState<UsageImpactSnapshot?>(initialValue = null, packageName) {
        value = loadUsageImpact(context, packageName)
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Impacto do bloqueio",
            color = TextPrimary,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Veja como seu uso mudou antes e depois de ativar o bloqueio ou limitador.",
            color = TextSecondary,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(28.dp))

        val data = snapshot
        if (data == null) {
            CircularProgressIndicator()
        } else {
            Text(
                text = data.appName,
                color = TextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                UsageCard(
                    modifier = Modifier.weight(1f),
                    title = "Antes",
                    value = formatDuration(data.beforeMillis)
                )
                UsageCard(
                    modifier = Modifier.weight(1f),
                    title = "Depois",
                    value = formatDuration(data.afterMillis)
                )
            }
            Spacer(Modifier.height(18.dp))

            val reduction = if (data.beforeMillis > 0L) {
                ((1.0 - data.afterMillis.toDouble() / data.beforeMillis.toDouble()) * 100.0)
                    .roundToInt()
            } else null
            if (reduction != null) {
                Text(
                    text = if (reduction >= 0) {
                        "Uso reduzido em ${reduction}%"
                    } else {
                        "Uso aumentou em ${-reduction}%"
                    },
                    color = if (reduction >= 0) SuccessGreen else TextSecondary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Comparação com períodos equivalentes de ${formatWindow(data.windowMillis)}. " +
                    "Limite configurado: ${data.dailyLimitMinutes} min/dia.",
                color = TextSecondary,
                textAlign = TextAlign.Center,
                fontSize = 13.sp
            )
        }

        Spacer(Modifier.height(28.dp))
        Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
            Text("Voltar")
        }
    }
}

@Composable
private fun UsageCard(modifier: Modifier, title: String, value: String) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(title, color = TextSecondary, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Text(value, color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
    }
}

private suspend fun loadUsageImpact(context: Context, packageName: String): UsageImpactSnapshot =
    withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val limit = AppDatabase.getDatabase(context)
            .appUsageLimitDao()
            .getAllStatic()
            .firstOrNull { it.packageName == packageName }
        val activation = limit?.createdAt
            ?.takeIf { it > 0L && it < now }
            ?: (now - DAY_MILLIS)
        val elapsed = (now - activation).coerceAtLeast(MIN_WINDOW_MILLIS)
        val window = elapsed.coerceAtMost(MAX_WINDOW_MILLIS)
        val beforeStart = (activation - window).coerceAtLeast(0L)
        val afterEnd = (activation + window).coerceAtMost(now)
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        val before = manager?.queryAndAggregateUsageStats(beforeStart, activation)
            ?.get(packageName)?.totalTimeInForeground ?: 0L
        val after = manager?.queryAndAggregateUsageStats(activation, afterEnd)
            ?.get(packageName)?.totalTimeInForeground ?: 0L
        val label = runCatching {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(packageName)

        UsageImpactSnapshot(
            appName = label,
            beforeMillis = before,
            afterMillis = after,
            windowMillis = window,
            dailyLimitMinutes = limit?.dailyLimitMinutes ?: 0
        )
    }

private fun formatDuration(millis: Long): String {
    val totalMinutes = (millis / 60_000L).coerceAtLeast(0L)
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return if (hours > 0L) "${hours}h ${minutes}min" else "${minutes} min"
}

private fun formatWindow(millis: Long): String {
    val hours = millis.toDouble() / 3_600_000.0
    return when {
        hours >= 48.0 -> String.format(Locale.getDefault(), "%.1f dias", hours / 24.0)
        hours >= 1.0 -> String.format(Locale.getDefault(), "%.1f horas", hours)
        else -> "${(millis / 60_000L).coerceAtLeast(1L)} min"
    }
}

private const val MIN_WINDOW_MILLIS = 60_000L
private const val DAY_MILLIS = 24L * 60L * 60L * 1000L
private const val MAX_WINDOW_MILLIS = 7L * DAY_MILLIS
