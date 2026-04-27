package com.focusguard.ui.compose.screens

import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.foundation.BorderStroke
import com.focusguard.database.AppDatabase
import com.focusguard.database.AppUsageLimit
import com.focusguard.database.WebsiteUsageLimit
import com.focusguard.ui.compose.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UsageLimitAppUi(
    val packageName: String,
    val appName: String,
    val icon: android.graphics.drawable.Drawable?,
    val currentLimitMinutes: Int?,
    val isEnabled: Boolean
)

data class WebsiteLimitUi(
    val domain: String,
    val dailyLimitMinutes: Int?,
    val isEnabled: Boolean
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageLimitsScreen(onBack: () -> Unit) {
    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Limites de Uso", color = TextPrimary) },
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
            // Tab Row
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

// ========================================
// ABA DE LIMITES DE APLICATIVOS
// ========================================

@Composable
fun AppLimitsTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var apps by remember { mutableStateOf<List<UsageLimitAppUi>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }
    var selectedApp by remember { mutableStateOf<UsageLimitAppUi?>(null) }
    var showDialog by remember { mutableStateOf(false) }

    val filteredApps = remember(apps, searchQuery) {
        if (searchQuery.isBlank()) apps
        else apps.filter {
            it.appName.contains(searchQuery, ignoreCase = true) ||
            it.packageName.contains(searchQuery, ignoreCase = true)
        }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            val resolveInfos = pm.queryIntentActivities(intent, 0)

            val db = AppDatabase.getDatabase(context).appUsageLimitDao()
            val existingLimits = db.getAll().associateBy { it.packageName }

            val loadedApps = resolveInfos.mapNotNull { info ->
                val packageName = info.activityInfo.packageName
                if (packageName == context.packageName) return@mapNotNull null
                val appName = info.loadLabel(pm).toString()
                val icon = try { info.loadIcon(pm) } catch (_: Exception) { null }
                val limit = existingLimits[packageName]
                UsageLimitAppUi(
                    packageName = packageName,
                    appName = appName,
                    icon = icon,
                    currentLimitMinutes = limit?.dailyLimitMinutes,
                    isEnabled = limit?.isEnabled ?: false
                )
            }.sortedBy { it.appName }

            withContext(Dispatchers.Main) {
                apps = loadedApps
                isLoading = false
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Buscar aplicativo...", color = TextHint) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = TextHint) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AccentCyan,
                cursorColor = AccentCyan,
                unfocusedTextColor = TextPrimary,
                focusedTextColor = TextPrimary
            ),
            shape = RoundedCornerShape(16.dp),
            singleLine = true
        )

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AccentCyan)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                val configuredApps = filteredApps.filter { it.currentLimitMinutes != null }
                val unconfiguredApps = filteredApps.filter { it.currentLimitMinutes == null }

                if (configuredApps.isNotEmpty()) {
                    item {
                        Text("Aplicativos com Limite", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AccentCyan, modifier = Modifier.padding(vertical = 8.dp))
                    }
                    items(configuredApps, key = { it.packageName }) { app ->
                        UsageLimitItem(app) {
                            selectedApp = app
                            showDialog = true
                        }
                    }
                    item {
                        Spacer(modifier = Modifier.height(16.dp))
                        Divider(color = CardBorder, thickness = 1.dp)
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }

                item {
                    Text("Todos os Aplicativos", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary, modifier = Modifier.padding(vertical = 8.dp))
                }
                items(unconfiguredApps, key = { it.packageName }) { app ->
                    UsageLimitItem(app) {
                        selectedApp = app
                        showDialog = true
                    }
                }
            }
        }
    }

    if (showDialog && selectedApp != null) {
        AppLimitDialog(
            app = selectedApp!!,
            onDismiss = { showDialog = false },
            onSave = { minutes, enabled ->
                scope.launch(Dispatchers.IO) {
                    val db = AppDatabase.getDatabase(context).appUsageLimitDao()
                    if (minutes != null && minutes > 0) {
                        db.insert(AppUsageLimit(selectedApp!!.packageName, selectedApp!!.appName, minutes, enabled))
                        com.focusguard.utils.FocusGuardLogger.log("Limits", "Limite de app salvo: ${selectedApp!!.packageName} = $minutes min")
                        val updated = selectedApp!!.copy(currentLimitMinutes = minutes, isEnabled = enabled)
                        apps = apps.map { if (it.packageName == updated.packageName) updated else it }
                    } else {
                        selectedApp?.let {
                            db.delete(AppUsageLimit(it.packageName, it.appName, it.currentLimitMinutes ?: 0))
                        }
                        com.focusguard.utils.FocusGuardLogger.log("Limits", "Limite de app removido: ${selectedApp!!.packageName}")
                        val updated = selectedApp!!.copy(currentLimitMinutes = null, isEnabled = false)
                        apps = apps.map { if (it.packageName == updated.packageName) updated else it }
                    }
                    withContext(Dispatchers.Main) { showDialog = false }
                }
            }
        )
    }
}

// ========================================
// ABA DE LIMITES DE SITES
// ========================================

@Composable
fun WebsiteLimitsTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sites by remember { mutableStateOf<List<WebsiteLimitUi>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var showAddDialog by remember { mutableStateOf(false) }
    var selectedSite by remember { mutableStateOf<WebsiteLimitUi?>(null) }
    var showEditDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(context).websiteUsageLimitDao()
            val allLimits = db.getAll()
            val loaded = allLimits.map { WebsiteLimitUi(it.domain, it.dailyLimitMinutes, it.isEnabled) }
            withContext(Dispatchers.Main) {
                sites = loaded
                isLoading = false
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Botão de Adicionar
        Button(
            onClick = { showAddDialog = true },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
            shape = RoundedCornerShape(14.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = null, tint = DarkBg, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Adicionar Site com Limite", color = DarkBg, fontWeight = FontWeight.Bold)
        }

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AccentCyan)
            }
        } else if (sites.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🌐", fontSize = 48.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Nenhum site com limite configurado", color = TextHint, fontSize = 14.sp)
                    Text("Toque no botão acima para adicionar", color = TextHint, fontSize = 12.sp)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                items(sites, key = { it.domain }) { site ->
                    WebsiteLimitItem(
                        site = site,
                        onClick = {
                            selectedSite = site
                            showEditDialog = true
                        },
                        onDelete = {
                            scope.launch(Dispatchers.IO) {
                                val db = AppDatabase.getDatabase(context).websiteUsageLimitDao()
                                db.delete(WebsiteUsageLimit(site.domain, site.dailyLimitMinutes ?: 0, site.isEnabled))
                                com.focusguard.utils.FocusGuardLogger.log("Limits", "Limite de site removido: ${site.domain}")
                                withContext(Dispatchers.Main) {
                                    sites = sites.filter { it.domain != site.domain }
                                }
                            }
                        }
                    )
                }
            }
        }
    }

    // Diálogo para Adicionar novo site
    if (showAddDialog) {
        AddWebsiteLimitDialog(
            onDismiss = { showAddDialog = false },
            onSave = { domain, minutes ->
                scope.launch(Dispatchers.IO) {
                    val db = AppDatabase.getDatabase(context).websiteUsageLimitDao()
                    val cleanDomain = domain.trim().lowercase()
                        .removePrefix("http://").removePrefix("https://")
                        .removePrefix("www.").trimEnd('/')
                    db.insert(WebsiteUsageLimit(cleanDomain, minutes, true))
                    com.focusguard.utils.FocusGuardLogger.log("Limits", "Limite de site salvo: $cleanDomain = $minutes min")
                    withContext(Dispatchers.Main) {
                        sites = sites + WebsiteLimitUi(cleanDomain, minutes, true)
                        showAddDialog = false
                    }
                }
            }
        )
    }

    // Diálogo para Editar site existente
    if (showEditDialog && selectedSite != null) {
        EditWebsiteLimitDialog(
            site = selectedSite!!,
            onDismiss = { showEditDialog = false },
            onSave = { minutes, enabled ->
                scope.launch(Dispatchers.IO) {
                    val db = AppDatabase.getDatabase(context).websiteUsageLimitDao()
                    db.insert(WebsiteUsageLimit(selectedSite!!.domain, minutes, enabled))
                    com.focusguard.utils.FocusGuardLogger.log("Limits", "Limite de site atualizado: ${selectedSite!!.domain} = $minutes min")
                    withContext(Dispatchers.Main) {
                        sites = sites.map { if (it.domain == selectedSite!!.domain) it.copy(dailyLimitMinutes = minutes, isEnabled = enabled) else it }
                        showEditDialog = false
                    }
                }
            }
        )
    }
}

// ========================================
// COMPONENTES DE UI
// ========================================

@Composable
fun UsageLimitItem(app: UsageLimitAppUi, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onClick() },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        border = BorderStroke(1.dp, CardBorder)
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (app.icon != null) {
                val bitmap = remember(app.packageName) { app.icon.toBitmap(80, 80).asImageBitmap() }
                Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)))
            } else {
                Box(modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(DarkCardElevated), contentAlignment = Alignment.Center) {
                    Text("📱", fontSize = 18.sp)
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(app.appName, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                if (app.currentLimitMinutes != null) {
                    val status = if (app.isEnabled) "Ativo" else "Desativado"
                    Text("Limite: ${app.currentLimitMinutes} min/dia ($status)", fontSize = 12.sp, color = if (app.isEnabled) AccentCyan else TextHint)
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
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(AccentCyan.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Text("🌐", fontSize = 20.sp)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(site.domain, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                if (site.dailyLimitMinutes != null) {
                    val status = if (site.isEnabled) "Ativo" else "Desativado"
                    Text("Limite: ${site.dailyLimitMinutes} min/dia ($status)", fontSize = 12.sp, color = if (site.isEnabled) AccentCyan else TextHint)
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Remover", tint = DangerRed, modifier = Modifier.size(20.dp))
            }
        }
    }
}

// ========================================
// DIÁLOGOS
// ========================================

@Composable
fun AppLimitDialog(app: UsageLimitAppUi, onDismiss: () -> Unit, onSave: (Int?, Boolean) -> Unit) {
    var minutesText by remember { mutableStateOf(app.currentLimitMinutes?.toString() ?: "") }
    var isEnabled by remember { mutableStateOf(app.isEnabled || app.currentLimitMinutes == null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Configurar Limite", color = TextPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text("Defina o limite diário de uso para ${app.appName}.", color = TextSecondary, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = minutesText,
                    onValueChange = { if (it.isEmpty() || it.all { c -> c.isDigit() }) minutesText = it },
                    label = { Text("Minutos por dia", color = TextHint) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AccentCyan, focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = isEnabled,
                        onCheckedChange = { isEnabled = it },
                        colors = CheckboxDefaults.colors(checkedColor = AccentCyan, checkmarkColor = DarkBg)
                    )
                    Text("Ativar este limite", color = TextPrimary, fontSize = 14.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(minutesText.toIntOrNull(), isEnabled) }) {
                Text("Salvar", color = AccentCyan)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar", color = TextHint)
            }
        },
        containerColor = DarkSurface
    )
}

@Composable
fun AddWebsiteLimitDialog(onDismiss: () -> Unit, onSave: (String, Int) -> Unit) {
    var domain by remember { mutableStateOf("") }
    var minutesText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Adicionar Limite de Site", color = TextPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text("Digite o domínio do site e o limite diário em minutos.", color = TextSecondary, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = domain,
                    onValueChange = { domain = it },
                    label = { Text("Domínio (ex: youtube.com)", color = TextHint) },
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AccentCyan, focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = minutesText,
                    onValueChange = { if (it.isEmpty() || it.all { c -> c.isDigit() }) minutesText = it },
                    label = { Text("Minutos por dia", color = TextHint) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AccentCyan, focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val minutes = minutesText.toIntOrNull()
                    if (domain.isNotBlank() && minutes != null && minutes > 0) {
                        onSave(domain, minutes)
                    }
                }
            ) {
                Text("Salvar", color = AccentCyan)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar", color = TextHint)
            }
        },
        containerColor = DarkSurface
    )
}

@Composable
fun EditWebsiteLimitDialog(site: WebsiteLimitUi, onDismiss: () -> Unit, onSave: (Int, Boolean) -> Unit) {
    var minutesText by remember { mutableStateOf(site.dailyLimitMinutes?.toString() ?: "") }
    var isEnabled by remember { mutableStateOf(site.isEnabled) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Editar Limite", color = TextPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text("Edite o limite diário para ${site.domain}.", color = TextSecondary, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = minutesText,
                    onValueChange = { if (it.isEmpty() || it.all { c -> c.isDigit() }) minutesText = it },
                    label = { Text("Minutos por dia", color = TextHint) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AccentCyan, focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = isEnabled,
                        onCheckedChange = { isEnabled = it },
                        colors = CheckboxDefaults.colors(checkedColor = AccentCyan, checkmarkColor = DarkBg)
                    )
                    Text("Ativar este limite", color = TextPrimary, fontSize = 14.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val minutes = minutesText.toIntOrNull()
                if (minutes != null && minutes > 0) {
                    onSave(minutes, isEnabled)
                }
            }) {
                Text("Salvar", color = AccentCyan)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar", color = TextHint)
            }
        },
        containerColor = DarkSurface
    )
}
