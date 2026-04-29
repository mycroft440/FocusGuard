package com.focusguard.ui.compose.screens

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import com.focusguard.manager.UsageLimitManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.concurrent.TimeUnit

data class AppUsageInfo(
    val packageName: String,
    val appName: String,
    val icon: Drawable?,
    val usageTimeMs: Long,
    val launchCount: Int = 0
)

data class MonthlyUsage(
    val label: String,
    val avgHoursPerDay: Float
)

@Composable
fun UsageStatsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val limitManager = remember { UsageLimitManager.getInstance(context) }

    var topApps by remember { mutableStateOf<List<AppUsageInfo>>(emptyList()) }
    var topLaunched by remember { mutableStateOf<List<AppUsageInfo>>(emptyList()) }
    var monthlyData by remember { mutableStateOf<List<MonthlyUsage>>(emptyList()) }
    var trendText by remember { mutableStateOf("Média diária de uso do telefone") }
    var noData by remember { mutableStateOf(false) }

    // Dialog State
    var selectedAppForLimit by remember { mutableStateOf<AppUsageInfo?>(null) }
    var limitHours by remember { mutableStateOf("1") }
    var limitMinutes by remember { mutableStateOf("0") }

    val authManager = remember { com.focusguard.security.AuthManager(context) }
    val isSafetyMode = authManager.isSafetyModeEnabled()

    if (selectedAppForLimit != null) {
        AlertDialog(
            onDismissRequest = { selectedAppForLimit = null },
            containerColor = DarkSurface,
            title = { Text("Configurar Limite", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        "Definir qual o tempo máximo por dia você quer usar o ${selectedAppForLimit?.appName}?",
                        color = TextSecondary, fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    if (isSafetyMode) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = DangerRed.copy(alpha = 0.1f)),
                            border = BorderStroke(1.dp, DangerRed.copy(alpha = 0.3f)),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                        ) {
                            Text(
                                "O modo segurança está ativo, não é possível burlar ou alterar configurações de limite.",
                                color = DangerRed, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(12.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = limitHours,
                                onValueChange = { limitHours = it },
                                label = { Text("Horas", color = TextHint) },
                                modifier = Modifier.weight(1f),
                                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, focusedBorderColor = AccentCyan)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            OutlinedTextField(
                                value = limitMinutes,
                                onValueChange = { limitMinutes = it },
                                label = { Text("Minutos", color = TextHint) },
                                modifier = Modifier.weight(1f),
                                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, focusedBorderColor = AccentCyan)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                if (!isSafetyMode) {
                    Row {
                        TextButton(
                            onClick = {
                                scope.launch {
                                    limitManager.removeLimit(selectedAppForLimit!!.packageName)
                                    withContext(Dispatchers.Main) {
                                        android.widget.Toast.makeText(context, "Limite removido", android.widget.Toast.LENGTH_SHORT).show()
                                        selectedAppForLimit = null
                                    }
                                }
                            }
                        ) { Text("Remover", color = DangerRed) }
                        
                        Spacer(modifier = Modifier.width(8.dp))
                        
                        Button(
                            onClick = {
                                val h = limitHours.toIntOrNull() ?: 0
                                val m = limitMinutes.toIntOrNull() ?: 0
                                val totalMin = h * 60 + m
                                if (totalMin > 0) {
                                    scope.launch {
                                        limitManager.setLimit(selectedAppForLimit!!.packageName, selectedAppForLimit!!.appName, totalMin)
                                        withContext(Dispatchers.Main) {
                                            android.widget.Toast.makeText(context, "Limite de $h h $m min definido para ${selectedAppForLimit?.appName}", android.widget.Toast.LENGTH_SHORT).show()
                                            selectedAppForLimit = null
                                        }
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentCyan)
                        ) { Text("Salvar", color = DarkBg, fontWeight = FontWeight.Bold) }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedAppForLimit = null }) { Text(if (isSafetyMode) "Fechar" else "Cancelar", color = TextHint) }
            }
        )
    }

    // Summary metrics
    var totalScreenTimeToday by remember { mutableStateOf(0L) }
    var totalAppsUsedToday by remember { mutableStateOf(0) }
    var totalLaunchesToday by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
                val pm = context.packageManager

                // ========================================
                // 1. TODAY'S SUMMARY
                // ========================================
                val todayCal = Calendar.getInstance()
                todayCal.set(Calendar.HOUR_OF_DAY, 0)
                todayCal.set(Calendar.MINUTE, 0)
                todayCal.set(Calendar.SECOND, 0)
                todayCal.set(Calendar.MILLISECOND, 0)
                val todayStart = todayCal.timeInMillis
                val now = System.currentTimeMillis()

                val todayStats = usageStatsManager.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY, todayStart, now
                )

                if (todayStats != null) {
                    var totalTimeToday = 0L
                    val appsUsedToday = mutableSetOf<String>()
                    for (stat in todayStats) {
                        if (stat.totalTimeInForeground > 0 && stat.packageName != context.packageName) {
                            totalTimeToday += stat.totalTimeInForeground
                            appsUsedToday.add(stat.packageName)
                        }
                    }
                    totalScreenTimeToday = totalTimeToday
                    totalAppsUsedToday = appsUsedToday.size
                }

                // Count today's app launches via UsageEvents
                val todayLaunchCounts = countAppLaunches(usageStatsManager, todayStart, now, context.packageName)
                totalLaunchesToday = todayLaunchCounts.values.sum()

                // ========================================
                // 2. TOP 5 APPS BY SCREEN TIME (7 days)
                // ========================================
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

                // Get launch counts for 7 days
                val weekLaunchCounts = countAppLaunches(usageStatsManager, startTime, endTime, context.packageName)

                topApps = top5.map { entry ->
                    val appName = try {
                        pm.getApplicationLabel(pm.getApplicationInfo(entry.key, 0)).toString()
                    } catch (_: PackageManager.NameNotFoundException) {
                        entry.key.substringAfterLast(".")
                    }
                    val icon = try { pm.getApplicationIcon(entry.key) } catch (_: Exception) { null }
                    AppUsageInfo(entry.key, appName, icon, entry.value, weekLaunchCounts[entry.key] ?: 0)
                }

                // ========================================
                // 3. TOP 10 MOST LAUNCHED APPS (7 days)
                // ========================================
                val topLaunchedEntries = weekLaunchCounts.entries
                    .filter { it.value > 0 && !it.key.contains("launcher") && it.key != "com.android.settings" }
                    .sortedByDescending { it.value }
                    .take(10)

                topLaunched = topLaunchedEntries.map { entry ->
                    val appName = try {
                        pm.getApplicationLabel(pm.getApplicationInfo(entry.key, 0)).toString()
                    } catch (_: PackageManager.NameNotFoundException) {
                        entry.key.substringAfterLast(".")
                    }
                    val icon = try { pm.getApplicationIcon(entry.key) } catch (_: Exception) { null }
                    val usageTime = filteredMap[entry.key] ?: 0L
                    AppUsageInfo(entry.key, appName, icon, usageTime, entry.value)
                }

                // ========================================
                // 4. MONTHLY TREND CHART (6 months)
                // ========================================
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
            .padding(horizontal = 20.dp, vertical = 24.dp)
    ) {
        Text(
            text = "Estatísticas de Uso",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Text(
            text = "Acompanhe seu tempo de tela e aberturas",
            fontSize = 14.sp,
            color = TextSecondary,
            modifier = Modifier.padding(bottom = 20.dp)
        )

        // ========================================
        // TODAY'S SUMMARY CARDS
        // ========================================
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SummaryMetricCard(
                icon = Icons.Default.PhoneAndroid,
                label = "Tempo de tela",
                value = formatDuration(totalScreenTimeToday),
                accentColor = AccentCyan,
                modifier = Modifier.weight(1f)
            )
            SummaryMetricCard(
                icon = Icons.Default.Apps,
                label = "Apps usados",
                value = "$totalAppsUsedToday",
                accentColor = AccentPurple,
                modifier = Modifier.weight(1f)
            )
            SummaryMetricCard(
                icon = Icons.Default.TouchApp,
                label = "Aberturas",
                value = "$totalLaunchesToday",
                accentColor = WarningAmber,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // ========================================
        // TOP 5 APPS BY SCREEN TIME
        // ========================================
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = DarkCard),
            border = BorderStroke(1.dp, CardBorder)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Timer, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Top 5 por Tempo (7 dias)", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                }
                Spacer(modifier = Modifier.height(16.dp))

                if (noData || topApps.isEmpty()) {
                    Text(
                        text = "Sem dados de uso disponíveis.\nConceda a permissão de Acesso ao Uso.",
                        fontSize = 13.sp, color = TextHint, modifier = Modifier.padding(16.dp)
                    )
                } else {
                    val maxUsage = topApps.maxOf { it.usageTimeMs }.toFloat()
                    topApps.forEachIndexed { index, app ->
                        AppUsageItem(
                            rank = index + 1, app = app, maxUsage = maxUsage, showLaunches = true,
                            onClick = { selectedAppForLimit = it }
                        )
                        if (index < topApps.lastIndex) Spacer(modifier = Modifier.height(10.dp))
                    }
                }
            }
        }

        // ========================================
        // RANKING: MOST LAUNCHED APPS
        // ========================================
        if (topLaunched.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = DarkCard),
                border = BorderStroke(1.dp, CardBorder)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.TouchApp, contentDescription = null, tint = WarningAmber, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Ranking de Aberturas (7 dias)", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Quantas vezes cada app foi aberto/fechado", fontSize = 12.sp, color = TextSecondary)
                    Spacer(modifier = Modifier.height(16.dp))

                    val maxLaunches = topLaunched.maxOf { it.launchCount }.toFloat()
                    topLaunched.forEachIndexed { index, app ->
                        LaunchRankItem(
                            rank = index + 1, app = app, maxLaunches = maxLaunches,
                            onClick = { selectedAppForLimit = it }
                        )
                        if (index < topLaunched.lastIndex) Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        }

        // ========================================
        // MONTHLY TREND CHART
        // ========================================
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = DarkCard),
            border = BorderStroke(1.dp, CardBorder)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.ShowChart, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Tendência (6 meses)", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(trendText, fontSize = 12.sp, color = TextSecondary)
                Spacer(modifier = Modifier.height(16.dp))

                if (monthlyData.isNotEmpty()) {
                    AndroidView(
                        factory = { ctx ->
                            LineChart(ctx).apply {
                                val chartEntries = monthlyData.mapIndexed { i, m -> Entry(i.toFloat(), m.avgHoursPerDay) }
                                val labels = monthlyData.map { it.label }

                                val dataSet = LineDataSet(chartEntries, "Horas/dia").apply {
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
                        modifier = Modifier.fillMaxWidth().height(220.dp)
                    )
                }
            }
        }
    }
}

// ========================================
// COMPOSABLE COMPONENTS
// ========================================

@Composable
fun SummaryMetricCard(
    icon: ImageVector,
    label: String,
    value: String,
    accentColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        border = BorderStroke(1.dp, CardBorder)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(accentColor.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = accentColor, modifier = Modifier.size(20.dp))
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            Text(label, fontSize = 10.sp, color = TextHint, maxLines = 1)
        }
    }
}

@Composable
fun AppUsageItem(rank: Int, app: AppUsageInfo, maxUsage: Float, showLaunches: Boolean = false, onClick: (AppUsageInfo) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick(app) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Medal rank
        val rankColor = when (rank) {
            1 -> WarningAmber
            2 -> TextSecondary
            3 -> Color(0xFFCD7F32)
            else -> TextHint
        }
        Text(
            text = "#$rank",
            color = rankColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(32.dp)
        )

        // Icon
        if (app.icon != null) {
            val bitmap = remember(app.packageName) { app.icon.toBitmap(80, 80).asImageBitmap() }
            Image(
                bitmap = bitmap, contentDescription = app.appName,
                modifier = Modifier.size(38.dp).clip(RoundedCornerShape(10.dp))
            )
        } else {
            Box(modifier = Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(DarkCardElevated))
        }

        Spacer(modifier = Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(app.appName, color = TextPrimary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row {
                Text(formatDuration(app.usageTimeMs), color = TextSecondary, fontSize = 11.sp)
                if (showLaunches && app.launchCount > 0) {
                    Text(" · ${app.launchCount}x aberto", color = TextHint, fontSize = 11.sp)
                }
            }
            // Progress bar
            val ratio = (app.usageTimeMs.toFloat() / maxUsage).coerceIn(0f, 1f)
            Spacer(modifier = Modifier.height(4.dp))
            Box(
                modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(Border)
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth(ratio).fillMaxHeight().clip(RoundedCornerShape(3.dp))
                        .background(Brush.horizontalGradient(listOf(AccentCyan, AccentPurple)))
                )
            }
        }
    }
}

@Composable
fun LaunchRankItem(rank: Int, app: AppUsageInfo, maxLaunches: Float, onClick: (AppUsageInfo) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick(app) }
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val rankColor = when (rank) {
            1 -> WarningAmber
            2 -> TextSecondary
            3 -> Color(0xFFCD7F32)
            else -> TextHint
        }
        Text("#$rank", color = rankColor, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(32.dp))

        if (app.icon != null) {
            val bitmap = remember(app.packageName) { app.icon.toBitmap(64, 64).asImageBitmap() }
            Image(bitmap = bitmap, contentDescription = app.appName, modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)))
        } else {
            Box(modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(DarkCardElevated))
        }

        Spacer(modifier = Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(app.appName, color = TextPrimary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text("${app.launchCount}x", color = WarningAmber, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(3.dp))
            val ratio = (app.launchCount.toFloat() / maxLaunches).coerceIn(0f, 1f)
            Box(modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Border)) {
                Box(
                    modifier = Modifier.fillMaxWidth(ratio).fillMaxHeight().clip(RoundedCornerShape(2.dp))
                        .background(Brush.horizontalGradient(listOf(WarningAmber, DangerRed)))
                )
            }
        }
    }
}

// ========================================
// UTILITY FUNCTIONS
// ========================================

private fun countAppLaunches(
    usageStatsManager: UsageStatsManager,
    startTime: Long,
    endTime: Long,
    ownPackage: String
): Map<String, Int> {
    val launchCounts = mutableMapOf<String, Int>()
    try {
        val events = usageStatsManager.queryEvents(startTime, endTime)
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            // ACTIVITY_RESUMED = app was brought to foreground = "opened"
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                val pkg = event.packageName
                if (pkg != ownPackage && !pkg.contains("launcher") && pkg != "com.android.settings") {
                    launchCounts[pkg] = (launchCounts[pkg] ?: 0) + 1
                }
            }
        }
    } catch (_: Exception) {
        // UsageEvents may not be available on some devices
    }
    return launchCounts
}

private fun formatDuration(millis: Long): String {
    val hours = TimeUnit.MILLISECONDS.toHours(millis)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60
    return if (hours > 0) "${hours}h ${minutes}min" else "${minutes}min"
}
