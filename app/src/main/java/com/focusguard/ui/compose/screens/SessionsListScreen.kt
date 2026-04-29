package com.focusguard.ui.compose.screens

import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.database.BlockSession
import com.focusguard.manager.BlockingSessionManager
import com.focusguard.ui.CreateSessionActivity
import com.focusguard.ui.compose.theme.*
import com.focusguard.security.AuthManager
import com.focusguard.ui.compose.screens.SelectableAppUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.content.pm.PackageManager
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import java.util.*
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsListScreen(
    sessionType: String, // "PASSWORD" or "TIME"
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val authManager = remember { AuthManager(context) }
    val sessionManager = remember { BlockingSessionManager.getInstance(context) }
    val sessions by sessionManager.activeSessionsFlow.collectAsState(initial = emptyList())
    val filteredSessions = sessions.filter { it.sessionType == sessionType }
    
    var showPasswordPrompt by remember { mutableStateOf<Int?>(null) }
    var showDetailsSheet by remember { mutableStateOf<BlockSession?>(null) }
    var showAppPickerForSession by remember { mutableStateOf<BlockSession?>(null) }
    val scope = rememberCoroutineScope()
    
    val title = if (sessionType == "PASSWORD") "Bloqueios por Senha" else "Bloqueios por Tempo"
    val icon = if (sessionType == "PASSWORD") Icons.Default.VpnKey else Icons.Default.Timer

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, color = TextPrimary, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Voltar", tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBg)
            )
        },
        containerColor = DarkBg,
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    val intent = Intent(context, CreateSessionActivity::class.java).apply {
                        putExtra("SESSION_TYPE", sessionType)
                    }
                    context.startActivity(intent)
                },
                containerColor = AccentCyan,
                contentColor = DarkBg,
                shape = CircleShape
            ) {
                Icon(Icons.Default.Add, contentDescription = "Adicionar Novo")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            if (filteredSessions.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = TextHint.copy(alpha = 0.3f)
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Nenhum bloqueio configurado",
                            color = TextHint,
                            fontSize = 16.sp,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Toque no + para criar seu primeiro bloqueio.",
                            color = TextHint.copy(alpha = 0.6f),
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                Text(
                    "Seus Bloqueios Ativos",
                    color = TextSecondary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
                
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(filteredSessions, key = { it.id }) { session ->
                        SessionListItem(
                            session = session, 
                            sessionManager = sessionManager,
                            onRemoveBlock = { showPasswordPrompt = session.id },
                            onAddContent = { showAppPickerForSession = session },
                            onClick = { showDetailsSheet = session }
                        )
                    }
                    item { Spacer(Modifier.height(80.dp)) } // Space for FAB
                }
            }
        }
    }

    if (showPasswordPrompt != null) {
        PasswordPromptDialog(
            onDismiss = { showPasswordPrompt = null },
            onConfirm = { password ->
                scope.launch {
                    if (authManager.verifyPassword(password)) {
                        sessionManager.endSession(showPasswordPrompt!!)
                        showPasswordPrompt = null
                    } else {
                        Toast.makeText(context, "Senha incorreta", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }

    if (showDetailsSheet != null) {
        SessionDetailsSheet(
            session = showDetailsSheet!!,
            onDismiss = { showDetailsSheet = null },
            onAddClick = { 
                val s = showDetailsSheet!!
                showDetailsSheet = null
                showAppPickerForSession = s
            }
        )
    }

    if (showAppPickerForSession != null) {
        ContentPickerSheet(
            session = showAppPickerForSession!!,
            onDismiss = { showAppPickerForSession = null }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionDetailsSheet(
    session: BlockSession, 
    onDismiss: () -> Unit,
    onAddClick: () -> Unit
) {
    val context = LocalContext.current
    val pm = remember { context.packageManager }
    val database = remember { com.focusguard.database.AppDatabase.getDatabase(context) }
    val scope = rememberCoroutineScope()
    
    var blockedApps by remember { mutableStateOf<List<String>>(emptyList()) }
    var blockedSites by remember { mutableStateOf<List<String>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    fun refresh() {
        scope.launch {
            isLoading = true
            val apps = database.sessionAppCrossRefDao().getAppsForSessions(listOf(session.id))
            val sites = database.sessionWebsiteCrossRefDao().getWebsitesForSessions(listOf(session.id))
            blockedApps = apps
            blockedSites = sites
            isLoading = false
        }
    }

    LaunchedEffect(session.id) {
        refresh()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = DarkSurface,
        scrimColor = Color.Black.copy(alpha = 0.6f),
        dragHandle = { BottomSheetDefaults.DragHandle(color = TextHint.copy(alpha = 0.3f)) }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header with Clear All
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    if (session.sessionType == "PASSWORD") "Apps e Sites Bloqueados" else "Blindagem Ativa",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
                
                IconButton(onClick = {
                    scope.launch {
                        database.sessionAppCrossRefDao().deleteForSession(session.id)
                        database.sessionWebsiteCrossRefDao().deleteForSession(session.id)
                        // If no content, maybe end session? Or just leave it empty.
                        // User said "remover todos os apps do bloqueio"
                        BlockingSessionManager.getInstance(context).checkAndEnforce()
                        refresh()
                    }
                }) {
                    Icon(Icons.Default.DeleteSweep, "Limpar Tudo", tint = DangerRed)
                }
            }

            Spacer(Modifier.height(16.dp))

            if (isLoading) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AccentCyan)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (blockedApps.isEmpty() && blockedSites.isEmpty()) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                                Text("Nenhum conteúdo bloqueado.", color = TextHint, textAlign = TextAlign.Center)
                            }
                        }
                    }

                    if (blockedApps.isNotEmpty()) {
                        item { Text("Aplicativos", color = AccentCyan, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
                        items(blockedApps) { pkg ->
                            val appName = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg }
                            Row(
                                modifier = Modifier.fillMaxWidth().background(DarkCard, RoundedCornerShape(12.dp)).padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Apps, null, tint = TextHint, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(12.dp))
                                Text(appName, color = TextPrimary, fontSize = 15.sp, modifier = Modifier.weight(1f))
                                IconButton(onClick = {
                                    scope.launch {
                                        database.sessionAppCrossRefDao().deleteSpecificApp(session.id, pkg)
                                        BlockingSessionManager.getInstance(context).checkAndEnforce()
                                        refresh()
                                    }
                                }) {
                                    Icon(Icons.Default.RemoveCircleOutline, "Remover", tint = DangerRed.copy(alpha = 0.6f), modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    }

                    if (blockedSites.isNotEmpty()) {
                        item { Spacer(Modifier.height(8.dp)); Text("Websites", color = AccentCyan, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
                        items(blockedSites) { site ->
                            Row(
                                modifier = Modifier.fillMaxWidth().background(DarkCard, RoundedCornerShape(12.dp)).padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Language, null, tint = TextHint, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(12.dp))
                                Text(site, color = TextPrimary, fontSize = 15.sp, modifier = Modifier.weight(1f))
                                IconButton(onClick = {
                                    scope.launch {
                                        database.sessionWebsiteCrossRefDao().deleteSpecificWebsite(session.id, site)
                                        BlockingSessionManager.getInstance(context).checkAndEnforce()
                                        refresh()
                                    }
                                }) {
                                    Icon(Icons.Default.RemoveCircleOutline, "Remover", tint = DangerRed.copy(alpha = 0.6f), modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                    
                    item { Spacer(Modifier.height(100.dp)) }
                }
            }
        }
        
        // Floating + Button inside the sheet? Or just at bottom.
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomEnd) {
            FloatingActionButton(
                onClick = onAddClick,
                modifier = Modifier.padding(24.dp),
                containerColor = AccentCyan,
                contentColor = DarkBg,
                shape = CircleShape
            ) {
                Icon(Icons.Default.Add, "Adicionar Mais")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContentPickerSheet(session: BlockSession, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val pm = context.packageManager
    val scope = rememberCoroutineScope()
    val database = remember { com.focusguard.database.AppDatabase.getDatabase(context) }

    var selectedTab by remember { mutableStateOf(0) } // 0 = Apps, 1 = Sites
    
    // App State
    var apps by remember { mutableStateOf<List<SelectableAppUi>>(emptyList()) }
    var isLoadingApps by remember { mutableStateOf(true) }
    
    // Site State
    var sites by remember { mutableStateOf<List<String>>(emptyList()) }
    var siteInput by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        scope.launch(Dispatchers.IO) {
            val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            val appList = installedApps.map { info ->
                val name = info.loadLabel(pm).toString()
                SelectableAppUi(info.packageName, name, null, false, true)
            }.sortedBy { it.appName.lowercase() }
            
            withContext(Dispatchers.Main) {
                apps = appList
                isLoadingApps = false
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = DarkSurface
    ) {
        Column(modifier = Modifier.fillMaxHeight(0.85f).padding(16.dp)) {
            Text("Adicionar Conteúdo", style = MaterialTheme.typography.titleLarge, color = TextPrimary, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color.Transparent,
                contentColor = AccentCyan,
                divider = {}
            ) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }) {
                    Text("Aplicativos", modifier = Modifier.padding(12.dp), color = if (selectedTab == 0) AccentCyan else TextHint)
                }
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }) {
                    Text("Websites", modifier = Modifier.padding(12.dp), color = if (selectedTab == 1) AccentCyan else TextHint)
                }
            }
            
            Spacer(Modifier.height(16.dp))

            Box(modifier = Modifier.weight(1f)) {
                if (selectedTab == 0) {
                    if (isLoadingApps) {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = AccentCyan)
                    } else {
                        LazyColumn {
                            items(apps) { app ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        apps = apps.map { if (it.packageName == app.packageName) it.copy(isSelected = !it.isSelected) else it }
                                    }.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(checked = app.isSelected, onCheckedChange = null, colors = CheckboxDefaults.colors(checkedColor = AccentCyan))
                                    Spacer(Modifier.width(12.dp))
                                    Text(app.appName, color = TextPrimary)
                                }
                            }
                        }
                    }
                } else {
                    Column {
                        OutlinedTextField(
                            value = siteInput,
                            onValueChange = { siteInput = it },
                            placeholder = { Text("Ex: site.com", color = TextHint) },
                            modifier = Modifier.fillMaxWidth(),
                            trailingIcon = {
                                TextButton(onClick = {
                                    if (siteInput.isNotBlank() && !sites.contains(siteInput.trim())) {
                                        sites = sites + siteInput.trim()
                                        siteInput = ""
                                    }
                                }) {
                                    Text("ADD +", color = AccentCyan)
                                }
                            },
                            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, focusedBorderColor = AccentCyan)
                        )
                        
                        Spacer(Modifier.height(16.dp))
                        
                        LazyColumn(modifier = Modifier.weight(1f)) {
                            items(sites) { site ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().background(DarkCard, RoundedCornerShape(12.dp)).padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(site, color = TextPrimary, modifier = Modifier.weight(1f))
                                    IconButton(onClick = { sites = sites - site }) {
                                        Icon(Icons.Default.Close, null, tint = DangerRed)
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                    }
                }
            }
            
            Button(
                onClick = {
                    scope.launch {
                        val selectedApps = apps.filter { it.isSelected }.map { it.packageName }
                        selectedApps.forEach { pkg ->
                            database.sessionAppCrossRefDao().insert(com.focusguard.database.SessionAppCrossRef(session.id, pkg))
                        }
                        sites.forEach { domain ->
                            database.sessionWebsiteCrossRefDao().insert(com.focusguard.database.SessionWebsiteCrossRef(session.id, domain))
                        }
                        BlockingSessionManager.getInstance(context).checkAndEnforce()
                        onDismiss()
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentCyan)
            ) {
                Text("Salvar Conteúdo", color = DarkBg, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun PasswordPromptDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Encerrar Bloqueio", color = TextPrimary) },
        text = {
            Column {
                Text("Digite sua senha de segurança para encerrar este bloqueio.", color = TextSecondary, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Senha") },
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, focusedBorderColor = AccentCyan)
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(password) }, colors = ButtonDefaults.buttonColors(containerColor = AccentCyan)) {
                Text("Confirmar", color = DarkBg)
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

@Composable
fun SessionListItem(
    session: BlockSession, 
    sessionManager: BlockingSessionManager,
    onRemoveBlock: () -> Unit,
    onAddContent: () -> Unit,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }
    
    val dateFormatter = remember { java.text.SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()) }
    val isCurrentlyActive = sessionManager.isCurrentlyInBlockingWindow(session)
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        border = BorderStroke(
            width = if (isCurrentlyActive) 1.5.dp else 1.dp,
            color = if (isCurrentlyActive) AccentCyan.copy(alpha = 0.5f) else CardBorder
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(if (isCurrentlyActive) AccentCyan.copy(alpha = 0.1f) else DarkCardElevated),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (session.sessionType == "PASSWORD") Icons.Default.Lock else Icons.Default.HourglassBottom,
                        contentDescription = null,
                        tint = if (isCurrentlyActive) AccentCyan else TextHint,
                        modifier = Modifier.size(20.dp)
                    )
                }
                
                Spacer(Modifier.width(12.dp))
                
                Column(Modifier.weight(1f)) {
                    Text(
                        if (session.sessionType == "PASSWORD") "Bloqueio por Senha" else "Bloqueio por Tempo",
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        if (session.isFixed24h) "Modo: Fixo 24h" else "Modo: Agendado",
                        color = TextHint,
                        fontSize = 12.sp
                    )
                }
                
                if (isCurrentlyActive) {
                    Surface(
                        color = AccentCyan.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            "ATIVO AGORA",
                            color = AccentCyan,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Configurações", tint = TextHint)
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        modifier = Modifier.background(DarkSurface)
                    ) {
                        DropdownMenuItem(
                            text = { Text("Adicionar apps/sites", color = TextPrimary) },
                            onClick = { showMenu = false; onAddContent() },
                            leadingIcon = { Icon(Icons.Default.Add, null, tint = AccentCyan) }
                        )
                        DropdownMenuItem(
                            text = { Text("Remover apps/sites", color = TextPrimary) },
                            onClick = { showMenu = false; onClick() },
                            leadingIcon = { Icon(Icons.Default.RemoveCircleOutline, null, tint = AccentCyan) }
                        )
                        HorizontalDivider(color = CardBorder, thickness = 0.5.dp)
                        DropdownMenuItem(
                            text = { Text("Remover bloqueio", color = DangerRed) },
                            onClick = { showMenu = false; onRemoveBlock() },
                            leadingIcon = { Icon(Icons.Default.Delete, null, tint = DangerRed) }
                        )
                    }
                }
            }
            
            Spacer(Modifier.height(16.dp))
            
            // Stats Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                InfoTag(Icons.Default.Apps, "${session.blockedAppsCount} Apps")
                InfoTag(Icons.Default.Language, "${session.blockedWebsitesCount} Sites")
                
                if (session.sessionType == "TIME" && session.endTime != null) {
                    val remainingMs = session.endTime - System.currentTimeMillis()
                    val remainingDays = TimeUnit.MILLISECONDS.toDays(remainingMs)
                    val remainingHours = TimeUnit.MILLISECONDS.toHours(remainingMs) % 24
                    InfoTag(Icons.Default.AccessTime, "${remainingDays}d ${remainingHours}h")
                }
            }
            
            if (!session.isFixed24h) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = CardBorder, thickness = 0.5.dp)
                Spacer(Modifier.height(12.dp))
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.EventRepeat, null, tint = TextHint, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Horário: ${String.format("%02d:%02d", session.recurringStartHour, session.recurringStartMinute)} - ${String.format("%02d:%02d", session.recurringEndHour, session.recurringEndMinute)}",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

@Composable
fun InfoTag(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = TextHint, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, color = TextSecondary, fontSize = 12.sp)
    }
}
