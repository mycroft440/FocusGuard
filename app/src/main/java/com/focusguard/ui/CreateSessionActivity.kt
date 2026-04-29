package com.focusguard.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import com.focusguard.database.AppDatabase
import com.focusguard.manager.BlockingSessionManager
import com.focusguard.ui.compose.screens.AppSelectionScreen
import com.focusguard.ui.compose.screens.SelectableAppUi
import com.focusguard.ui.compose.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CreateSessionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sessionType = intent.getStringExtra("SESSION_TYPE") ?: "PASSWORD"
        
        setContent {
            FocusGuardTheme {
                CreateSessionWizard(
                    sessionType = sessionType,
                    onFinish = { finish() }
                )
            }
        }
    }
}

@Composable
fun CreateSessionWizard(sessionType: String, onFinish: () -> Unit) {
    var step by remember { mutableStateOf(1) }
    
    // Data collected
    var selectedApps by remember { mutableStateOf<List<SelectableAppUi>>(emptyList()) }
    var selectedSites by remember { mutableStateOf<List<String>>(emptyList()) }

    when (step) {
        1 -> AppSelectionStep(
            onNext = { apps ->
                selectedApps = apps
                step = 2
            },
            onBack = onFinish
        )
        2 -> SiteSelectionStep(
            initialSites = selectedSites,
            onNext = { sites ->
                selectedSites = sites
                step = 3
            },
            onBack = { step = 1 }
        )
        3 -> ConfigSessionStep(
            sessionType = sessionType,
            apps = selectedApps.map { it.packageName },
            sites = selectedSites,
            onFinish = onFinish,
            onBack = { step = 2 }
        )
    }
}

@Composable
fun AppSelectionStep(onNext: (List<SelectableAppUi>) -> Unit, onBack: () -> Unit) {
    val pm = androidx.compose.ui.platform.LocalContext.current.packageManager
    var apps by remember { mutableStateOf<List<SelectableAppUi>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
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

            // Add predefined apps that are NOT installed
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
                isLoading = false
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
            AppSelectionScreen(
                apps = apps,
                isLoading = isLoading,
                onToggleApp = { pkg ->
                    apps = apps.map { if (it.packageName == pkg) it.copy(isSelected = !it.isSelected) else it }
                },
                onBack = onBack
            )
        }
        
        Button(
            onClick = { onNext(apps.filter { it.isSelected }) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .height(50.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AccentCyan)
        ) {
            Text("Confirmar Apps e Prosseguir", color = DarkBg, fontWeight = FontWeight.Bold)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SiteSelectionStep(initialSites: List<String>, onNext: (List<String>) -> Unit, onBack: () -> Unit) {
    var sites by remember { mutableStateOf(initialSites) }
    var urlInput by remember { mutableStateOf("") }

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
            
            LazyColumn(modifier = Modifier.weight(1f)) {
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
            
            Button(
                onClick = { onNext(sites) },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentCyan)
            ) {
                Text("Confirmar Sites e Prosseguir", color = DarkBg, fontWeight = FontWeight.Bold)
            }
        }
    }
}

fun android.content.Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ConfigSessionStep(sessionType: String, apps: List<String>, sites: List<String>, onFinish: () -> Unit, onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val activityContext = context.findActivity() ?: context
    val sessionManager = remember { com.focusguard.manager.BlockingSessionManager.getInstance(context) }
    
    var isFixed24h by remember { mutableStateOf(true) }
    var useSpecificTime by remember { mutableStateOf(false) }
    
    var startHour by remember { mutableStateOf("08") }
    var startMin by remember { mutableStateOf("00") }
    var endHour by remember { mutableStateOf("20") }
    var endMin by remember { mutableStateOf("00") }
    
    // Days selection: 1=Dom, 2=Seg, 3=Ter, 4=Qua, 5=Qui, 6=Sex, 7=Sáb
    var selectedDays by remember { mutableStateOf(setOf("2", "3", "4", "5", "6")) } 
    val dayLabels = listOf("Dom" to "1", "Seg" to "2", "Ter" to "3", "Qua" to "4", "Qui" to "5", "Sex" to "6", "Sáb" to "7")
    
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
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState())) {
            
            Text(
                if (sessionType == "PASSWORD") "Bloqueio por Senha" else "Bloqueio por Tempo",
                fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextPrimary
            )
            Spacer(modifier = Modifier.height(20.dp))

            // Selection: Pattern vs Scheduled
            Row(modifier = Modifier.fillMaxWidth()) {
                FilterChip(
                    selected = isFixed24h,
                    onClick = { isFixed24h = true },
                    label = { Text("Bloqueio padrão") },
                    modifier = Modifier.weight(1f).padding(end = 4.dp),
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = AccentCyan, selectedLabelColor = DarkBg)
                )
                FilterChip(
                    selected = !isFixed24h,
                    onClick = { isFixed24h = false },
                    label = { Text("Programado") },
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = AccentCyan, selectedLabelColor = DarkBg)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            if (!isFixed24h) {
                Text("Selecione os dias da semana:", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(12.dp))
                
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    maxItemsInEachRow = 4
                ) {
                    dayLabels.forEach { (label, value) ->
                        FilterChip(
                            selected = selectedDays.contains(value),
                            onClick = {
                                selectedDays = if (selectedDays.contains(value)) selectedDays - value else selectedDays + value
                            },
                            label = { Text(label, fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = AccentCyan,
                                selectedLabelColor = DarkBg,
                                containerColor = DarkCard
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Especificar horário de início e término?", color = TextPrimary, modifier = Modifier.weight(1f))
                    Switch(
                        checked = useSpecificTime,
                        onCheckedChange = { useSpecificTime = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = AccentCyan)
                    )
                }

                if (useSpecificTime) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = {
                                android.app.TimePickerDialog(activityContext, { _, h, m ->
                                    startHour = h.toString().padStart(2, '0')
                                    startMin = m.toString().padStart(2, '0')
                                }, startHour.toIntOrNull() ?: 8, startMin.toIntOrNull() ?: 0, true).show()
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, CardBorder)
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Início", fontSize = 11.sp, color = TextHint)
                                Text("$startHour:$startMin", fontSize = 18.sp, color = TextPrimary, fontWeight = FontWeight.Bold)
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        OutlinedButton(
                            onClick = {
                                android.app.TimePickerDialog(activityContext, { _, h, m ->
                                    endHour = h.toString().padStart(2, '0')
                                    endMin = m.toString().padStart(2, '0')
                                }, endHour.toIntOrNull() ?: 20, endMin.toIntOrNull() ?: 0, true).show()
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, CardBorder)
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Término", fontSize = 11.sp, color = TextHint)
                                Text("$endHour:$endMin", fontSize = 18.sp, color = TextPrimary, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            } else {
                Text("O bloqueio funcionará 24h por dia, todos os dias.", color = TextSecondary, fontSize = 14.sp)
            }

            if (sessionType == "TIME") {
                Spacer(modifier = Modifier.height(32.dp))
                Text("Duração do Limite", color = TextPrimary, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = timeDays, onValueChange = { timeDays = it }, 
                        label = { Text("Dias") }, modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, focusedBorderColor = AccentCyan)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    OutlinedTextField(
                        value = timeHours, onValueChange = { timeHours = it }, 
                        label = { Text("Horas") }, modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, focusedBorderColor = AccentCyan)
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = DangerRed.copy(alpha = 0.1f)),
                    border = BorderStroke(1.dp, DangerRed.copy(alpha = 0.3f))
                ) {
                    Text(
                        text = "AVISO: Bloqueios por tempo não podem ser cancelados nem com senha após iniciados.",
                        color = DangerRed, fontSize = 12.sp,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(40.dp))
            
            Button(
                onClick = {
                    val daysStr = selectedDays.joinToString(",")
                    if (sessionType == "PASSWORD") {
                        com.focusguard.manager.BlockingSessionManager.getInstance(context).startPasswordSession(
                            isFixed24h = isFixed24h,
                            startHour = if (useSpecificTime) startHour.toIntOrNull() ?: 0 else 0,
                            endHour = if (useSpecificTime) endHour.toIntOrNull() ?: 24 else 0,
                            startMinute = if (useSpecificTime) startMin.toIntOrNull() ?: 0 else 0,
                            endMinute = if (useSpecificTime) endMin.toIntOrNull() ?: 0 else 0,
                            daysOfWeek = if (isFixed24h) "" else daysStr,
                            apps = apps,
                            sites = sites
                        )
                    } else {
                        com.focusguard.manager.BlockingSessionManager.getInstance(context).startTimeSession(
                            days = timeDays.toIntOrNull() ?: 0,
                            hours = timeHours.toIntOrNull() ?: 0,
                            isFixed24h = isFixed24h,
                            startHour = if (useSpecificTime) startHour.toIntOrNull() ?: 0 else 0,
                            endHour = if (useSpecificTime) endHour.toIntOrNull() ?: 24 else 0,
                            startMinute = if (useSpecificTime) startMin.toIntOrNull() ?: 0 else 0,
                            endMinute = if (useSpecificTime) endMin.toIntOrNull() ?: 0 else 0,
                            daysOfWeek = if (isFixed24h) "" else daysStr,
                            apps = apps,
                            sites = sites
                        )
                    }
                    Toast.makeText(context, "bloqueio configurado com sucesso!", Toast.LENGTH_LONG).show()
                    onFinish()
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("Ativar Bloqueio", color = DarkBg, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
