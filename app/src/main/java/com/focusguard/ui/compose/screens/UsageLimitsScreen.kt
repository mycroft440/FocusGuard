package com.focusguard.ui.compose.screens

import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.focusguard.database.AppDatabase
import com.focusguard.database.AppUsageLimit
import com.focusguard.database.WebsiteUsageLimit
import com.focusguard.database.UsageLimitsLock
import com.focusguard.ui.compose.theme.*
import com.focusguard.security.AuthManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

data class UsageLimitAppUi(
    val packageName: String,
    val appName: String,
    val icon: android.graphics.drawable.Drawable?,
    val currentLimitMinutes: Int?,
    val isEnabled: Boolean,
    val usageMs: Long = 0,
    val lockMode: String = "NONE",
    val lockPasswordHash: String? = null,
    val lockUntilTimestamp: Long? = null
)

data class WebsiteLimitUi(
    val domain: String,
    val dailyLimitMinutes: Int?,
    val isEnabled: Boolean,
    val usageMs: Long = 0,
    val lockMode: String = "NONE",
    val lockPasswordHash: String? = null,
    val lockUntilTimestamp: Long? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageLimitsScreen(authManager: AuthManager, onBack: () -> Unit) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var showSecurityDialog by remember { mutableStateOf(false) }
    var isLockedByTime by remember { mutableStateOf(false) }
    var lockTimeRemaining by remember { mutableStateOf("") }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var permissionsMissing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(context).usageLimitsLockDao()
            val lock = db.getLock()
            if (lock != null && System.currentTimeMillis() < lock.lockedUntilTimestamp) {
                isLockedByTime = true
                val diff = lock.lockedUntilTimestamp - System.currentTimeMillis()
                val days = TimeUnit.MILLISECONDS.toDays(diff)
                val hours = TimeUnit.MILLISECONDS.toHours(diff) % 24
                lockTimeRemaining = if (days > 0) "$days dias e $hours h" else "$hours horas"
            }

            val deviceOwnerManager = com.focusguard.admin.DeviceOwnerManager(context)
            val isA11yEnabled = com.focusguard.utils.PermissionUtils.isAccessibilityServiceEnabled(context)
            val isAdminActive = deviceOwnerManager.isDeviceAdminActive() || deviceOwnerManager.isDeviceOwnerActive()
            val isUsageAccessEnabled = com.focusguard.utils.PermissionUtils.isUsageAccessEnabled(context)
            val isBatteryIgnored = com.focusguard.utils.PermissionUtils.isBatteryOptimizationIgnored(context)
            permissionsMissing = !isA11yEnabled || !isAdminActive || !isUsageAccessEnabled || !isBatteryIgnored
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Limites de Uso", color = TextPrimary, fontSize = 18.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar", tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBg)
            )
        },
        containerColor = DarkBg
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = DarkCard,
                contentColor = AccentCyan
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Aplicativos", color = if (selectedTab == 0) AccentCyan else TextHint, fontWeight = FontWeight.Bold) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Sites", color = if (selectedTab == 1) AccentCyan else TextHint, fontWeight = FontWeight.Bold) }
                )
            }

            when (selectedTab) {
                0 -> AppLimitsTab(permissionsMissing)
                1 -> WebsiteLimitsTab(permissionsMissing)
            }
        }
    }
}

@Composable
fun AppLimitsTab(permissionsMissing: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var apps by remember { mutableStateOf<List<UsageLimitAppUi>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }
    var selectedApp by remember { mutableStateOf<UsageLimitAppUi?>(null) }
    var showDialog by remember { mutableStateOf(false) }
    var showPasswordConfirm by remember { mutableStateOf(false) }
    var showTimeLockedAlert by remember { mutableStateOf(false) }
    var pendingAction: (() -> Unit)? by remember { mutableStateOf(null) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            val resolveInfos = pm.queryIntentActivities(intent, 0)
            val db = AppDatabase.getDatabase(context)
            val limitDao = db.appUsageLimitDao()
            val existingLimits = limitDao.getAllStatic().associateBy { it.packageName }
            val usageStatsManager = context.getSystemService(android.content.Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
            val cal = java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0) }
            val stats = usageStatsManager.queryAndAggregateUsageStats(cal.timeInMillis, System.currentTimeMillis())

            val loadedApps = resolveInfos.mapNotNull { info ->
                val packageName = info.activityInfo.packageName
                if (packageName == context.packageName) return@mapNotNull null
                val appName = info.loadLabel(pm).toString()
                val icon = try { info.loadIcon(pm) } catch (_: Exception) { null }
                val limit = existingLimits[packageName]
                UsageLimitAppUi(
                    packageName, appName, icon, limit?.dailyLimitMinutes, limit?.isEnabled ?: false, 
                    stats[packageName]?.totalTimeInForeground ?: 0L,
                    limit?.lockMode ?: "NONE", limit?.lockPasswordHash, limit?.lockUntilTimestamp
                )
            }.sortedBy { it.appName }

            withContext(Dispatchers.Main) { apps = loadedApps; isLoading = false }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Buscar aplicativo...", color = TextHint) },
            leadingIcon = { Icon(Icons.Filled.Search, null, tint = TextHint) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AccentCyan, unfocusedTextColor = TextPrimary, focusedTextColor = TextPrimary),
            shape = RoundedCornerShape(16.dp),
            singleLine = true
        )

        if (isLoading) {
            Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = AccentCyan) }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                val allFiltered = filteredApps(apps, searchQuery)
                val activeLimits = allFiltered.filter { it.currentLimitMinutes != null && it.isEnabled }
                val inactiveLimits = allFiltered.filter { it.currentLimitMinutes != null && !it.isEnabled }
                val unconfiguredApps = allFiltered.filter { it.currentLimitMinutes == null }

                // 1. Prioridade Máxima: Limites Ativos
                if (activeLimits.isNotEmpty()) {
                    item { 
                        Text(
                            "Limites Ativos", 
                            fontSize = 16.sp, 
                            fontWeight = FontWeight.ExtraBold, 
                            color = AccentCyan, 
                            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                        ) 
                    }
                    items(activeLimits, key = { "active_${it.packageName}" }) { app ->
                        UsageLimitItem(app, isActive = true) { 
                            if (app.lockMode == "PASSWORD") {
                                pendingAction = { selectedApp = app; showDialog = true }
                                showPasswordConfirm = true
                            } else {
                                selectedApp = app; showDialog = true 
                            }
                        }
                    }
                }

                // 2. Limites Configurados mas Desativados
                if (inactiveLimits.isNotEmpty()) {
                    item { 
                        Text(
                            "Limites Pausados", 
                            fontSize = 14.sp, 
                            fontWeight = FontWeight.Bold, 
                            color = TextHint, 
                            modifier = Modifier.padding(top = 24.dp, bottom = 8.dp)
                        ) 
                    }
                    items(inactiveLimits, key = { "inactive_${it.packageName}" }) { app ->
                        UsageLimitItem(app, isActive = false) { 
                            selectedApp = app; showDialog = true 
                        }
                    }
                }

                // 3. Demais Aplicativos (Apenas se a busca estiver vazia ou houver resultados)
                if (unconfiguredApps.isNotEmpty()) {
                    val sectionTitle = if (activeLimits.isEmpty() && inactiveLimits.isEmpty()) "Configurar Limites" else "Outros Aplicativos"
                    item { 
                        Text(
                            sectionTitle, 
                            fontSize = 14.sp, 
                            fontWeight = FontWeight.Bold, 
                            color = TextSecondary, 
                            modifier = Modifier.padding(top = 24.dp, bottom = 8.dp)
                        ) 
                    }
                    items(unconfiguredApps, key = { "unconf_${it.packageName}" }) { app ->
                        UsageLimitItem(app, isActive = false) { 
                            selectedApp = app; showDialog = true 
                        }
                    }
                }
            }
        }
    }

    if (showDialog && selectedApp != null) {
        AppLimitDialog(
            app = selectedApp!!,
            permissionsMissing = permissionsMissing,
            onDismiss = { showDialog = false },
            onSave = { minutes, enabled, lockMode, lockPassword, lockUntil ->
                scope.launch(Dispatchers.IO) {
                    val db = AppDatabase.getDatabase(context).appUsageLimitDao()
                    if (minutes != null && minutes > 0) {
                        db.insert(AppUsageLimit(selectedApp!!.packageName, selectedApp!!.appName, minutes, enabled, lockMode, lockPassword, lockUntil))
                        val updated = selectedApp!!.copy(currentLimitMinutes = minutes, isEnabled = enabled, lockMode = lockMode, lockPasswordHash = lockPassword, lockUntilTimestamp = lockUntil)
                        apps = apps.map { if (it.packageName == updated.packageName) updated else it }
                    } else {
                        val existing = db.getAllStatic().find { it.packageName == selectedApp!!.packageName }
                        if (existing != null) db.delete(existing)
                        val updated = selectedApp!!.copy(currentLimitMinutes = null, isEnabled = false, lockMode = "NONE", lockPasswordHash = null, lockUntilTimestamp = null)
                        apps = apps.map { if (it.packageName == updated.packageName) updated else it }
                    }
                    withContext(Dispatchers.Main) { showDialog = false }
                }
            }
        )
    }

    if (showPasswordConfirm && selectedApp != null) {
        ConfirmLimitPasswordDialog(
            expectedHash = selectedApp?.lockPasswordHash ?: "",
            onDismiss = { showPasswordConfirm = false },
            onConfirm = { 
                showPasswordConfirm = false
                pendingAction?.invoke()
                pendingAction = null
            }
        )
    }

    if (showTimeLockedAlert) {
        AlertDialog(
            onDismissRequest = { showTimeLockedAlert = false },
            title = { Text("Limite Blindado", color = DangerRed) },
            text = { Text("Este limite está blindado por tempo e não pode ser alterado até o fim do prazo.", color = TextPrimary) },
            confirmButton = { TextButton({ showTimeLockedAlert = false }) { Text("OK", color = AccentCyan) } },
            containerColor = DarkSurface
        )
    }
}

@Composable
fun WebsiteLimitsTab(permissionsMissing: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sites by remember { mutableStateOf<List<WebsiteLimitUi>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var showAddDialog by remember { mutableStateOf(false) }
    var selectedSite by remember { mutableStateOf<WebsiteLimitUi?>(null) }
    var showEditDialog by remember { mutableStateOf(false) }
    var showPasswordConfirm by remember { mutableStateOf(false) }
    var showTimeLockedAlert by remember { mutableStateOf(false) }
    var pendingAction: (() -> Unit)? by remember { mutableStateOf(null) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(context)
            val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
            val allLimits = db.websiteUsageLimitDao().getAllStatic()
            val usageStats = db.dailyUsageStatDao().getStatsForDateStatic(today).associate { it.identifier to it.timeSpentMs }
            sites = allLimits.map { WebsiteLimitUi(it.domain, it.dailyLimitMinutes, it.isEnabled, usageStats[it.domain] ?: 0L, it.lockMode, it.lockPasswordHash, it.lockUntilTimestamp) }
            withContext(Dispatchers.Main) { isLoading = false }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Button(
            onClick = { showAddDialog = true },
            modifier = Modifier.fillMaxWidth().padding(16.dp, 12.dp).height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
            shape = RoundedCornerShape(14.dp)
        ) {
            Icon(Icons.Default.Add, null, tint = DarkBg, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Adicionar Site", color = DarkBg, fontWeight = FontWeight.Bold)
        }

        if (isLoading) {
            Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = AccentCyan) }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                items(sites, key = { it.domain }) { site ->
                    WebsiteLimitItem(
                        site = site,
                        onClick = {
                            if (site.lockMode == "TIME" && site.lockUntilTimestamp != null && System.currentTimeMillis() < site.lockUntilTimestamp) {
                                showTimeLockedAlert = true
                            } else if (site.lockMode == "PASSWORD") {
                                pendingAction = { selectedSite = site; showEditDialog = true }
                                showPasswordConfirm = true
                            } else {
                                selectedSite = site; showEditDialog = true 
                            }
                        },
                        onDelete = {
                            if (site.lockMode == "TIME" && site.lockUntilTimestamp != null && System.currentTimeMillis() < site.lockUntilTimestamp) {
                                showTimeLockedAlert = true
                            } else {
                                val action = {
                                    scope.launch(Dispatchers.IO) {
                                        val db = AppDatabase.getDatabase(context).websiteUsageLimitDao()
                                        val existing = db.getAllStatic().find { it.domain == site.domain }
                                        if (existing != null) db.delete(existing)
                                        withContext(Dispatchers.Main) { sites = sites.filter { it.domain != site.domain } }
                                    }
                                    Unit
                                }
                                if (site.lockMode == "PASSWORD") {
                                    selectedSite = site
                                    pendingAction = action
                                    showPasswordConfirm = true
                                } else {
                                    action()
                                }
                            }
                        }
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        AddWebsiteLimitDialog(permissionsMissing = permissionsMissing, onDismiss = { showAddDialog = false }, onSave = { domain, minutes, lockMode, lockPassword, lockUntil ->
            scope.launch(Dispatchers.IO) {
                val db = AppDatabase.getDatabase(context).websiteUsageLimitDao()
                val clean = domain.trim().lowercase().removePrefix("http://").removePrefix("https://").removePrefix("www.").trimEnd('/')
                db.insert(WebsiteUsageLimit(clean, minutes, true, lockMode, lockPassword, lockUntil))
                withContext(Dispatchers.Main) { sites = sites + WebsiteLimitUi(clean, minutes, true, 0L, lockMode, lockPassword, lockUntil); showAddDialog = false }
            }
        })
    }

    if (showEditDialog && selectedSite != null) {
        EditWebsiteLimitDialog(site = selectedSite!!, permissionsMissing = permissionsMissing, onDismiss = { showEditDialog = false }, onSave = { minutes, enabled, lockMode, lockPassword, lockUntil ->
            scope.launch(Dispatchers.IO) {
                val db = AppDatabase.getDatabase(context).websiteUsageLimitDao()
                db.insert(WebsiteUsageLimit(selectedSite!!.domain, minutes, enabled, lockMode, lockPassword, lockUntil))
                withContext(Dispatchers.Main) {
                    sites = sites.map { if (it.domain == selectedSite!!.domain) it.copy(dailyLimitMinutes = minutes, isEnabled = enabled, lockMode = lockMode, lockPasswordHash = lockPassword, lockUntilTimestamp = lockUntil) else it }
                    showEditDialog = false
                }
            }
        })
    }

    if (showPasswordConfirm && selectedSite != null) {
        ConfirmLimitPasswordDialog(
            expectedHash = selectedSite?.lockPasswordHash ?: "",
            onDismiss = { showPasswordConfirm = false },
            onConfirm = { 
                showPasswordConfirm = false
                pendingAction?.invoke()
                pendingAction = null
            }
        )
    }

    if (showTimeLockedAlert) {
        AlertDialog(
            onDismissRequest = { showTimeLockedAlert = false },
            title = { Text("Limite Blindado", color = DangerRed) },
            text = { Text("Este limite está blindado por tempo e não pode ser alterado até o fim do prazo.", color = TextPrimary) },
            confirmButton = { TextButton({ showTimeLockedAlert = false }) { Text("OK", color = AccentCyan) } },
            containerColor = DarkSurface
        )
    }
}

private fun filteredApps(apps: List<UsageLimitAppUi>, query: String) = 
    if (query.isBlank()) apps else apps.filter { it.appName.contains(query, true) || it.packageName.contains(query, true) }

@Composable
fun UsageLimitItem(app: UsageLimitAppUi, isActive: Boolean = false, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) DarkCardElevated else DarkCard
        ),
        border = BorderStroke(
            width = if (isActive) 1.5.dp else 1.dp,
            color = if (isActive) AccentCyan.copy(alpha = 0.5f) else CardBorder
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isActive) 4.dp else 0.dp)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (app.icon != null) {
                val bitmap = remember(app.packageName) { app.icon.toBitmap(80, 80).asImageBitmap() }
                Image(
                    bitmap, 
                    null, 
                    Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                )
            } else {
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(DarkCardElevated), 
                    Alignment.Center
                ) { Text("📱", fontSize = 20.sp) }
            }
            
            Spacer(Modifier.width(14.dp))
            
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        app.appName, 
                        fontSize = 15.sp, 
                        fontWeight = FontWeight.Bold, 
                        color = if (isActive) AccentCyan else TextPrimary
                    )
                    if (app.lockMode != "NONE") {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            imageVector = if (app.lockMode == "TIME") Icons.Default.Lock else Icons.Default.Security,
                            contentDescription = null,
                            tint = if (isActive) AccentCyan else TextHint,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
                
                if (app.currentLimitMinutes != null) {
                    val usedMin = app.usageMs / 60000
                    val progress = if (app.currentLimitMinutes > 0) (usedMin.toFloat() / app.currentLimitMinutes).coerceIn(0f, 1f) else 0f
                    
                    Spacer(Modifier.height(4.dp))
                    
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "$usedMin / ${app.currentLimitMinutes} min", 
                            fontSize = 12.sp, 
                            color = if (isActive) TextPrimary else TextSecondary
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            if (isActive) "Monitorando" else "Pausado", 
                            fontSize = 11.sp, 
                            color = if (isActive) AccentCyan else TextHint,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    
                    Spacer(Modifier.height(6.dp))
                    
                    LinearProgressIndicator(
                        progress = progress,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(CircleShape),
                        color = when {
                            !isActive -> TextHint.copy(alpha = 0.5f)
                            progress >= 0.9f -> DangerRed
                            else -> AccentCyan
                        },
                        trackColor = DarkBg
                    )
                } else { 
                    Text("Sem limite configurado", fontSize = 12.sp, color = TextHint) 
                }
            }
        }
    }
}

@Composable
fun WebsiteLimitItem(site: WebsiteLimitUi, onClick: () -> Unit, onDelete: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onClick() },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        border = BorderStroke(1.dp, CardBorder)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(AccentCyan.copy(0.12f)), Alignment.Center) { Text("🌐", fontSize = 20.sp) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(site.domain, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                if (site.dailyLimitMinutes != null) {
                    val usedMin = site.usageMs / 60000
                    val progress = if (site.dailyLimitMinutes > 0) (usedMin.toFloat() / site.dailyLimitMinutes).coerceIn(0f, 1f) else 0f
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("$usedMin / ${site.dailyLimitMinutes} min", fontSize = 12.sp, color = if (site.isEnabled) AccentCyan else TextHint)
                        Spacer(Modifier.weight(1f))
                        Text(if (site.isEnabled) "Ativo" else "Desativado", fontSize = 11.sp, color = if (site.isEnabled) AccentCyan else TextHint)
                    }
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = progress,
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                        color = if (progress >= 0.9f) DangerRed else AccentCyan,
                        trackColor = DarkCardElevated
                    )
                }
            }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Remover", tint = DangerRed, modifier = Modifier.size(20.dp)) }
        }
    }
}

@Composable
fun ConfirmLimitPasswordDialog(expectedHash: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Confirmar Senha", color = TextPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text("Digite a senha configurada para este limite.", color = TextSecondary, fontSize = 14.sp)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; error = false },
                    label = { Text("Senha", color = if (error) DangerRed else TextHint) },
                    visualTransformation = PasswordVisualTransformation(),
                    isError = error,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AccentCyan, unfocusedTextColor = TextPrimary, focusedTextColor = TextPrimary),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                // Use the standard legacy hash for secondary locks if stretching isn't available
                // In a future update, we should migrate all secondary locks to stretching-hash too.
                val hash = com.focusguard.security.AuthManager.hashPasswordLegacy(password)
                if (expectedHash.isEmpty() || hash == expectedHash) onConfirm() else error = true
            }) { Text("Confirmar", color = AccentCyan) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar", color = TextHint) } },
        containerColor = DarkSurface
    )
}

@Composable
fun LimitSecuritySection(
    lockMode: String,
    onLockModeChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    days: String,
    onDaysChange: (String) -> Unit,
    onConfirmed: (Boolean) -> Unit
) {
    var agreeText by remember { mutableStateOf("") }
    var allowPasswordUnlock by remember { mutableStateOf(lockMode == "PASSWORD") }

    Column {
        Text("Modo de Segurança", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 14.sp)
        Spacer(Modifier.height(8.dp))
        
        Column(modifier = Modifier.fillMaxWidth().background(DarkCardElevated, RoundedCornerShape(12.dp)).padding(12.dp)) {
            Text("Impedir que o app seja aberto depois que o tempo acabar", color = TextPrimary, fontSize = 13.sp)
            Text("Aviso: quando o tempo acabar não será possível desbloquear o app nem com senha.", color = DangerRed, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            
            Spacer(Modifier.height(16.dp))
            
            OutlinedTextField(
                value = days,
                onValueChange = { if (it.all { c -> c.isDigit() }) onDaysChange(it) },
                label = { Text("Por quantos dias deseja limitar o tempo?", color = TextHint) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AccentCyan, unfocusedTextColor = TextPrimary, focusedTextColor = TextPrimary)
            )
            
            Spacer(Modifier.height(16.dp))
            
            Text("Desbloquear app quando o tempo acabar utilizando uma senha?", color = TextPrimary, fontSize = 13.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { 
                    allowPasswordUnlock = false
                    onLockModeChange("TIME")
                }) {
                    RadioButton(selected = !allowPasswordUnlock, onClick = { 
                        allowPasswordUnlock = false
                        onLockModeChange("TIME")
                    }, colors = RadioButtonDefaults.colors(selectedColor = AccentCyan))
                    Text("Não", color = TextPrimary, fontSize = 14.sp)
                }
                Spacer(Modifier.width(20.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { 
                    allowPasswordUnlock = true
                    onLockModeChange("PASSWORD")
                }) {
                    RadioButton(selected = allowPasswordUnlock, onClick = { 
                        allowPasswordUnlock = true
                        onLockModeChange("PASSWORD")
                    }, colors = RadioButtonDefaults.colors(selectedColor = AccentCyan))
                    Text("Sim", color = TextPrimary, fontSize = 14.sp)
                }
            }

            if (allowPasswordUnlock) {
                OutlinedTextField(
                    value = password,
                    onValueChange = onPasswordChange,
                    label = { Text("Defina a senha de desbloqueio", color = TextHint) },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AccentCyan, unfocusedTextColor = TextPrimary, focusedTextColor = TextPrimary)
                )
                LaunchedEffect(password) { onConfirmed(password.isNotEmpty()) }
            } else {
                Spacer(Modifier.height(12.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = DangerRed.copy(alpha = 0.1f)),
                    border = BorderStroke(1.dp, DangerRed.copy(alpha = 0.3f))
                ) {
                    Column(Modifier.padding(8.dp)) {
                        Text("Atenção: caso o bloqueio seja sem senha o app será impossível de desbloquear até o dia seguinte ou fim dos dias bloqueio.", color = DangerRed, fontSize = 11.sp)
                        Text("Você concorda?", color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = agreeText,
                            onValueChange = { 
                                agreeText = it
                                onConfirmed(it.lowercase() == "sim")
                            },
                            placeholder = { Text("Escreva 'sim' para continuar", color = TextHint, fontSize = 11.sp) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = if(agreeText.lowercase() == "sim") AccentCyan else DangerRed, unfocusedTextColor = TextPrimary, focusedTextColor = TextPrimary),
                            singleLine = true
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AppLimitDialog(app: UsageLimitAppUi, permissionsMissing: Boolean, onDismiss: () -> Unit, onSave: (Int?, Boolean, String, String?, Long?) -> Unit) {
    val isEditMode = app.currentLimitMinutes != null
    var hoursText by remember { mutableStateOf(if(isEditMode) (app.currentLimitMinutes!! / 60f).toString() else "") }
    var lockMode by remember { mutableStateOf(app.lockMode) }
    var password by remember { mutableStateOf("") }
    var days by remember { mutableStateOf("") }
    var isConfirmed by remember { mutableStateOf(!isEditMode && lockMode == "NONE") }
    
    // For Edit Mode (Extend Only)
    var extensionDays by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { 
            Text(
                if (isEditMode) "Informações do Limite: ${app.appName}" 
                else "Definir tempo máximo para ${app.appName}", 
                color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp
            ) 
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (permissionsMissing) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = DangerRed.copy(alpha = 0.1f)),
                        border = BorderStroke(1.dp, DangerRed.copy(alpha = 0.5f)),
                        modifier = Modifier.padding(bottom = 16.dp).fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = DangerRed, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "Permissões necessárias para bloqueio",
                                color = DangerRed, fontSize = 12.sp, fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                if (isEditMode) {
                    // MODO EDIÇÃO: Apenas Informações e Extensão
                    Card(
                        colors = CardDefaults.cardColors(containerColor = DarkCardElevated),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, CardBorder)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Tempo diário configurado:", color = TextHint, fontSize = 12.sp)
                            Text("${app.currentLimitMinutes} minutos", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            
                            Spacer(Modifier.height(12.dp))
                            
                            Text("Segurança:", color = TextHint, fontSize = 12.sp)
                            val securityText = when(app.lockMode) {
                                "TIME" -> "Blindado por Tempo"
                                "PASSWORD" -> "Protegido por Senha"
                                else -> "Sem proteção extra"
                            }
                            Text(securityText, color = if(app.lockMode != "NONE") AccentCyan else TextPrimary, fontSize = 14.sp)

                            if (app.lockUntilTimestamp != null && app.lockUntilTimestamp > System.currentTimeMillis()) {
                                val date = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm").format(java.util.Date(app.lockUntilTimestamp))
                                Spacer(Modifier.height(8.dp))
                                Text("Inviolável até: $date", color = DangerRed, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Text("Adicionar mais dias de bloqueio", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = extensionDays,
                        onValueChange = { if (it.all { c -> c.isDigit() }) extensionDays = it },
                        placeholder = { Text("Ex: 7", color = TextHint) },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AccentCyan, unfocusedTextColor = TextPrimary, focusedTextColor = TextPrimary),
                        suffix = { Text("dias") }
                    )
                    Text("A opção de adicionar dias torna o bloqueio impossível de ser removido até o fim do prazo.", color = TextHint, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                } else {
                    // MODO CRIAÇÃO: Interface Completa
                    OutlinedTextField(
                        value = hoursText,
                        onValueChange = { if (it.isEmpty() || it.replace(",", ".").toDoubleOrNull() != null || it == ".") hoursText = it },
                        label = { Text("Limite diário em horas", color = TextHint) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AccentCyan, unfocusedTextColor = TextPrimary, focusedTextColor = TextPrimary),
                        modifier = Modifier.fillMaxWidth()
                    )
                    
                    Spacer(Modifier.height(24.dp))
                    LimitSecuritySection(lockMode, { lockMode = it }, password, { password = it }, days, { days = it }, { isConfirmed = it })
                }
            }
        },
        confirmButton = { 
            TextButton(
                enabled = !isEditMode && (isConfirmed || lockMode == "NONE") || isEditMode,
                onClick = { 
                    if (isEditMode) {
                        // Salvar apenas extensão de dias
                        val extraDays = extensionDays.toLongOrNull() ?: 0L
                        if (extraDays > 0) {
                            val currentUntil = app.lockUntilTimestamp ?: System.currentTimeMillis()
                            val base = if (currentUntil < System.currentTimeMillis()) System.currentTimeMillis() else currentUntil
                            val newUntil = base + TimeUnit.DAYS.toMillis(extraDays)
                            // Mantém tudo igual, só altera o lockMode para TIME se não for e estende o prazo
                            onSave(app.currentLimitMinutes, app.isEnabled, "TIME", app.lockPasswordHash, newUntil)
                        } else {
                            onDismiss()
                        }
                    } else {
                        val hours = hoursText.replace(",", ".").toDoubleOrNull() ?: 0.0
                        val minutes = (hours * 60).toInt()
                        val hash = if (lockMode == "PASSWORD" && password.isNotEmpty()) {
                            com.focusguard.security.AuthManager.hashPasswordLegacy(password)
                        } else null
                        
                        val until = if (lockMode == "TIME" && days.isNotEmpty()) System.currentTimeMillis() + TimeUnit.DAYS.toMillis(days.toLong()) else null
                        onSave(if(minutes > 0) minutes else null, true, lockMode, hash, until) 
                    }
                }
            ) { 
                Text(
                    if(isEditMode) "Estender Bloqueio" else "Salvar", 
                    color = if(!isEditMode && (isConfirmed || lockMode == "NONE") || isEditMode) AccentCyan else TextHint
                ) 
            } 
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar", color = TextHint) } },
        containerColor = DarkSurface
    )
}

@Composable
fun AddWebsiteLimitDialog(permissionsMissing: Boolean, onDismiss: () -> Unit, onSave: (String, Int, String, String?, Long?) -> Unit) {
    var domain by remember { mutableStateOf("") }
    var hoursText by remember { mutableStateOf("") }
    var lockMode by remember { mutableStateOf("NONE") }
    var password by remember { mutableStateOf("") }
    var days by remember { mutableStateOf("") }
    var isConfirmed by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Adicionar Site", color = TextPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(domain, { domain = it }, label = { Text("Domínio") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(hoursText, { if (it.isEmpty() || it.replace(",", ".").toDoubleOrNull() != null) hoursText = it }, label = { Text("Limite diário em horas") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(16.dp))
                LimitSecuritySection(lockMode, { lockMode = it }, password, { password = it }, days, { days = it }, { isConfirmed = it })
            }
        },
        confirmButton = { 
            TextButton(
                enabled = isConfirmed || lockMode == "NONE",
                onClick = { 
                    if (domain.isNotBlank()) {
                        val hours = hoursText.replace(",", ".").toDoubleOrNull() ?: 0.0
                        val minutes = (hours * 60).toInt()
                        val hash = if (lockMode == "PASSWORD" && password.isNotEmpty()) com.focusguard.security.AuthManager.hashPasswordLegacy(password) else null
                        val until = if (lockMode == "TIME" && days.isNotEmpty()) System.currentTimeMillis() + TimeUnit.DAYS.toMillis(days.toLong()) else null
                        onSave(domain, minutes, lockMode, hash, until)
                    }
                }
            ) { Text("Salvar", color = AccentCyan) } 
        },
        containerColor = DarkSurface
    )
}

@Composable
fun EditWebsiteLimitDialog(site: WebsiteLimitUi, permissionsMissing: Boolean, onDismiss: () -> Unit, onSave: (Int, Boolean, String, String?, Long?) -> Unit) {
    var extensionDays by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Informações do Limite: ${site.domain}", color = TextPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = DarkCardElevated),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, CardBorder)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Tempo diário:", color = TextHint, fontSize = 12.sp)
                        Text("${site.dailyLimitMinutes} minutos", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        
                        if (site.lockUntilTimestamp != null && site.lockUntilTimestamp > System.currentTimeMillis()) {
                            val date = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm").format(java.util.Date(site.lockUntilTimestamp))
                            Spacer(Modifier.height(12.dp))
                            Text("Inviolável até: $date", color = DangerRed, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Text("Adicionar mais dias de bloqueio", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = extensionDays,
                    onValueChange = { if (it.all { c -> c.isDigit() }) extensionDays = it },
                    placeholder = { Text("Ex: 7", color = TextHint) },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AccentCyan, unfocusedTextColor = TextPrimary, focusedTextColor = TextPrimary),
                    suffix = { Text("dias") }
                )
            }
        },
        confirmButton = { 
            TextButton(
                onClick = { 
                    val extraDays = extensionDays.toLongOrNull() ?: 0L
                    if (extraDays > 0) {
                        val currentUntil = site.lockUntilTimestamp ?: System.currentTimeMillis()
                        val base = if (currentUntil < System.currentTimeMillis()) System.currentTimeMillis() else currentUntil
                        val newUntil = base + TimeUnit.DAYS.toMillis(extraDays)
                        onSave(site.dailyLimitMinutes ?: 0, site.isEnabled, "TIME", site.lockPasswordHash, newUntil) 
                    } else {
                        onDismiss()
                    }
                }
            ) { Text("Estender Bloqueio", color = AccentCyan) } 
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar", color = TextHint) } },
        containerColor = DarkSurface
    )
}