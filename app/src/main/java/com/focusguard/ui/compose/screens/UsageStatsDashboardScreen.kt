package com.focusguard.ui.compose.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.focusguard.database.DailyUsageStat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageStatsDashboardScreen(stats: List<DailyUsageStat>, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Estatísticas Detalhadas") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Voltar")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                if (stats.isNotEmpty()) {
                    UsageBarChart(stats)
                } else {
                    Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                        Text("Sem dados de uso disponíveis.", style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }

            item {
                Text("Histórico por Item", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            }

            items(stats.sortedByDescending { it.date }) { stat ->
                UsageStatRow(stat)
            }
        }
    }
}

@Composable
fun UsageBarChart(stats: List<DailyUsageStat>) {
    val barColor = MaterialTheme.colorScheme.primary
    val dailyTotals = stats.groupBy { it.date }.mapValues { entry -> entry.value.sumOf { it.timeSpentMs } }
    val sortedDates = dailyTotals.keys.sorted().takeLast(7)
    val maxUsage = dailyTotals.values.maxOfOrNull { it } ?: 1L
    
    Card(
        modifier = Modifier.fillMaxWidth().height(250.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Uso Total Diário (Últimos 7 dias)", style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(16.dp))
            Canvas(modifier = Modifier.fillMaxSize()) {
                val canvasWidth = size.width
                val canvasHeight = size.height
                val barGap = 12.dp.toPx()
                val totalBars = sortedDates.size
                val barWidth = (canvasWidth - (totalBars - 1) * barGap) / totalBars.coerceAtLeast(1)
                
                sortedDates.forEachIndexed { index, date ->
                    val totalMs = dailyTotals[date] ?: 0L
                    val barHeight = (totalMs.toFloat() / maxUsage) * canvasHeight
                    drawRect(
                        color = barColor,
                        topLeft = Offset(x = index * (barWidth + barGap), y = canvasHeight - barHeight),
                        size = Size(width = barWidth, height = barHeight)
                    )
                }
            }
        }
    }
}

@Composable
fun UsageStatRow(stat: DailyUsageStat) {
    val minutes = stat.timeSpentMs / 1000 / 60
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stat.identifier, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text(stat.date, style = MaterialTheme.typography.bodySmall)
            }
            Text("${minutes}m", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}