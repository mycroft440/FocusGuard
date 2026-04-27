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
                0 -> AppLimitsTab()
                1 -> WebsiteLimitsTab()
            }
        }
    }
}

@Composable
fun AppLimitsTab() {
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
            val existingLimits = limitDao.getAll().associateBy { it.packageName }
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
                val configuredApps = filteredApps(apps, searchQuery).filter { it.currentLimitMinutes != null }
                val unconfiguredApps = filteredApps(apps, searchQuery).filter { it.currentLimitMinutes == null }

                if (configuredApps.isNotEmpty()) {
                    item { Text("Aplicativos com Limite", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AccentCyan, modifier = Modifier.padding(vertical = 8.dp)) }
                    items(configuredApps, key = { it.packageName }) { app ->
                        UsageLimitItem(app) { 
                            if (app.lockMode == "TIME" && app.lockUntilTimestamp != null && System.currentTimeMillis() < app.lockUntilTimestamp) {
                                showTimeLockedAlert = true
                            } else if (app.lockMode == "PASSWORD") {
                                pendingAction = { selectedApp = app; showDialog = true }
                                showPasswordConfirm = true
                            } else {
                                selectedApp = app; showDialog = true 
                            }
                        }
                    }
                    item { Spacer(Modifier.height(16.dp)); HorizontalDivider(color = CardBorder); Spacer(Modifier.height(16.dp)) }
                }
                item { Text("Todos os Aplicativos", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary, modifier = Modifier.padding(vertical = 8.dp)) }
                items(unconfiguredApps, key = { it.packageName }) { app ->
                    UsageLimitItem(app) { selectedApp = app; showDialog = true }
                }
            }
        }
    }

    if (showDialog && selectedApp != null) {
        AppLimitDialog(
            app = selectedApp!!,
            onDismiss = { showDialog = false },
            onSave = { minutes, enabled, lockMode, lockPassword, lockUntil ->
                scope.launch(Dispatchers.IO) {
                    val db = AppDatabase.getDatabase(context).appUsageLimitDao()
                    if (minutes != null && minutes > 0) {
                        db.insert(AppUsageLimit(selectedApp!!.packageName, selectedApp!!.appName, minutes, enabled, lockMode, lockPassword, lockUntil))
                        val updated = selectedApp!!.copy(currentLimitMinutes = minutes, isEnabled = enabled, lockMode = lockMode, lockPasswordHash = lockPassword, lockUntilTimestamp = lockUntil)
                        apps = apps.map { if (it.packageName == updated.packageName) updated else it }
                    } else {
                        val existing = db.getAll().find { it.packageName == selectedApp!!.packageName }
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
fun WebsiteLimitsTab() {
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
            val allLimits = db.websiteUsageLimitDao().getAll()
            val usageStats = db.dailyUsageStatDao().getStatsForDate(today).associate { it.identifier to it.timeSpentMs }
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
                                        val existing = db.getAll().find { it.domain == site.domain }
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
        AddWebsiteLimitDialog(onDismiss = { showAddDialog = false }, onSave = { domain, minutes, lockMode, lockPassword, lockUntil ->
            scope.launch(Dispatchers.IO) {
                val db = AppDatabase.getDatabase(context).websiteUsageLimitDao()
                val clean = domain.trim().lowercase().removePrefix("http://").removePrefix("https://").removePrefix("www.").trimEnd('/')
                db.insert(WebsiteUsageLimit(clean, minutes, true, lockMode, lockPassword, lockUntil))
                withContext(Dispatchers.Main) { sites = sites + WebsiteLimitUi(clean, minutes, true, 0L, lockMode, lockPassword, lockUntil); showAddDialog = false }
            }
        })
    }

    if (showEditDialog && selectedSite != null) {
        EditWebsiteLimitDialog(site = selectedSite!!, onDismiss = { showEditDialog = false }, onSave = { minutes, enabled, lockMode, lockPassword, lockUntil ->
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
fun UsageLimitItem(app: UsageLimitAppUi, onClick: () -> Unit) {
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
            if (app.icon != null) {
                val bitmap = remember(app.packageName) { app.icon.toBitmap(80, 80).asImageBitmap() }
                Image(bitmap, null, Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)))
            } else {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(DarkCardElevated), Alignment.Center) { Text("📱", fontSize = 18.sp) }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(app.appName, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                if (app.currentLimitMinutes != null) {
                    val usedMin = app.usageMs / 60000
                    val progress = if (app.currentLimitMinutes > 0) (usedMin.toFloat() / app.currentLimitMinutes).coerceIn(0f, 1f) else 0f
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("$usedMin / ${app.currentLimitMinutes} min", fontSize = 12.sp, color = if (app.isEnabled) AccentCyan else TextHint)
                        Spacer(Modifier.weight(1f))
                        Text(if (app.isEnabled) "Ativo" else "Desativado", fontSize = 11.sp, color = if (app.isEnabled) AccentCyan else TextHint)
                    }
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = progress,
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                        color = if (progress >= 0.9f) DangerRed else AccentCyan,
                        trackColor = DarkCardElevated
                    )
                } else { Text("Sem limite configurado", fontSize = 12.sp, color = TextHint) }
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
fun UsageSecurityDialog(authManager: AuthManager, isLocked: Boolean, onDismiss: () -> Unit, onLock: (Int) -> Unit) {
    var daysText by remember { mutableStateOf("7") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Segurança dos Limites", color = TextPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                if (isLocked) {
                    Text("Esta seção está BLINDADA. Você só pode ver os limites atuais. Para alterar, aguarde o fim do prazo ou use a senha mestre em cada item.", color = DangerRed, fontSize = 14.sp)
                } else {
                    Text("Deseja blindar as configurações de limites por alguns dias? Durante esse tempo, qualquer alteração exigirá a senha mestre.", color = TextSecondary, fontSize = 14.sp)
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = daysText,
                        onValueChange = { if (it.all { c -> c.isDigit() }) daysText = it },
                        label = { Text("Dias de Blindagem", color = TextHint) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AccentCyan, unfocusedTextColor = TextPrimary, focusedTextColor = TextPrimary),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            if (!isLocked) {
                TextButton(onClick = { onLock(daysText.toIntOrNull() ?: 7) }) { Text("Ativar Blindagem", color = AccentCyan) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Fechar", color = TextHint) } },
        containerColor = DarkSurface
    )
}

@Composable
fun ConfirmPasswordDialog(authManager: AuthManager, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Confirmar Senha", color = TextPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text("Esta ação é protegida. Digite a senha mestre para continuar.", color = TextSecondary, fontSize = 14.sp)
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
                scope.launch {
                    if (authManager.verifyPassword(password)) onConfirm() else error = true
                }
            }) { Text("Confirmar", color = AccentCyan) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar", color = TextHint) } },
        containerColor = DarkSurface
    )
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
                val hash = java.security.MessageDigest.getInstance("SHA-256").digest(password.toByteArray()).joinToString("") { "%02x".format(it) }
                if (hash == expectedHash || expectedHash.isEmpty()) onConfirm() else error = true
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
    onDaysChange: (String) -> Unit
) {
    Column {
        Text("Modo de Segurança", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 14.sp)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("NONE" to "Sem Proteção", "PASSWORD" to "Senha", "TIME" to "Blindagem").forEach { (mode, label) ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { onLockModeChange(mode) }) {
                    RadioButton(selected = lockMode == mode, onClick = { onLockModeChange(mode) }, colors = RadioButtonDefaults.colors(selectedColor = AccentCyan))
                    Text(label, color = TextPrimary, fontSize = 12.sp)
                }
            }
        }
        if (lockMode == "PASSWORD") {
            OutlinedTextField(
                value = password,
                onValueChange = onPasswordChange,
                label = { Text("Senha para alterar/remover") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AccentCyan, unfocusedTextColor = TextPrimary, focusedTextColor = TextPrimary)
            )
        } else if (lockMode == "TIME") {
            OutlinedTextField(
                value = days,
                onValueChange = { if (it.all { c -> c.isDigit() }) onDaysChange(it) },
                label = { Text("Dias de blindagem") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AccentCyan, unfocusedTextColor = TextPrimary, focusedTextColor = TextPrimary)
            )
        }
    }
}

@Composable
fun AppLimitDialog(app: UsageLimitAppUi, onDismiss: () -> Unit, onSave: (Int?, Boolean, String, String?, Long?) -> Unit) {
    var minutesText by remember { mutableStateOf(app.currentLimitMinutes?.toString() ?: "") }
    var isEnabled by remember { mutableStateOf(app.isEnabled || app.currentLimitMinutes == null) }
    var lockMode by remember { mutableStateOf(app.lockMode) }
    var password by remember { mutableStateOf("") }
    var days by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Configurar Limite", color = TextPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                OutlinedTextField(
                    value = minutesText,
                    onValueChange = { if (it.isEmpty() || it.all { c -> c.isDigit() }) minutesText = it },
                    label = { Text("Minutos por dia", color = TextHint) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AccentCyan, unfocusedTextColor = TextPrimary, focusedTextColor = TextPrimary),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(isEnabled, { isEnabled = it }, colors = CheckboxDefaults.colors(AccentCyan, DarkBg))
                    Text("Ativar este limite", color = TextPrimary, fontSize = 14.sp)
                }
                Spacer(Modifier.height(16.dp))
                LimitSecuritySection(lockMode, { lockMode = it }, password, { password = it }, days, { days = it })
            }
        },
        confirmButton = { 
            TextButton(onClick = { 
                val hash = if (lockMode == "PASSWORD" && password.isNotEmpty()) java.security.MessageDigest.getInstance("SHA-256").digest(password.toByteArray()).joinToString("") { "%02x".format(it) } else app.lockPasswordHash
                val until = if (lockMode == "TIME" && days.isNotEmpty()) System.currentTimeMillis() + TimeUnit.DAYS.toMillis(days.toLong()) else app.lockUntilTimestamp
                onSave(minutesText.toIntOrNull(), isEnabled, lockMode, hash, until) 
            }) { Text("Salvar", color = AccentCyan) } 
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar", color = TextHint) } },
        containerColor = DarkSurface
    )
}

@Composable
fun AddWebsiteLimitDialog(onDismiss: () -> Unit, onSave: (String, Int, String, String?, Long?) -> Unit) {
    var domain by remember { mutableStateOf("") }
    var minutesText by remember { mutableStateOf("") }
    var lockMode by remember { mutableStateOf("NONE") }
    var password by remember { mutableStateOf("") }
    var days by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Adicionar Site", color = TextPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                OutlinedTextField(domain, { domain = it }, label = { Text("Domínio") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(minutesText, { if (it.all { c -> c.isDigit() }) minutesText = it }, label = { Text("Minutos") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(16.dp))
                LimitSecuritySection(lockMode, { lockMode = it }, password, { password = it }, days, { days = it })
            }
        },
        confirmButton = { 
            TextButton({ 
                if (domain.isNotBlank()) {
                    val hash = if (lockMode == "PASSWORD" && password.isNotEmpty()) java.security.MessageDigest.getInstance("SHA-256").digest(password.toByteArray()).joinToString("") { "%02x".format(it) } else null
                    val until = if (lockMode == "TIME" && days.isNotEmpty()) System.currentTimeMillis() + TimeUnit.DAYS.toMillis(days.toLong()) else null
                    onSave(domain, minutesText.toIntOrNull() ?: 0, lockMode, hash, until)
                }
            }) { Text("Salvar", color = AccentCyan) } 
        },
        containerColor = DarkSurface
    )
}

@Composable
fun EditWebsiteLimitDialog(site: WebsiteLimitUi, onDismiss: () -> Unit, onSave: (Int, Boolean, String, String?, Long?) -> Unit) {
    var minutesText by remember { mutableStateOf(site.dailyLimitMinutes?.toString() ?: "") }
    var isEnabled by remember { mutableStateOf(site.isEnabled) }
    var lockMode by remember { mutableStateOf(site.lockMode) }
    var password by remember { mutableStateOf("") }
    var days by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Editar Limite", color = TextPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                OutlinedTextField(minutesText, { if (it.all { c -> c.isDigit() }) minutesText = it }, label = { Text("Minutos") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(isEnabled, { isEnabled = it }); Text("Ativar") }
                Spacer(Modifier.height(16.dp))
                LimitSecuritySection(lockMode, { lockMode = it }, password, { password = it }, days, { days = it })
            }
        },
        confirmButton = { 
            TextButton({ 
                val hash = if (lockMode == "PASSWORD" && password.isNotEmpty()) java.security.MessageDigest.getInstance("SHA-256").digest(password.toByteArray()).joinToString("") { "%02x".format(it) } else site.lockPasswordHash
                val until = if (lockMode == "TIME" && days.isNotEmpty()) System.currentTimeMillis() + TimeUnit.DAYS.toMillis(days.toLong()) else site.lockUntilTimestamp
                onSave(minutesText.toIntOrNull() ?: 0, isEnabled, lockMode, hash, until) 
            }) { Text("Salvar", color = AccentCyan) } 
        },
        containerColor = DarkSurface
    )
}
