package com.focusguard.ui

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.database.AppDatabase
import com.focusguard.utils.UsageLimitPauseStateStore
import com.focusguard.ui.compose.components.FocusGuardBannerAd
import com.focusguard.ui.compose.theme.DarkSurface
import com.focusguard.ui.compose.theme.FocusGuardTheme
import com.focusguard.ui.compose.theme.SuccessGreen
import com.focusguard.ui.compose.theme.TextPrimary
import com.focusguard.ui.compose.theme.TextSecondary
import com.focusguard.usage.BlockReleaseTimes
import com.focusguard.usage.UsageImpactRouter
import com.focusguard.usage.UsageInterventionStore
import com.focusguard.usage.UsageInterventionType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Dedicated metrics surface shown after a non-password timed intervention blocks
 * an app. Two anchored banners remain around the metrics while a third banner is
 * part of the scrollable content at the real end of the page.
 */
class UsageImpactActivity : AppCompatActivity() {
    private var targetPackage by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        targetPackage = intent.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty()
        if (targetPackage.isBlank()) {
            finish()
            return
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goHome()
        })

        setContent {
            FocusGuardTheme {
                UsageImpactScreen(
                    packageName = targetPackage,
                    onClose = ::goHome
                )
            }
        }
    }

    /**
     * Sair desta tela nunca pode devolver o usuário ao app bloqueado, que fica
     * logo abaixo na pilha: isso reabriria o bloqueio e esta mesma tela. Voltar
     * sempre leva à tela inicial do aparelho.
     */
    private fun goHome() {
        runCatching {
            startActivity(
                Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
            )
        }
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val nextPackage = intent.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty()
        if (nextPackage.isBlank()) {
            finish()
            return
        }
        targetPackage = nextPackage
    }

    companion object {
        private const val EXTRA_PACKAGE_NAME = "usage_impact_package"

        fun createIntent(context: Context, packageName: String): Intent =
            Intent(context, UsageImpactActivity::class.java).apply {
                putExtra(EXTRA_PACKAGE_NAME, packageName)
                addFlags(
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
            }
    }
}

private data class UsageImpactSnapshot(
    val appName: String,
    val beforeMillis: Long,
    val afterMillis: Long,
    val windowMillis: Long,
    val interventionType: UsageInterventionType,
    val dailyLimitMinutes: Int?,
    val endsAt: Long?,
    /** Quando o app volta a abrir; null num bloqueio sem data final. */
    val blockedUntil: Long?,
    /** Bloqueio por períodos do dia: o trecho do dia em que o app fica liberado. */
    val allowedWindow: BlockReleaseTimes.AllowedWindow?,
    val scheduledPeriod: Boolean
)

@Composable
private fun UsageImpactScreen(
    packageName: String,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val compact = configuration.screenHeightDp <= 760
    val snapshot by produceState<UsageImpactSnapshot?>(initialValue = null, packageName) {
        value = loadUsageImpact(context, packageName)
    }

    val horizontalPadding = if (compact) 16.dp else 20.dp

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = { FocusGuardBannerAd() },
        bottomBar = { FocusGuardBannerAd() }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(
                    horizontal = horizontalPadding,
                    vertical = if (compact) 10.dp else 14.dp
                ),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Impacto do bloqueio",
                color = TextPrimary,
                fontSize = if (compact) 21.sp else 23.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
            Spacer(Modifier.height(if (compact) 2.dp else 4.dp))
            Text(
                text = "Compare o uso antes e depois do bloqueio.",
                color = TextSecondary,
                textAlign = TextAlign.Center,
                fontSize = if (compact) 11.sp else 12.sp,
                maxLines = 1
            )
            Spacer(Modifier.height(if (compact) 10.dp else 14.dp))

            val data = snapshot
            if (data == null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (compact) 220.dp else 280.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(34.dp))
                }
            } else {
                Text(
                    text = data.appName,
                    color = TextPrimary,
                    fontSize = if (compact) 16.sp else 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
                Spacer(Modifier.height(if (compact) 8.dp else 10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    UsageCard(
                        modifier = Modifier.weight(1f),
                        title = "Antes",
                        value = formatDuration(data.beforeMillis),
                        compact = compact
                    )
                    UsageCard(
                        modifier = Modifier.weight(1f),
                        title = "Depois",
                        value = formatDuration(data.afterMillis),
                        compact = compact
                    )
                }
                Spacer(Modifier.height(if (compact) 10.dp else 14.dp))

                val reduction = if (data.beforeMillis > 0L) {
                    ((1.0 - data.afterMillis.toDouble() / data.beforeMillis.toDouble()) * 100.0)
                        .roundToInt()
                } else null
                Text(
                    text = when {
                        reduction == null -> "Bloqueio ativo"
                        reduction >= 0 -> "Uso reduzido em ${reduction}%"
                        else -> "Uso aumentou em ${-reduction}%"
                    },
                    color = if (reduction == null || reduction >= 0) {
                        SuccessGreen
                    } else {
                        TextSecondary
                    },
                    fontWeight = FontWeight.Bold,
                    fontSize = if (compact) 15.sp else 17.sp,
                    maxLines = 1
                )
                Spacer(Modifier.height(if (compact) 10.dp else 14.dp))

                ReleaseCard(data = data, compact = compact)
                Spacer(Modifier.height(if (compact) 10.dp else 14.dp))

                Text(
                    text = impactDescription(data),
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                    fontSize = if (compact) 11.sp else 12.sp,
                    lineHeight = if (compact) 15.sp else 17.sp,
                    maxLines = 3
                )

                Spacer(Modifier.height(if (compact) 14.dp else 20.dp))
                Button(
                    onClick = onClose,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (compact) 42.dp else 46.dp)
                ) {
                    Text("Voltar", fontSize = if (compact) 14.sp else 15.sp)
                }
                Spacer(Modifier.height(if (compact) 14.dp else 18.dp))
                FocusGuardBannerAd(modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

/** Quanto tempo o app ainda fica bloqueado e, nos períodos do dia, quando fica liberado. */
@Composable
private fun ReleaseCard(data: UsageImpactSnapshot, compact: Boolean) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(data.blockedUntil) {
        while (true) {
            delay(15_000L)
            now = System.currentTimeMillis()
        }
    }
    val until = data.blockedUntil
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = if (compact) 10.dp else 13.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "Fica bloqueado por mais",
                color = TextSecondary,
                fontSize = if (compact) 11.sp else 12.sp
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = if (until == null) "Sem data final" else formatRemaining(until - now),
                color = TextPrimary,
                fontSize = if (compact) 20.sp else 22.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
            if (until != null) {
                Text(
                    text = "Liberado ${formatReleaseMoment(until, now)}",
                    color = TextSecondary,
                    fontSize = if (compact) 11.sp else 12.sp,
                    maxLines = 1
                )
            }
            data.allowedWindow?.let { window ->
                Spacer(Modifier.height(if (compact) 8.dp else 10.dp))
                Text(
                    "Período liberado",
                    color = TextSecondary,
                    fontSize = if (compact) 11.sp else 12.sp
                )
                Text(
                    text = "Das ${hhmm(window.startHour, window.startMinute)} " +
                        "às ${hhmm(window.endHour, window.endMinute)}",
                    color = SuccessGreen,
                    fontSize = if (compact) 16.sp else 18.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                if (window.restrictedToDays) {
                    Text(
                        "Nos dias sem bloqueio, o app fica liberado o dia todo.",
                        color = TextSecondary,
                        textAlign = TextAlign.Center,
                        fontSize = if (compact) 10.sp else 11.sp
                    )
                }
            }
        }
    }
}

private fun impactDescription(data: UsageImpactSnapshot): String {
    val period = "Períodos equivalentes de ${formatWindow(data.windowMillis)}."
    return when (data.interventionType) {
        UsageInterventionType.TIME_BLOCK -> {
            if (data.scheduledPeriod) {
                val ruleEnd = data.endsAt?.takeIf { it > System.currentTimeMillis() }
                return if (ruleEnd != null) {
                    "$period Bloqueio por períodos ativo até ${formatTimestamp(ruleEnd)}."
                } else {
                    "$period Bloqueio por períodos do dia ativo."
                }
            }
            val end = data.endsAt?.takeIf { it > System.currentTimeMillis() }
            if (end != null) {
                "$period Bloqueio ativo até ${formatTimestamp(end)}."
            } else {
                "$period Bloqueio por tempo ativo."
            }
        }
        UsageInterventionType.USAGE_LIMIT -> {
            val limit = data.dailyLimitMinutes
            if (limit != null) {
                "$period Limite: $limit min/dia."
            } else {
                "$period Limitador diário ativo."
            }
        }
    }
}

@Composable
private fun UsageCard(
    modifier: Modifier,
    title: String,
    value: String,
    compact: Boolean
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = if (compact) 8.dp else 11.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                title,
                color = TextSecondary,
                fontSize = if (compact) 11.sp else 12.sp
            )
            Spacer(Modifier.height(2.dp))
            Text(
                value,
                color = TextPrimary,
                fontSize = if (compact) 18.sp else 20.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }
}

private suspend fun loadUsageImpact(
    context: Context,
    packageName: String
): UsageImpactSnapshot = withContext(Dispatchers.IO) {
    val now = System.currentTimeMillis()
    val limit = AppDatabase.getDatabase(context)
        .appUsageLimitDao()
        .getAllStatic()
        .firstOrNull { it.packageName == packageName }
    val intervention = UsageInterventionStore.readApp(context, packageName)
        ?: limit?.let { UsageInterventionStore.syncFromLimit(context, it) }
    val activation = intervention?.startedAt
        ?.takeIf { it in 1 until now }
        ?: limit?.createdAt?.takeIf { it in 1 until now }
        ?: now
    val elapsed = (now - activation).coerceAtLeast(1L)
    val window = minOf(elapsed, activation)
        .coerceAtMost(MAX_WINDOW_MILLIS)
        .coerceAtLeast(1L)
    val beforeStart = activation - window
    val afterEnd = activation + window
    val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
    val before = manager?.queryAndAggregateUsageStats(beforeStart, activation)
        ?.get(packageName)?.totalTimeInForeground ?: 0L
    val after = manager?.queryAndAggregateUsageStats(activation, afterEnd)
        ?.get(packageName)?.totalTimeInForeground ?: 0L
    val label = runCatching {
        val info = context.packageManager.getApplicationInfo(packageName, 0)
        context.packageManager.getApplicationLabel(info).toString()
    }.getOrDefault(packageName)
    val type = intervention?.type ?: if (limit?.lockMode.equals("TIME", true)) {
        UsageInterventionType.TIME_BLOCK
    } else {
        UsageInterventionType.USAGE_LIMIT
    }

    val timedSession = UsageImpactRouter.findActiveTimedSessionForApp(
        context = context,
        database = AppDatabase.getDatabase(context),
        packageName = packageName,
        nowMillis = now
    )
    val blockedUntil = when {
        timedSession != null -> BlockReleaseTimes.scheduledWindowEnd(timedSession, now)
        limit != null -> BlockReleaseTimes.usageLimitBlockedUntil(
            limit = limit,
            nowMillis = now,
            pauseBlockedUntil = UsageLimitPauseStateStore.pauseBlockedUntil(
                lockMode = limit.lockMode,
                ruleEndMillis = limit.lockUntilTimestamp,
                nowMillis = now
            )
        )
        else -> intervention?.endsAt
    }

    UsageImpactSnapshot(
        appName = label,
        beforeMillis = before,
        afterMillis = after,
        windowMillis = window,
        interventionType = type,
        dailyLimitMinutes = intervention?.dailyLimitMinutes
            ?: limit?.dailyLimitMinutes?.takeIf { it > 0 },
        endsAt = intervention?.endsAt ?: limit?.lockUntilTimestamp,
        blockedUntil = blockedUntil,
        allowedWindow = timedSession?.let(BlockReleaseTimes::allowedWindow),
        scheduledPeriod = timedSession?.isFixed24h == false
    )
}

private fun formatDuration(millis: Long): String {
    val totalMinutes = (millis / 60_000L).coerceAtLeast(0L)
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return if (hours > 0L) "${hours}h ${minutes}min" else "${minutes} min"
}

private fun formatWindow(millis: Long): String {
    val seconds = (millis / 1_000L).coerceAtLeast(1L)
    val minutes = seconds / 60L
    val hours = millis.toDouble() / 3_600_000.0
    return when {
        hours >= 48.0 -> String.format(Locale.getDefault(), "%.1f dias", hours / 24.0)
        hours >= 1.0 -> String.format(Locale.getDefault(), "%.1f horas", hours)
        minutes >= 1L -> "$minutes min"
        else -> "$seconds s"
    }
}

private fun formatRemaining(millis: Long): String {
    // Arredonda para cima: com 30 s restantes, "0 min" pareceria já liberado.
    val totalMinutes = ((millis.coerceAtLeast(0L) + 59_999L) / 60_000L)
    val days = totalMinutes / (24L * 60L)
    val hours = (totalMinutes / 60L) % 24L
    val minutes = totalMinutes % 60L
    return when {
        days > 0L -> "${days}d ${hours}h"
        hours > 0L -> "${hours}h ${minutes}min"
        else -> "$minutes min"
    }
}

private fun formatReleaseMoment(at: Long, now: Long): String {
    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(at))
    val today = SimpleDateFormat("yyyyMMdd", Locale.US)
    val tomorrow = Date(now + DAY_MILLIS)
    return when (today.format(Date(at))) {
        today.format(Date(now)) -> "hoje às $time"
        today.format(tomorrow) -> "amanhã às $time"
        else -> "em ${SimpleDateFormat("dd/MM", Locale.getDefault()).format(Date(at))} às $time"
    }
}

private fun hhmm(hour: Int, minute: Int): String =
    String.format(Locale.getDefault(), "%02d:%02d", hour, minute)

private fun formatTimestamp(timestamp: Long): String =
    SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(timestamp))

private const val DAY_MILLIS = 24L * 60L * 60L * 1000L
private const val MAX_WINDOW_MILLIS = 7L * DAY_MILLIS
