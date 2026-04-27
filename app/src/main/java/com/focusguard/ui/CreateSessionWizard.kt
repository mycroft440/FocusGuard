package com.focusguard.ui

import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.manager.BlockingSessionManager
import com.focusguard.ui.compose.screens.AppSelectionScreen
import com.focusguard.ui.compose.screens.SelectableAppUi
import com.focusguard.ui.compose.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset

@Composable
fun CreateSessionWizard(sessionType: String, onFinish: () -> Unit) {
    var step by remember { mutableStateOf(1) }
    
    // Data collected
    var selectedApps by remember { mutableStateOf<List<SelectableAppUi>>(emptyList()) }
    var selectedSites by remember { mutableStateOf<List<String>>(emptyList()) }

    when (step) {
        1 -> SelectionStep(
            initialApps = selectedApps,
            initialSites = selectedSites,
            onNext = { apps, sites ->
                selectedApps = apps
                selectedSites = sites
                step = 2
            },
            onBack = onFinish
        )
        2 -> ConfigSessionStep(
            sessionType = sessionType,
            apps = selectedApps.map { it.packageName },
            sites = selectedSites,
            onFinish = onFinish,
            onBack = { step = 1 }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionStep(
    initialApps: List<SelectableAppUi>,
    initialSites: List<String>,
    onNext: (List<SelectableAppUi>, List<String>) -> Unit,
    onBack: () -> Unit
) {
    var selectedTabIndex by remember { mutableStateOf(0) }
    val tabs = listOf("Aplicativos", "Sites")

    val context = LocalContext.current
    val pm = context.packageManager
    
    // Apps State
    var apps by remember { mutableStateOf<List<SelectableAppUi>>(initialApps) }
    var isLoadingApps by remember { mutableStateOf(true) }
    
    // Sites State
    var sites by remember { mutableStateOf(initialSites) }
    var urlInput by remember { mutableStateOf("") }

    // Load Apps
    LaunchedEffect(Unit) {
        if (apps.isEmpty()) {
            withContext(Dispatchers.IO) {
                val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                val launchables = pm.queryIntentActivities(intent, 0).map { it.activityInfo.packageName }.toSet()

                val appList = mutableListOf<SelectableAppUi>()
                val installedPackageNames = mutableSetOf<String>()

                for (info in installedApps) {
                    if (launchables.contains(info.packageName)) {
                        installedPackageNames.add(info.packageName)
                        val appName = info.loadLabel(pm).toString()
                        val icon = try { info.loadIcon(pm) } catch (_: Exception) { null }
                        appList.add(SelectableAppUi(packageName = info.packageName, appName = appName, icon = icon, isSelected = false, isInstalled = true))
                    }
                }
                appList.sortBy { it.appName.lowercase() }

                val uninstalledPredefined = com.focusguard.data.PredefinedApps.PREVENTIVE_APPS.filter { 
                    !installedPackageNames.contains(it.packageName) 
                }.map {
                    SelectableAppUi(
                        packageName = it.packageName,
                        appName = it.appName,
                        icon = null,
                        isSelected = false,
                        isInstalled = false,
                        category = it.category
                    )
                }

                val finalAppList = uninstalledPredefined + appList
                withContext(Dispatchers.Main) {
                    apps = finalAppList
                    isLoadingApps = false
                }
            }
        } else {
            isLoadingApps = false
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(
            selectedTabIndex = selectedTabIndex,
            containerColor = DarkBg,
            contentColor = AccentCyan,
            indicator = { tabPositions ->
                TabRowDefaults.Indicator(
                    modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTabIndex]),
                    color = AccentCyan
                )
            }
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTabIndex == index,
                    onClick = { selectedTabIndex = index },
                    text = { Text(title, color = if (selectedTabIndex == index) AccentCyan else TextSecondary) }
                )
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            if (selectedTabIndex == 0) {
                // APPS TAB
                AppSelectionScreen(
                    apps = apps,
                    isLoading = isLoadingApps,
                    onToggleApp = { pkg ->
                        apps = apps.map { if (it.packageName == pkg) it.copy(isSelected = !it.isSelected) else it }
                    },
                    onBack = onBack
                )
            } else {
                // SITES TAB
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("Adicionar Sites", color = TextPrimary) },
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
                    Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                        Text("Quais sites você deseja bloquear nesta sessão?", color = TextSecondary)
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = urlInput,
                                onValueChange = { urlInput = it },
                                placeholder = { Text("Ex: facebook.com", color = TextHint) },
                                modifier = Modifier.weight(1f),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = TextPrimary,
                                    unfocusedTextColor = TextPrimary,
                                    focusedBorderColor = AccentCyan
                                )
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            IconButton(
                                onClick = {
                                    if (urlInput.isNotBlank() && !sites.contains(urlInput.trim())) {
                                        sites = sites + urlInput.trim()
                                        urlInput = ""
                                    }
                                },
                                modifier = Modifier.background(AccentCyan, RoundedCornerShape(12.dp))
                            ) {
                                Icon(Icons.Default.Add, contentDescription = "Adicionar", tint = DarkBg)
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(24.dp))
                        
                        androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.weight(1f)) {
                            items(sites) { site ->
                                Card(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    colors = CardDefaults.cardColors(containerColor = DarkCard),
                                    border = BorderStroke(1.dp, CardBorder)
                                ) {
                                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(site, color = TextPrimary, modifier = Modifier.weight(1f))
                                        IconButton(onClick = { sites = sites - site }) {
                                            Icon(Icons.Default.Delete, "Remover", tint = DangerRed)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        
        Button(
            onClick = { onNext(apps.filter { it.isSelected }, sites) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .height(50.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AccentCyan)
        ) {
            val totalSelected = apps.count { it.isSelected } + sites.size
            Text("Confirmar ($totalSelected itens) e Prosseguir", color = DarkBg, fontWeight = FontWeight.Bold)
        }
    }
}

fun android.content.Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigSessionStep(sessionType: String, apps: List<String>, sites: List<String>, onFinish: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val activityContext = context.findActivity() ?: context
    val sessionManager = remember { BlockingSessionManager.getInstance(context) }
    
    var isFixed24h by remember { mutableStateOf(true) }
    var startHour by remember { mutableStateOf("08") }
    var startMin by remember { mutableStateOf("00") }
    var endHour by remember { mutableStateOf("18") }
    var endMin by remember { mutableStateOf("00") }
    var daysOfWeek by remember { mutableStateOf("2,3,4,5,6") } // Mon-Fri default
    
    var timeDays by remember { mutableStateOf("0") }
    var timeHours by remember { mutableStateOf("2") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Configurar Bloqueio", color = TextPrimary) },
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
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            if (sessionType == "PASSWORD") {
                Text("Configuração de Bloqueio por Senha", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                Spacer(modifier = Modifier.height(16.dp))
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = isFixed24h, onClick = { isFixed24h = true }, colors = RadioButtonDefaults.colors(selectedColor = AccentCyan))
                    Text("Bloqueio fixo todos os dias (24h)", color = TextPrimary)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = !isFixed24h, onClick = { isFixed24h = false }, colors = RadioButtonDefaults.colors(selectedColor = AccentCyan))
                    Text("Bloqueio por período", color = TextPrimary)
                }
                
                if (!isFixed24h) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Dias da semana (1=Dom, 7=Sáb):", color = TextSecondary)
                    OutlinedTextField(value = daysOfWeek, onValueChange = { daysOfWeek = it }, colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary))
                    
                    Row(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = {
                                android.app.TimePickerDialog(activityContext, { _, h, m ->
                                    startHour = h.toString().padStart(2, '0')
                                    startMin = m.toString().padStart(2, '0')
                                }, startHour.toIntOrNull() ?: 8, startMin.toIntOrNull() ?: 0, true).show()
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Início: $startHour:$startMin", color = TextPrimary)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = {
                                android.app.TimePickerDialog(activityContext, { _, h, m ->
                                    endHour = h.toString().padStart(2, '0')
                                    endMin = m.toString().padStart(2, '0')
                                }, endHour.toIntOrNull() ?: 18, endMin.toIntOrNull() ?: 0, true).show()
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Fim: $endHour:$endMin", color = TextPrimary)
                        }
                    }
                }
            } else {
                Text("Configuração de Bloqueio por Tempo", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                Spacer(modifier = Modifier.height(16.dp))
                
                Row(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(value = timeDays, onValueChange = { timeDays = it }, label = { Text("Dias") }, modifier = Modifier.weight(1f), colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary))
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedTextField(value = timeHours, onValueChange = { timeHours = it }, label = { Text("Horas") }, modifier = Modifier.weight(1f), colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary))
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = isFixed24h, onClick = { isFixed24h = true }, colors = RadioButtonDefaults.colors(selectedColor = AccentCyan))
                    Text("Ativo 24h durante o tempo limite", color = TextPrimary)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = !isFixed24h, onClick = { isFixed24h = false }, colors = RadioButtonDefaults.colors(selectedColor = AccentCyan))
                    Text("Ativo por horário durante o limite", color = TextPrimary)
                }
                if (!isFixed24h) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Dias da semana (1=Dom, 7=Sáb):", color = TextSecondary)
                    OutlinedTextField(value = daysOfWeek, onValueChange = { daysOfWeek = it }, colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary))
                    
                    Row(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = {
                                android.app.TimePickerDialog(activityContext, { _, h, m ->
                                    startHour = h.toString().padStart(2, '0')
                                    startMin = m.toString().padStart(2, '0')
                                }, startHour.toIntOrNull() ?: 8, startMin.toIntOrNull() ?: 0, true).show()
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Início: $startHour:$startMin", color = TextPrimary)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = {
                                android.app.TimePickerDialog(activityContext, { _, h, m ->
                                    endHour = h.toString().padStart(2, '0')
                                    endMin = m.toString().padStart(2, '0')
                                }, endHour.toIntOrNull() ?: 18, endMin.toIntOrNull() ?: 0, true).show()
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Fim: $endHour:$endMin", color = TextPrimary)
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                Card(colors = CardDefaults.cardColors(containerColor = DangerRed.copy(alpha = 0.2f))) {
                    Text(
                        text = "AVISO: Após iniciar, NÃO será possível encerrar ou cancelar o bloqueio por tempo! Essa ação não usa senha.",
                        color = DangerRed,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
            
            Spacer(modifier = Modifier.weight(1f))
            
            val canStart = remember(apps, sites, timeDays, timeHours, sessionType) {
                val hasItems = apps.isNotEmpty() || sites.isNotEmpty()
                if (sessionType == "TIME") {
                    val days = timeDays.toIntOrNull() ?: 0
                    val hours = timeHours.toIntOrNull() ?: 0
                    hasItems && (days > 0 || hours > 0)
                } else {
                    hasItems
                }
            }

            Button(
                onClick = {
                    if (canStart) {
                        com.focusguard.utils.FocusGuardLogger.log("UI", "Usuário clicou para criar sessão do tipo: $sessionType")
                        if (sessionType == "PASSWORD") {
                        sessionManager.startPasswordSession(
                            isFixed24h = isFixed24h,
                            startHour = startHour.toIntOrNull() ?: 0,
                            endHour = endHour.toIntOrNull() ?: 0,
                            startMinute = startMin.toIntOrNull() ?: 0,
                            endMinute = endMin.toIntOrNull() ?: 0,
                            daysOfWeek = daysOfWeek,
                            apps = apps,
                            sites = sites
                        )
                    } else {
                        sessionManager.startTimeSession(
                            days = timeDays.toIntOrNull() ?: 0,
                            hours = timeHours.toIntOrNull() ?: 0,
                            isFixed24h = isFixed24h,
                            startHour = startHour.toIntOrNull() ?: 0,
                            endHour = endHour.toIntOrNull() ?: 0,
                            startMinute = startMin.toIntOrNull() ?: 0,
                            endMinute = endMin.toIntOrNull() ?: 0,
                            daysOfWeek = daysOfWeek,
                            apps = apps,
                            sites = sites
                        )
                    }
                        onFinish()
                    }
                },
                enabled = canStart,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentCyan)
            ) {
                Text("Iniciar Sessão", color = DarkBg, fontWeight = FontWeight.Bold)
            }
        }
    }
}
