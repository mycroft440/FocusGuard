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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageLimitsScreen(onBack: () -> Unit) {
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
                val icon = try { info.loadIcon(pm) } catch (e: Exception) { null }
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
    }

    if (showDialog && selectedApp != null) {
        var minutesText by remember { mutableStateOf(selectedApp?.currentLimitMinutes?.toString() ?: "") }
        var isEnabled by remember { mutableStateOf(selectedApp?.isEnabled ?: true) }

        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Configurar Limite", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("Defina o limite diário de uso para ${selectedApp!!.appName}.", color = TextSecondary, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = minutesText,
                        onValueChange = { if (it.isEmpty() || it.all { char -> char.isDigit() }) minutesText = it },
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
                        scope.launch(Dispatchers.IO) {
                            val db = AppDatabase.getDatabase(context).appUsageLimitDao()
                            db.insert(AppUsageLimit(selectedApp!!.packageName, selectedApp!!.appName, minutes, isEnabled))
                            com.focusguard.utils.FocusGuardLogger.log("Limits", "Limite salvo para ${selectedApp!!.packageName}: $minutes min")
                            
                            // Atualiza a lista
                            val updatedLimit = selectedApp!!.copy(currentLimitMinutes = minutes, isEnabled = isEnabled)
                            apps = apps.map { if (it.packageName == updatedLimit.packageName) updatedLimit else it }
                            
                            withContext(Dispatchers.Main) { showDialog = false }
                        }
                    } else {
                        // Se apagar ou zero, remove
                        scope.launch(Dispatchers.IO) {
                            val db = AppDatabase.getDatabase(context).appUsageLimitDao()
                            selectedApp?.let {
                                db.delete(AppUsageLimit(it.packageName, it.appName, it.currentLimitMinutes ?: 0))
                            }
                            com.focusguard.utils.FocusGuardLogger.log("Limits", "Limite removido para ${selectedApp!!.packageName}")
                            val updatedLimit = selectedApp!!.copy(currentLimitMinutes = null, isEnabled = false)
                            apps = apps.map { if (it.packageName == updatedLimit.packageName) updatedLimit else it }
                            withContext(Dispatchers.Main) { showDialog = false }
                        }
                    }
                }) {
                    Text("Salvar", color = AccentCyan)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text("Cancelar", color = TextHint)
                }
            },
            containerColor = DarkSurface
        )
    }
}

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
