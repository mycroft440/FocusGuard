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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.pager.PagerState
import com.focusguard.database.AppDatabase
import com.focusguard.manager.BlockingSessionManager
import com.focusguard.ui.compose.screens.AppSelectionScreen
import com.focusguard.ui.compose.screens.SelectableAppUi
import com.focusguard.ui.compose.theme.*
import com.focusguard.utils.findActivity
import com.focusguard.security.AuthManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CreateSessionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sessionType = intent.getStringExtra("SESSION_TYPE") ?: "PASSWORD"
        
        val authManager = AuthManager(this)
        
        setContent {
            FocusGuardTheme {
                CreateSessionWizard(
                    sessionType = sessionType,
                    authManager = authManager,
                    onFinish = { finish() }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CreateSessionWizard(sessionType: String, authManager: AuthManager, onFinish: () -> Unit) {
    val pagerState = rememberPagerState(pageCount = { 3 })
    val scope = rememberCoroutineScope()
    
    // Data collected
    var selectedApps by remember { mutableStateOf<List<SelectableAppUi>>(emptyList()) }
    var selectedSites by remember { mutableStateOf<List<String>>(emptyList()) }

    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
        userScrollEnabled = false // Control navigation via buttons
    ) { page ->
        when (page) {
            0 -> AppSelectionStep(
                onNext = { apps ->
                    selectedApps = apps
                    scope.launch { pagerState.animateScrollToPage(1) }
                },
                onBack = onFinish
            )
            1 -> WebsiteSelectionStep(
                initialSites = selectedSites,
                onNext = { sites ->
                    selectedSites = sites
                    scope.launch { pagerState.animateScrollToPage(2) }
                },
                onBack = {
                    scope.launch { pagerState.animateScrollToPage(0) }
                }
            )
            2 -> FinalConfigStep(
                sessionType = sessionType,
                authManager = authManager,
                sites = selectedSites,
                apps = selectedApps.map { it.packageName },
                onFinish = onFinish,
                onBack = {
                    scope.launch { pagerState.animateScrollToPage(1) }
                }
            )
        }
    }
}

@Composable
fun AppSelectionStep(onNext: (List<SelectableAppUi>) -> Unit, onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val pm = context.packageManager
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
                // 1. Never allow selecting FocusGuard itself
                if (info.packageName == context.packageName || info.packageName == "com.focusguard") continue

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
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text("Prosseguir com o bloqueio", color = DarkBg, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebsiteSelectionStep(
    initialSites: List<String>,
    onNext: (List<String>) -> Unit,
    onBack: () -> Unit
) {
    var sites by remember { mutableStateOf(initialSites) }
    var urlInput by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Bloqueio de Sites", color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar", tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBg)
            )
        },
        containerColor = DarkBg,
        bottomBar = {
            Button(
                onClick = { onNext(sites) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("Prosseguir com a configuração", color = DarkBg, fontWeight = FontWeight.Bold)
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text("Adicione os sites que deseja bloquear durante esta sessão.", color = TextSecondary, fontSize = 14.sp)
            Spacer(modifier = Modifier.height(24.dp))
            
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    placeholder = { Text("Ex: site.com", color = TextHint) },
                    modifier = Modifier.weight(1f),
                    trailingIcon = {
                        TextButton(
                            onClick = {
                                if (urlInput.isNotBlank() && !sites.contains(urlInput.trim())) {
                                    sites = sites + urlInput.trim()
                                    urlInput = ""
                                }
                            }
                        ) {
                            Text("ADD +", color = AccentCyan, fontWeight = FontWeight.Bold)
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = AccentCyan
                    )
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text("Sugestões:", color = TextSecondary, fontSize = 12.sp)
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                listOf("facebook.com", "instagram.com").forEach { suggestion ->
                    FilterChip(
                        selected = sites.contains(suggestion),
                        onClick = {
                            sites = if (sites.contains(suggestion)) sites - suggestion else sites + suggestion
                        },
                        label = { Text(suggestion) },
                        modifier = Modifier.padding(end = 8.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AccentCyan.copy(alpha = 0.2f),
                            selectedLabelColor = AccentCyan
                        )
                    )
                }
            }

            if (sites.isNotEmpty()) {
                Spacer(modifier = Modifier.height(24.dp))
                Text("Sites Selecionados:", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                Spacer(modifier = Modifier.height(8.dp))
                Column(modifier = Modifier.fillMaxWidth().background(DarkCard, RoundedCornerShape(12.dp)).padding(8.dp)) {
                    sites.forEach { site ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
                            Text(site, color = TextPrimary, modifier = Modifier.weight(1f), fontSize = 14.sp)
                            IconButton(onClick = { sites = sites - site }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Default.Delete, "Remover", tint = DangerRed, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FinalConfigStep(
    sessionType: String,
    authManager: AuthManager,
    sites: List<String>,
    apps: List<String>,
    onFinish: () -> Unit,
    onBack: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val activityContext = com.focusguard.utils.findActivity(context) ?: context
    
    var isFixed24h by remember { mutableStateOf(true) }
    var useSpecificTime by remember { mutableStateOf(false) }
    
    var startHour by remember { mutableStateOf("08") }
    var startMin by remember { mutableStateOf("00") }
    var endHour by remember { mutableStateOf("20") }
    var endMin by remember { mutableStateOf("00") }
    
    var selectedDays by remember { mutableStateOf(setOf("2", "3", "4", "5", "6")) } 
    var timeDays by remember { mutableStateOf("0") }
    var timeHours by remember { mutableStateOf("2") }

    var showPasswordCreationDialog by remember { mutableStateOf(false) }
    var hasPassword by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        hasPassword = authManager.hasPasswordSet()
    }

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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text("Opções de Agendamento", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            Spacer(modifier = Modifier.height(12.dp))
            
            Column(modifier = Modifier.fillMaxWidth().background(DarkCard, RoundedCornerShape(16.dp)).padding(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { isFixed24h = true }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = isFixed24h, onClick = { isFixed24h = true }, colors = RadioButtonDefaults.colors(selectedColor = AccentCyan))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Bloqueio padrão (24h todos os dias)", color = TextPrimary)
                }
                
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { isFixed24h = false }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = !isFixed24h, onClick = { isFixed24h = false }, colors = RadioButtonDefaults.colors(selectedColor = AccentCyan))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Bloquear somente dias específicos", color = TextPrimary)
                }
            }

            if (!isFixed24h) {
                Spacer(modifier = Modifier.height(24.dp))
                Text("Selecione os dias em que o bloqueio irá funcionar:", color = TextPrimary, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(12.dp))
                
                val customDayLabels = listOf(
                    "Seg" to "2", "Ter" to "3", "Qua" to "4", "Qui" to "5", "Sex" to "6", "Sáb" to "7", "Dom" to "1"
                )
                
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    maxItemsInEachRow = 4
                ) {
                    customDayLabels.forEach { (label, value) ->
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

                Text("Especificar horário de início e término?", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(12.dp))
                
                Row(modifier = Modifier.fillMaxWidth().background(DarkCard, RoundedCornerShape(16.dp)).padding(8.dp)) {
                    Row(
                        modifier = Modifier.weight(1f).clickable { useSpecificTime = false }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = !useSpecificTime, onClick = { useSpecificTime = false }, colors = RadioButtonDefaults.colors(selectedColor = AccentCyan))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Não", color = TextPrimary)
                    }
                    
                    Row(
                        modifier = Modifier.weight(1f).clickable { useSpecificTime = true }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = useSpecificTime, onClick = { useSpecificTime = true }, colors = RadioButtonDefaults.colors(selectedColor = AccentCyan))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Sim", color = TextPrimary)
                    }
                }

                if (useSpecificTime) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Horários de bloqueio:", color = TextSecondary, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(8.dp))
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
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = DarkCard),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        "O bloqueio funcionará 24h por dia, todos os dias.", 
                        color = TextSecondary, fontSize = 14.sp,
                        modifier = Modifier.padding(16.dp)
                    )
                }
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
                    if (sessionType == "PASSWORD" && !hasPassword) {
                        showPasswordCreationDialog = true
                        return@Button
                    }
                    
                    val daysStr = selectedDays.joinToString(",")
                    if (sessionType == "PASSWORD") {
                        com.focusguard.manager.BlockingSessionManager.getInstance(context).startPasswordSession(
                            isFixed24h = isFixed24h,
                            startHour = if (useSpecificTime) startHour.toIntOrNull() ?: 0 else 0,
                            endHour = if (useSpecificTime) endHour.toIntOrNull() ?: 24 else 24,
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
                            endHour = if (useSpecificTime) endHour.toIntOrNull() ?: 24 else 24,
                            startMinute = if (useSpecificTime) startMin.toIntOrNull() ?: 0 else 0,
                            endMinute = if (useSpecificTime) endMin.toIntOrNull() ?: 0 else 0,
                            daysOfWeek = if (isFixed24h) "" else daysStr,
                            apps = apps,
                            sites = sites
                        )
                    }
                    Toast.makeText(context, "Bloqueio configurado com sucesso!", Toast.LENGTH_LONG).show()
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

    if (showPasswordCreationDialog) {
        PasswordCreationDialog(
            onDismiss = { showPasswordCreationDialog = false },
            onPasswordCreated = { password ->
                authManager.addPassword(password)
                hasPassword = true
                showPasswordCreationDialog = false
                Toast.makeText(context, "Senha criada com sucesso! Agora você pode ativar o bloqueio.", Toast.LENGTH_LONG).show()
            }
        )
    }
}

@Composable
fun PasswordCreationDialog(onDismiss: () -> Unit, onPasswordCreated: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Criar Senha de Segurança", color = TextPrimary) },
        text = {
            Column {
                Text("Você precisa criar uma senha para usar o bloqueio por senha. Esta senha será necessária para desativar o bloqueio.", color = TextSecondary, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; error = null },
                    label = { Text("Nova Senha") },
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, focusedBorderColor = AccentCyan)
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = confirmPassword,
                    onValueChange = { confirmPassword = it; error = null },
                    label = { Text("Confirmar Senha") },
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, focusedBorderColor = AccentCyan)
                )
                if (error != null) {
                    Text(error!!, color = DangerRed, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (password.isEmpty()) {
                        error = "A senha não pode estar vazia"
                    } else if (password != confirmPassword) {
                        error = "As senhas não coincidem"
                    } else {
                        onPasswordCreated(password)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = AccentCyan)
            ) {
                Text("Salvar", color = DarkBg)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar", color = TextSecondary)
            }
        },
        containerColor = DarkSurface,
        shape = RoundedCornerShape(24.dp)
    )
}

