package com.focusguard.ui.compose.screens

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.drawable.toBitmap
import com.focusguard.ui.compose.theme.*
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.concurrent.TimeUnit

data class AppUsageInfo(
    val packageName: String,
    val appName: String,
    val icon: Drawable?,
    val usageTimeMs: Long
)

data class MonthlyUsage(
    val label: String,
    val avgHoursPerDay: Float
)

@Composable
fun UsageStatsScreen() {
    val context = LocalContext.current

    var topApps by remember { mutableStateOf<List<AppUsageInfo>>(emptyList()) }
    var monthlyData by remember { mutableStateOf<List<MonthlyUsage>>(emptyList()) }
    var trendText by remember { mutableStateOf("Média diária de uso do telefone") }
    var noData by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
                val pm = context.packageManager

                // Load top 5 apps
                val calendar = Calendar.getInstance()
                val endTime = calendar.timeInMillis
                calendar.add(Calendar.DAY_OF_YEAR, -7)
                val startTime = calendar.timeInMillis

                val usageStatsList = usageStatsManager.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY, startTime, endTime
                )

                if (usageStatsList.isNullOrEmpty()) {
                    noData = true
                    return@withContext
                }

                val usageMap = mutableMapOf<String, Long>()
                for (stats in usageStatsList) {
                    if (stats.totalTimeInForeground > 0) {
                        usageMap[stats.packageName] = (usageMap[stats.packageName] ?: 0L) + stats.totalTimeInForeground
                    }
                }

                val filteredMap = usageMap.filter { entry ->
                    !entry.key.contains("launcher") &&
                    entry.key != context.packageName &&
                    entry.key != "com.android.settings" &&
                    entry.value > TimeUnit.MINUTES.toMillis(1)
                }

                val top5 = filteredMap.entries.sortedByDescending { it.value }.take(5)

                if (top5.isEmpty()) {
                    noData = true
                    return@withContext
                }

                topApps = top5.map { entry ->
                    val appName = try {
                        pm.getApplicationLabel(pm.getApplicationInfo(entry.key, 0)).toString()
                    } catch (_: PackageManager.NameNotFoundException) {
                        entry.key.substringAfterLast(".")
                    }
                    val icon = try { pm.getApplicationIcon(entry.key) } catch (_: Exception) { null }
                    AppUsageInfo(entry.key, appName, icon, entry.value)
                }

                // Load monthly chart
                val entries = mutableListOf<MonthlyUsage>()
                val monthNames = arrayOf("Jan", "Fev", "Mar", "Abr", "Mai", "Jun", "Jul", "Ago", "Set", "Out", "Nov", "Dez")

                for (i in 5 downTo 0) {
                    val cal = Calendar.getInstance()
                    cal.add(Calendar.MONTH, -i)
                    cal.set(Calendar.DAY_OF_MONTH, 1)
                    cal.set(Calendar.HOUR_OF_DAY, 0)
                    cal.set(Calendar.MINUTE, 0)
                    cal.set(Calendar.SECOND, 0)
                    val monthStart = cal.timeInMillis

                    val calEnd = Calendar.getInstance()
                    calEnd.add(Calendar.MONTH, -i)
                    calEnd.set(Calendar.DAY_OF_MONTH, calEnd.getActualMaximum(Calendar.DAY_OF_MONTH))
                    calEnd.set(Calendar.HOUR_OF_DAY, 23)
                    calEnd.set(Calendar.MINUTE, 59)
                    calEnd.set(Calendar.SECOND, 59)
                    val monthEnd = calEnd.timeInMillis

                    val monthUsage = usageStatsManager.queryUsageStats(
                        UsageStatsManager.INTERVAL_DAILY, monthStart, monthEnd
                    )

                    var totalUsageMs = 0L
                    var daysWithData = 0

                    if (!monthUsage.isNullOrEmpty()) {
                        val dailyMap = mutableMapOf<Int, Long>()
                        for (stats in monthUsage) {
                            if (stats.totalTimeInForeground > 0) {
                                val dayCal = Calendar.getInstance()
                                dayCal.timeInMillis = stats.lastTimeUsed
                                val dayOfMonth = dayCal.get(Calendar.DAY_OF_MONTH)
                                dailyMap[dayOfMonth] = (dailyMap[dayOfMonth] ?: 0L) + stats.totalTimeInForeground
                            }
                        }
                        totalUsageMs = dailyMap.values.sum()
                        daysWithData = dailyMap.size.coerceAtLeast(1)
                    }

                    val avgHoursPerDay = if (daysWithData > 0) {
                        totalUsageMs.toFloat() / daysWithData / 1000f / 3600f
                    } else 0f

                    entries.add(MonthlyUsage(monthNames[cal.get(Calendar.MONTH)], avgHoursPerDay))
                }

                monthlyData = entries

                // Trend detection
                if (entries.size >= 2) {
                    val firstHalf = entries.take(3).map { it.avgHoursPerDay.toDouble() }.average()
                    val secondHalf = entries.takeLast(3).map { it.avgHoursPerDay.toDouble() }.average()
                    trendText = when {
                        secondHalf > firstHalf * 1.1 -> "📈 Tendência: uso SUBINDO"
                        secondHalf < firstHalf * 0.9 -> "📉 Tendência: uso CAINDO — ótimo!"
                        else -> "➡️ Tendência: uso ESTÁVEL"
                    }
                }
            } catch (_: Exception) {
                noData = true
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp)
    ) {
        Text(
            text = "📊  Estatísticas de Uso",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Text(
            text = "Acompanhe seu tempo de tela",
            fontSize = 14.sp,
            color = TextSecondary,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        // Top 5 Apps Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = DarkCard),
            border = BorderStroke(1.dp, CardBorder)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "🔥  Top 5 Apps (7 dias)",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                if (noData || topApps.isEmpty()) {
                    Text(
                        text = "Sem dados de uso disponíveis.\nConceda a permissão de Acesso ao Uso.",
                        fontSize = 13.sp,
                        color = TextHint,
                        modifier = Modifier.padding(16.dp)
                    )
                } else {
                    val maxUsage = topApps.maxOf { it.usageTimeMs }.toFloat()
                    topApps.forEachIndexed { index, app ->
                        AppUsageItem(
                            rank = index + 1,
                            app = app,
                            maxUsage = maxUsage
                        )
                        if (index < topApps.lastIndex) {
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                }
            }
        }

        // Monthly Chart Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = DarkCard),
            border = BorderStroke(1.dp, CardBorder)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "📈  Tendência de Uso (6 meses)",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Text(
                    text = trendText,
                    fontSize = 12.sp,
                    color = TextSecondary,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                if (monthlyData.isNotEmpty()) {
                    AndroidView(
                        factory = { ctx ->
                            LineChart(ctx).apply {
                                val entries = monthlyData.mapIndexed { i, m ->
                                    Entry(i.toFloat(), m.avgHoursPerDay)
                                }
                                val labels = monthlyData.map { it.label }

                                val dataSet = LineDataSet(entries, "Horas/dia").apply {
                                    color = AccentCyan.toArgb()
                                    setCircleColor(AccentCyan.toArgb())
                                    lineWidth = 2.5f
                                    circleRadius = 4f
                                    setDrawCircleHole(true)
                                    circleHoleColor = DarkCard.toArgb()
                                    circleHoleRadius = 2f
                                    setDrawValues(true)
                                    valueTextColor = TextSecondary.toArgb()
                                    valueTextSize = 10f
                                    setDrawFilled(true)
                                    fillColor = AccentCyan.toArgb()
                                    fillAlpha = 30
                                    mode = LineDataSet.Mode.CUBIC_BEZIER
                                }

                                data = LineData(dataSet)
                                description.isEnabled = false
                                legend.isEnabled = false
                                setTouchEnabled(true)
                                isDragEnabled = false
                                setScaleEnabled(false)
                                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                                setDrawGridBackground(false)

                                xAxis.apply {
                                    position = XAxis.XAxisPosition.BOTTOM
                                    textColor = TextHint.toArgb()
                                    textSize = 11f
                                    setDrawGridLines(false)
                                    setDrawAxisLine(false)
                                    valueFormatter = IndexAxisValueFormatter(labels)
                                    granularity = 1f
                                }

                                axisLeft.apply {
                                    textColor = TextHint.toArgb()
                                    textSize = 10f
                                    setDrawGridLines(true)
                                    gridColor = Border.toArgb()
                                    setDrawAxisLine(false)
                                    axisMinimum = 0f
                                }

                                axisRight.isEnabled = false
                                animateX(800)
                                invalidate()
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun AppUsageItem(rank: Int, app: AppUsageInfo, maxUsage: Float) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Rank
        Text(
            text = "$rank",
            color = AccentCyan,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(28.dp)
        )

        // Icon
        if (app.icon != null) {
            val bitmap = remember(app.packageName) {
                app.icon.toBitmap(80, 80).asImageBitmap()
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
                    .background(DarkCardElevated)
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Info
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.appName,
                color = TextPrimary,
                fontSize = 14.sp,
                maxLines = 1
            )
            Text(
                text = formatDuration(app.usageTimeMs),
                color = TextSecondary,
                fontSize = 12.sp
            )
            // Progress bar
            val ratio = (app.usageTimeMs.toFloat() / maxUsage).coerceIn(0f, 1f)
            Spacer(modifier = Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Border)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(ratio)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(3.dp))
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(AccentCyan, AccentPurple)
                            )
                        )
                )
            }
        }
    }
}

private fun formatDuration(millis: Long): String {
    val hours = TimeUnit.MILLISECONDS.toHours(millis)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60
    return if (hours > 0) "${hours}h ${minutes}min" else "${minutes}min"
}
