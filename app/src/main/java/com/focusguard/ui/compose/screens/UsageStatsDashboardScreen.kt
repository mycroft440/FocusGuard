package com.focusguard.ui.compose.screens

import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.focusguard.database.DailyUsageStat
import com.focusguard.ui.compose.theme.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageStatsDashboardScreen(stats: List<DailyUsageStat>, onBack: () -> Unit) {
    val context = LocalContext.current
    val pm = context.packageManager
    
    val todayDate = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date()) }
    val todayStats = stats.filter { it.date == todayDate }
    val totalTimeToday = todayStats.sumOf { it.timeSpentMs }
    val mostUsedApp = todayStats.maxByOrNull { it.timeSpentMs }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Métricas e Uso", color = TextPrimary, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar", tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBg)
            )
        },
        containerColor = DarkBg
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Spacer(Modifier.height(8.dp))
                // Resumo do Dia
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkCard),
                    border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder)
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Timer, null, tint = AccentCyan)
                            Spacer(Modifier.width(8.dp))
                            Text("Tempo Total (Hoje)", color = TextSecondary, fontSize = 14.sp)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = formatTime(totalTimeToday),
                            color = TextPrimary,
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Black
                        )
                        
                        if (mostUsedApp != null) {
                            Spacer(Modifier.height(16.dp))
                            HorizontalDivider(color = CardBorder)
                            Spacer(Modifier.height(16.dp))
                            Text("Mais Usado Hoje", color = TextSecondary, fontSize = 12.sp)
                            val appName = try { pm.getApplicationLabel(pm.getApplicationInfo(mostUsedApp.identifier, 0)).toString() } catch(e:Exception) { mostUsedApp.identifier }
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                                Text(appName, color = AccentCyan, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                Spacer(Modifier.width(8.dp))
                                Text("(${formatTime(mostUsedApp.timeSpentMs)})", color = TextHint, fontSize = 14.sp)
                            }
                        }
                    }
                }
            }

            item {
                if (stats.isNotEmpty()) {
                    UsageBarChart(stats)
                } else {
                    Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                        Text("Sem dados de uso disponíveis para gráficos.", color = TextHint)
                    }
                }
            }

            item {
                Text(
                    "Detalhes de Uso (Histórico)", 
                    color = TextPrimary, 
                    fontSize = 18.sp, 
                    fontWeight = FontWeight.Bold, 
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                )
            }

            if (stats.isEmpty()) {
                item {
                    Text("Nenhum registro de uso detectado ainda.", color = TextHint, modifier = Modifier.padding(16.dp))
                }
            } else {
                items(stats.sortedByDescending { it.date + it.timeSpentMs }) { stat ->
                    UsageStatRow(stat, pm)
                }
            }
            
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@Composable
fun UsageBarChart(stats: List<DailyUsageStat>) {
    val dailyTotals = stats.groupBy { it.date }.mapValues { entry -> entry.value.sumOf { it.timeSpentMs } }.toSortedMap()
    val sortedDates: List<String> = dailyTotals.keys.toList().takeLast(7)
    val maxUsage = dailyTotals.values.maxOfOrNull { it }?.coerceAtLeast(60000L) ?: 60000L // Min 1 min
    
    Card(
        modifier = Modifier.fillMaxWidth().height(260.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Uso Diário (Últimos Dias)", color = TextPrimary, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(24.dp))
            Canvas(modifier = Modifier.fillMaxSize().padding(bottom = 20.dp, top = 10.dp)) {
                val canvasWidth = size.width
                val canvasHeight = size.height
                val barGap = 16.dp.toPx()
                val totalBars = sortedDates.size
                val barWidth = (canvasWidth - (totalBars - 1) * barGap) / totalBars.coerceAtLeast(1)
                
                sortedDates.forEachIndexed { index: Int, date: String ->
                    val totalMs = dailyTotals[date] ?: 0L
                    val barHeight = (totalMs.toFloat() / maxUsage.toFloat()) * canvasHeight
                    
                    // Draw Bar
                    drawRoundRect(
                        color = AccentCyan,
                        topLeft = Offset(x = index * (barWidth + barGap), y = canvasHeight - barHeight),
                        size = Size(width = barWidth, height = barHeight),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx())
                    )
                    
                    // Faint line at bottom
                    drawLine(
                        color = CardBorder,
                        start = Offset(0f, canvasHeight),
                        end = Offset(canvasWidth, canvasHeight),
                        strokeWidth = 1f
                    )
                }
            }
        }
    }
}

@Composable
fun UsageStatRow(stat: DailyUsageStat, pm: PackageManager) {
    val appName = try { pm.getApplicationLabel(pm.getApplicationInfo(stat.identifier, 0)).toString() } catch(e:Exception) { stat.identifier }
    val iconDrawable: Drawable? = try { pm.getApplicationIcon(stat.identifier) } catch(e:Exception) { null }
    val isDomain = stat.identifier.contains(".") && !stat.identifier.startsWith("com.") && iconDrawable == null

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (iconDrawable != null) {
                Image(
                    bitmap = iconDrawable.toBitmap().asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.size(40.dp).clip(CircleShape)
                )
            } else {
                Box(
                    modifier = Modifier.size(40.dp).background(DarkCardElevated, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(if (isDomain) Icons.Default.Language else Icons.Default.Timer, null, tint = AccentCyan, modifier = Modifier.size(24.dp))
                }
            }
            
            Spacer(Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(appName, color = TextPrimary, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(stat.date, color = TextHint, fontSize = 12.sp)
            }
            
            Text(
                text = formatTime(stat.timeSpentMs),
                color = AccentCyan,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
        }
    }
}

private fun formatTime(millis: Long): String {
    if (millis < 60000) return "< 1m"
    val totalMinutes = millis / 1000 / 60
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}