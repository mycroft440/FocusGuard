package com.focusguard.ui.compose.screens

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.focusguard.R
import com.focusguard.analytics.*
import com.focusguard.ui.compose.theme.*
import com.focusguard.utils.PermissionUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

private data class UsageInsightsData(
    val weeklyUsage: List<DailyPhoneUsage>,
    val mostUsedApps: List<AppUsageStat>,
    val openCloseEvents: List<AppEventStat>,
    val neverUsedApps: List<String>
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageStatsDashboardScreen(onBack: () -> Unit, showTopBar: Boolean = true) {
    val context = LocalContext.current
    val pm = context.packageManager
    val analytics = remember { AdvancedUsageAnalytics(context.applicationContext) }
    
    var weeklyUsage by remember { mutableStateOf<List<DailyPhoneUsage>>(emptyList()) }
    var mostUsedApps by remember { mutableStateOf<List<AppUsageStat>>(emptyList()) }
    var openCloseEvents by remember { mutableStateOf<List<AppEventStat>>(emptyList()) }
    var neverUsedApps by remember { mutableStateOf<List<String>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var hasUsageAccess by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }

    var showAverageForMostUsed by remember { mutableStateOf(false) }
    var expandMostUsed by remember { mutableStateOf(false) }
    var expandOpenClose by remember { mutableStateOf(false) }
    var expandNeverUsed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        isLoading = true
        loadError = null
        hasUsageAccess = PermissionUtils.isUsageAccessEnabled(context)

        if (!hasUsageAccess) {
            isLoading = false
            return@LaunchedEffect
        }

        try {
            val data = withContext(Dispatchers.IO) {
                val end = System.currentTimeMillis()
                val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -7) }
                val start7Days = cal.timeInMillis
                
                val calToday = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val startToday = calToday.timeInMillis

                UsageInsightsData(
                    weeklyUsage = analytics.getPhoneUsageHistory(14),
                    mostUsedApps = analytics.getMostUsedApps(start7Days, end),
                    openCloseEvents = analytics.getAppOpenCloseCounts(startToday, end),
                    neverUsedApps = analytics.getNeverUsedApps(start7Days, end)
                )
            }
            weeklyUsage = data.weeklyUsage
            mostUsedApps = data.mostUsedApps
            openCloseEvents = data.openCloseEvents
            neverUsedApps = data.neverUsedApps
        } catch (e: Exception) {
            loadError = e.localizedMessage
        } finally {
            isLoading = false
        }
    }

    Scaffold(
        topBar = {
            if (showTopBar) {
                TopAppBar(
                    title = { Text(stringResource(R.string.dashboard_title), color = TextPrimary, fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back), tint = TextPrimary)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        when {
            isLoading -> {
                Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AccentCyan)
                }
            }

            !hasUsageAccess -> {
                InsightsFallback(
                    modifier = Modifier.padding(padding),
                    title = stringResource(R.string.dashboard_permission_title),
                    message = stringResource(R.string.dashboard_permission_desc),
                    actionLabel = stringResource(R.string.dashboard_open_settings),
                    onAction = {
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                        }
                    }
                )
            }

            loadError != null -> {
                InsightsFallback(
                    modifier = Modifier.padding(padding),
                    title = stringResource(R.string.dashboard_load_failed_title),
                    message = loadError ?: stringResource(R.string.dashboard_load_failed_desc),
                    actionLabel = stringResource(R.string.action_back),
                    onAction = onBack
                )
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    item { Spacer(Modifier.height(8.dp)) }

                    item {
                        PhoneUsageChartSection(weeklyUsage)
                    }

                    item {
                        MostUsedAppsSection(
                            apps = mostUsedApps,
                            pm = pm,
                            showAverage = showAverageForMostUsed,
                            onToggleAverage = { showAverageForMostUsed = it },
                            expanded = expandMostUsed,
                            onToggleExpand = { expandMostUsed = it }
                        )
                    }

                    item {
                        OpenCloseEventsSection(
                            events = openCloseEvents,
                            pm = pm,
                            expanded = expandOpenClose,
                            onToggleExpand = { expandOpenClose = it }
                        )
                    }

                    item {
                        NeverUsedAppsSection(
                            apps = neverUsedApps,
                            pm = pm,
                            expanded = expandNeverUsed,
                            onToggleExpand = { expandNeverUsed = it }
                        )
                    }

                    item { Spacer(Modifier.height(32.dp)) }
                }
            }
        }
    }
}

@Composable
private fun InsightsFallback(
    modifier: Modifier = Modifier,
    title: String,
    message: String,
    actionLabel: String,
    onAction: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(modifier)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(42.dp))
                Spacer(Modifier.height(16.dp))
                Text(title, color = MaterialTheme.colorScheme.onSurface, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(20.dp))
                Button(onClick = onAction) {
                    Text(actionLabel)
                }
            }
        }
    }
}

@Composable
fun PhoneUsageChartSection(usageHistory: List<DailyPhoneUsage>) {
    val currentWeek = usageHistory.takeLast(7)
    val previousWeek = usageHistory.take(usageHistory.size - 7).takeLast(7)
    
    val currentTotal = currentWeek.sumOf { it.totalTimeMs }
    val currentAvg = currentTotal / currentWeek.size.coerceAtLeast(1)
    
    val previousTotal = previousWeek.sumOf { it.totalTimeMs }
    val previousAvg = previousTotal / previousWeek.size.coerceAtLeast(1)
    
    val diff = currentAvg - previousAvg
    val isIncrease = diff > 0

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.dashboard_phone_usage), color = MaterialTheme.colorScheme.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                
                if (previousWeek.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (isIncrease) androidx.compose.material.icons.Icons.Default.Timer else androidx.compose.material.icons.Icons.Default.Check,
                            contentDescription = null,
                            tint = if (isIncrease) DangerRed else SuccessGreen,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "${if (isIncrease) "+" else ""}${formatTime(Math.abs(diff))}",
                            color = if (isIncrease) DangerRed else SuccessGreen,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(stringResource(R.string.dashboard_vs_prev_week), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                    }
                }
            }
            
            Spacer(Modifier.height(16.dp))
            
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(stringResource(R.string.dashboard_total_week), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    Text(formatTime(currentTotal), color = AccentCyan, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(stringResource(R.string.dashboard_daily_avg), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    Text(formatTime(currentAvg), color = AccentPurple, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
            }
            
            Spacer(Modifier.height(24.dp))
            
            Column {
                Canvas(modifier = Modifier.fillMaxWidth().height(150.dp)) {
                    val maxTime = currentWeek.maxOfOrNull { it.totalTimeMs }?.coerceAtLeast(60000L) ?: 60000L
                    val barGap = 12.dp.toPx()
                    val totalBars = currentWeek.size
                    if (totalBars == 0) return@Canvas
                    val barWidth = (size.width - (totalBars - 1) * barGap) / totalBars
                    
                    currentWeek.forEachIndexed { index, usage ->
                        val barHeight = (usage.totalTimeMs.toFloat() / maxTime.toFloat()) * size.height
                        drawRoundRect(
                            color = AccentCyan,
                            topLeft = Offset(x = index * (barWidth + barGap), y = size.height - barHeight),
                            size = Size(width = barWidth, height = barHeight),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx())
                        )
                    }
                    
                    val avgY = size.height - ((currentAvg.toFloat() / maxTime.toFloat()) * size.height)
                    drawLine(
                        color = AccentPurple.copy(alpha = 0.5f),
                        start = Offset(0f, avgY),
                        end = Offset(size.width, avgY),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 10f))
                    )
                }
                
                Spacer(Modifier.height(8.dp))
                
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    currentWeek.forEach { usage ->
                        val label = usage.dateLabel.take(1)
                        Text(
                            text = label,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.width(20.dp),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MostUsedAppsSection(
    apps: List<AppUsageStat>, 
    pm: PackageManager, 
    showAverage: Boolean, 
    onToggleAverage: (Boolean) -> Unit,
    expanded: Boolean,
    onToggleExpand: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.dashboard_most_used), color = MaterialTheme.colorScheme.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.dashboard_daily_avg), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    Switch(checked = showAverage, onCheckedChange = onToggleAverage, modifier = Modifier.scale(0.8f))
                }
            }
            
            Spacer(Modifier.height(16.dp))
            
            val displayList = if (expanded) apps else apps.take(5)
            
            if (displayList.isEmpty()) {
                Text(stringResource(R.string.dashboard_no_data), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                displayList.forEach { stat ->
                    val timeToDisplay = if (showAverage) stat.timeSpentMs / 7 else stat.timeSpentMs
                    AppUsageRow(stat.packageName, timeToDisplay, pm)
                    Spacer(Modifier.height(12.dp))
                }
            }
            
            if (apps.size > 5) {
                TextButton(onClick = { onToggleExpand(!expanded) }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (expanded) stringResource(R.string.dashboard_hide) else stringResource(R.string.dashboard_show_all, apps.size), color = AccentCyan)
                }
            }
        }
    }
}

@Composable
fun OpenCloseEventsSection(
    events: List<AppEventStat>,
    pm: PackageManager,
    expanded: Boolean,
    onToggleExpand: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.dashboard_open_close_title), color = MaterialTheme.colorScheme.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            
            val displayList = if (expanded) events else events.take(5)
            
            if (displayList.isEmpty()) {
                Text(stringResource(R.string.dashboard_no_events), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                displayList.forEach { event ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(event.packageName, pm, 32)
                        Spacer(Modifier.width(12.dp))
                        Text(getAppName(event.packageName, pm), color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f), maxLines = 1)
                        Column(horizontalAlignment = Alignment.End) {
                            Text(stringResource(R.string.dashboard_opened_count, event.openCount), color = DangerRed, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text(stringResource(R.string.dashboard_closed_count, event.closeCount), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        }
                    }
                }
            }
            
            if (events.size > 5) {
                TextButton(onClick = { onToggleExpand(!expanded) }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (expanded) stringResource(R.string.dashboard_hide) else stringResource(R.string.dashboard_show_all, events.size), color = AccentCyan)
                }
            }
        }
    }
}

@Composable
fun NeverUsedAppsSection(
    apps: List<String>,
    pm: PackageManager,
    expanded: Boolean,
    onToggleExpand: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.dashboard_never_used), color = MaterialTheme.colorScheme.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            
            val displayList = if (expanded) apps else apps.take(3)
            
            if (displayList.isEmpty()) {
                Text(stringResource(R.string.dashboard_no_inactive), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                displayList.forEach { pkg ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(pkg, pm, 32)
                        Spacer(Modifier.width(12.dp))
                        Text(getAppName(pkg, pm), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), maxLines = 1)
                    }
                }
            }
            
            if (apps.size > 3) {
                TextButton(onClick = { onToggleExpand(!expanded) }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (expanded) stringResource(R.string.dashboard_hide) else stringResource(R.string.dashboard_show_all, apps.size), color = AccentCyan)
                }
            }
        }
    }
}

@Composable
fun AppUsageRow(pkg: String, timeMs: Long, pm: PackageManager) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        AppIcon(pkg, pm, 40)
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(getAppName(pkg, pm), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, maxLines = 1)
        }
        Text(formatTime(timeMs), color = AccentCyan, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun AppIcon(pkg: String, pm: PackageManager, size: Int) {
    val iconDrawable: Drawable? = try { pm.getApplicationIcon(pkg) } catch(e:Exception) { null }
    if (iconDrawable != null) {
        Image(
            bitmap = iconDrawable.toBitmap().asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.size(size.dp).clip(CircleShape)
        )
    } else {
        Box(
            modifier = Modifier.size(size.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Language, null, tint = AccentCyan, modifier = Modifier.size((size/2).dp))
        }
    }
}

fun getAppName(pkg: String, pm: PackageManager): String {
    return try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch(e:Exception) { pkg }
}

private fun formatTime(millis: Long): String {
    if (millis < 60000) return "< 1m"
    val totalMinutes = millis / 1000 / 60
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

