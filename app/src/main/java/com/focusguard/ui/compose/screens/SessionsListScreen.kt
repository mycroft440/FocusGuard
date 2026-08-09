package com.focusguard.ui.compose.screens

import kotlin.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.database.BlockSession
import com.focusguard.manager.BlockingSessionManager
import com.focusguard.ui.CreateSessionActivity
import com.focusguard.ui.compose.theme.*
import com.focusguard.R
import com.focusguard.ui.compose.screens.SelectableAppUi
import com.focusguard.ui.compose.screens.sessions.SessionsListViewModel
import com.focusguard.ui.compose.screens.sessions.AuthViewModel
import com.focusguard.utils.WebsiteBlocker
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.content.pm.PackageManager
import java.util.*
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsListScreen(
    sessionType: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    // ViewModel (Hilt) fornece:
    //  - sessions (StateFlow) via Repository → Room
    //  - isCurrentlyInBlockingWindow(session) para SessionListItem
    //  - endSession(sessionId) para encerrar sessão após auth
    //  - loadSessionDetails/clearAll/removeApp/removeSite para SessionDetailsSheet
    //  - initContentPicker/addPendingSite/removePendingSite/bulkAddContent para ContentPickerSheet
    // Tudo via Repository + BlockingSessionManager injetados por Hilt.
    val viewModel: SessionsListViewModel = hiltViewModel()
    val authViewModel: AuthViewModel = hiltViewModel()
    val sessions by viewModel.sessions.collectAsState()
    val filteredSessions = sessions.filter { it.sessionType == sessionType }
    
    var showPasswordPrompt by remember { mutableStateOf<Int?>(null) }
    var showDetailsSheet by remember { mutableStateOf<BlockSession?>(null) }
    var showAppPickerForSession by remember { mutableStateOf<BlockSession?>(null) }

    val title = if (sessionType == "PASSWORD") stringResource(R.string.sessions_title_password) else stringResource(R.string.sessions_title_time)
    val icon = if (sessionType == "PASSWORD") Icons.Default.VpnKey else Icons.Default.Timer

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, color = TextPrimary, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back), tint = TextPrimary)
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
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.sessions_add_new))
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
                            stringResource(R.string.sessions_empty_title),
                            color = TextHint,
                            fontSize = 16.sp,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.sessions_empty_desc),
                            color = TextHint.copy(alpha = 0.6f),
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                Text(
                    stringResource(R.string.sessions_active_header),
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
                            viewModel = viewModel,
                            onRemoveBlock = { showPasswordPrompt = session.id },
                            onAddContent = { showAppPickerForSession = session },
                            onClick = { showDetailsSheet = session }
                        )
                    }
                    item { Spacer(Modifier.height(80.dp)) }
                }
            }
        }
    }

    val sessionIdToEnd = showPasswordPrompt
    if (sessionIdToEnd != null) {
        PasswordPromptDialog(
            onDismiss = {
                showPasswordPrompt = null
                authViewModel.reset()
            },
            onAuthenticated = {
                viewModel.endSession(sessionIdToEnd)
                showPasswordPrompt = null
                authViewModel.reset()
            },
            authViewModel = authViewModel
        )
    }

    if (showDetailsSheet != null) {
        val currentDetails = showDetailsSheet
        SessionDetailsSheet(
            session = currentDetails!!,
            onDismiss = { showDetailsSheet = null },
            onAddClick = { 
                // Captura o ID antes de mudar o estado — evita race com
                // recomposição que poderia setar showDetailsSheet = null
                // entre o check e o uso (force-unwrap crash).
                currentDetails?.let { s ->
                    showDetailsSheet = null
                    showAppPickerForSession = s
                }
            },
            viewModel = viewModel
        )
    }

    if (showAppPickerForSession != null) {
        val currentPicker = showAppPickerForSession
        currentPicker?.let { session ->
            ContentPickerSheet(
                session = session,
                onDismiss = { showAppPickerForSession = null },
                viewModel = viewModel
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionDetailsSheet(
    session: BlockSession, 
    onDismiss: () -> Unit,
    onAddClick: () -> Unit,
    viewModel: SessionsListViewModel
) {
    val context = LocalContext.current
    val pm = remember { context.packageManager }
    val detailsState by viewModel.detailsSheetState.collectAsState()
    
    LaunchedEffect(session.id) {
        viewModel.loadSessionDetails(session.id)
    }
    // Quando o sheet fecha, reseta o estado para a próxima abertura
    DisposableEffect(Unit) {
        onDispose { viewModel.resetDetailsSheet() }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = DarkSurface,
        scrimColor = Color.Black.copy(alpha = 0.6f),
        dragHandle = { BottomSheetDefaults.DragHandle(color = TextHint.copy(alpha = 0.3f)) }
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    if (session.sessionType == "PASSWORD") stringResource(R.string.sessions_details_title_password) else stringResource(R.string.sessions_details_title_time),
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
                
                if (session.sessionType != "TIME") {
                    IconButton(onClick = {
                        viewModel.clearAllBlockedContent(session.id)
                    }) {
                        Icon(Icons.Default.DeleteSweep, stringResource(R.string.sessions_clear_all), tint = DangerRed)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            if (detailsState.isLoading) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AccentCyan)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (detailsState.blockedApps.isEmpty() && detailsState.blockedSites.isEmpty()) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                                Text(stringResource(R.string.sessions_no_content), color = TextHint, textAlign = TextAlign.Center)
                            }
                        }
                    }

                    if (detailsState.blockedApps.isNotEmpty()) {
                        item { Text(stringResource(R.string.sessions_category_apps), color = AccentCyan, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
                        items(detailsState.blockedApps) { pkg ->
                            val appName = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg }
                            Row(
                                modifier = Modifier.fillMaxWidth().background(DarkCard, RoundedCornerShape(12.dp)).padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Apps, null, tint = TextHint, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(12.dp))
                                Text(appName, color = TextPrimary, fontSize = 15.sp, modifier = Modifier.weight(1f))
                                if (session.sessionType != "TIME") {
                                    IconButton(onClick = {
                                        viewModel.removeAppFromSession(session.id, pkg)
                                    }) {
                                        Icon(Icons.Default.RemoveCircleOutline, stringResource(R.string.sessions_remove_item), tint = DangerRed.copy(alpha = 0.6f), modifier = Modifier.size(20.dp))
                                    }
                                }
                            }
                        }
                    }

                    if (detailsState.blockedSites.isNotEmpty()) {
                        item { Spacer(Modifier.height(8.dp)); Text(stringResource(R.string.sessions_category_websites), color = AccentCyan, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
                        items(detailsState.blockedSites) { site ->
                            Row(
                                modifier = Modifier.fillMaxWidth().background(DarkCard, RoundedCornerShape(12.dp)).padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Language, null, tint = TextHint, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(12.dp))
                                Text(WebsiteBlocker.displayRule(site), color = TextPrimary, fontSize = 15.sp, modifier = Modifier.weight(1f))
                                if (session.sessionType != "TIME") {
                                    IconButton(onClick = {
                                        viewModel.removeSiteFromSession(session.id, site)
                                    }) {
                                        Icon(Icons.Default.RemoveCircleOutline, stringResource(R.string.sessions_remove_item), tint = DangerRed.copy(alpha = 0.6f), modifier = Modifier.size(20.dp))
                                    }
                                }
                            }
                        }
                    }
                    
                    item { Spacer(Modifier.height(100.dp)) }
                }
            }
        }
        
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomEnd) {
            FloatingActionButton(
                onClick = onAddClick,
                modifier = Modifier.padding(24.dp),
                containerColor = AccentCyan,
                contentColor = DarkBg,
                shape = CircleShape
            ) {
                Icon(Icons.Default.Add, stringResource(R.string.sessions_add_more))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContentPickerSheet(
    session: BlockSession,
    onDismiss: () -> Unit,
    viewModel: SessionsListViewModel
) {
    val context = LocalContext.current
    val pm = context.packageManager
    val pickerState by viewModel.contentPickerState.collectAsState()

    var selectedTab by remember { mutableStateOf(0) }
    var apps by remember { mutableStateOf<List<SelectableAppUi>>(emptyList()) }
    var isLoadingApps by remember { mutableStateOf(true) }

    // Inicia o estado do ContentPicker ao abrir a sheet
    LaunchedEffect(Unit) {
        viewModel.initContentPicker()
        val appList = withContext(Dispatchers.IO) {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .map { info ->
                    val name = info.loadLabel(pm).toString()
                    SelectableAppUi(info.packageName, name, false, false, true)
                }
                .sortedBy { it.appName.lowercase() }
        }
        apps = appList
        isLoadingApps = false
    }
    // Reseta o estado ao fechar a sheet
    DisposableEffect(Unit) {
        onDispose { viewModel.resetContentPicker() }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = DarkSurface
    ) {
        Column(modifier = Modifier.fillMaxHeight(0.85f).padding(16.dp)) {
            Text(stringResource(R.string.sessions_add_content_title), style = MaterialTheme.typography.titleLarge, color = TextPrimary, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color.Transparent,
                contentColor = AccentCyan,
                divider = {}
            ) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }) {
                    Text(stringResource(R.string.sessions_category_apps), modifier = Modifier.padding(12.dp), color = if (selectedTab == 0) AccentCyan else TextHint)
                }
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }) {
                    Text(stringResource(R.string.sessions_category_websites), modifier = Modifier.padding(12.dp), color = if (selectedTab == 1) AccentCyan else TextHint)
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
                                    modifier = Modifier.fillMaxWidth().clickable(enabled = !pickerState.isSaving) {
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
                        val pornographySelected =
                            com.focusguard.data.PredefinedWebsites.PORNOGRAPHY_RULE in
                                pickerState.sites
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (pornographySelected) {
                                        AccentCyan.copy(alpha = 0.08f)
                                    } else {
                                        DarkCard
                                    },
                                    RoundedCornerShape(12.dp)
                                )
                                .clickable(enabled = !pickerState.isSaving) {
                                    viewModel.togglePendingPornography()
                                }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Shield, null, tint = AccentCyan)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    stringResource(R.string.protection_sites_pornography),
                                    color = TextPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    stringResource(
                                        R.string.protection_sites_pornography_subtitle
                                    ),
                                    color = TextHint,
                                    fontSize = 12.sp
                                )
                            }
                            Checkbox(
                                checked = pornographySelected,
                                onCheckedChange = null,
                                colors = CheckboxDefaults.colors(checkedColor = AccentCyan)
                            )
                        }

                        Spacer(Modifier.height(12.dp))

                        OutlinedTextField(
                            value = pickerState.siteInput,
                            onValueChange = { viewModel.updateSiteInput(it) },
                            placeholder = { Text(stringResource(R.string.sessions_site_placeholder), color = TextHint) },
                            supportingText = {
                                Text(stringResource(R.string.website_rule_hint), color = TextHint)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !pickerState.isSaving,
                            trailingIcon = {
                                TextButton(onClick = { viewModel.addPendingSite() }) {
                                    Text(stringResource(R.string.sessions_add_site_btn), color = AccentCyan)
                                }
                            },
                            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, focusedBorderColor = AccentCyan)
                        )
                        
                        Spacer(Modifier.height(16.dp))
                        
                        LazyColumn(modifier = Modifier.weight(1f)) {
                            items(pickerState.sites) { site ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().background(DarkCard, RoundedCornerShape(12.dp)).padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(WebsiteBlocker.displayRule(site), color = TextPrimary, modifier = Modifier.weight(1f))
                                    IconButton(onClick = { viewModel.removePendingSite(site) }, enabled = !pickerState.isSaving) {
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
                    val selectedApps = apps.filter { it.isSelected }.map { it.packageName }
                    viewModel.bulkAddContent(session.id, selectedApps)
                    // Fecha a sheet imediatamente; o VM cuida do save em background
                    onDismiss()
                },
                enabled = !pickerState.isSaving,
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentCyan)
            ) {
                if (pickerState.isSaving) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = DarkBg)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (pickerState.isSaving) stringResource(R.string.sessions_saving) else stringResource(R.string.sessions_save_content), color = DarkBg, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun PasswordPromptDialog(
    onDismiss: () -> Unit,
    onAuthenticated: () -> Unit,
    authViewModel: AuthViewModel
) {
    val context = LocalContext.current
    val authState by authViewModel.uiState.collectAsState()

    // Observa sucesso de autenticação e chama callback
    LaunchedEffect(authState.isAuthenticated) {
        if (authState.isAuthenticated) {
            onAuthenticated()
            authViewModel.onAuthenticatedHandled()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sessions_end_title), color = TextPrimary) },
        text = {
            Column {
                Text(stringResource(R.string.sessions_end_desc), color = TextSecondary, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = authState.password,
                    onValueChange = { authViewModel.updatePassword(it) },
                    placeholder = { Text(stringResource(R.string.sessions_password_hint), color = TextHint) },
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    isError = authState.error != null,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, focusedBorderColor = AccentCyan)
                )
                if (authState.error != null) {
                    Text(authState.error!!, color = DangerRed, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    authViewModel.verifyPassword(
                        errorMessage = context.getString(R.string.sessions_wrong_password)
                    )
                },
                enabled = !authState.isVerifying && authState.password.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = AccentCyan)
            ) {
                if (authState.isVerifying) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = DarkBg
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(stringResource(R.string.sessions_confirm), color = DarkBg)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel), color = TextSecondary)
            }
        },
        containerColor = DarkSurface,
        shape = RoundedCornerShape(24.dp)
    )
}

@Composable
fun SessionListItem(
    session: BlockSession,
    viewModel: SessionsListViewModel,
    onRemoveBlock: () -> Unit,
    onAddContent: () -> Unit,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }
    val isCurrentlyActive = viewModel.isCurrentlyInBlockingWindow(session)
    
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
                        if (session.sessionType == "PASSWORD") stringResource(R.string.sessions_type_password) else stringResource(R.string.sessions_type_time),
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        if (session.isFixed24h) stringResource(R.string.sessions_mode_fixed) else stringResource(R.string.sessions_mode_scheduled),
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
                            stringResource(R.string.sessions_active_badge),
                            color = AccentCyan,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                val isTimeActive = session.sessionType == "TIME" && isCurrentlyActive
                    
                Box {
                    IconButton(onClick = { 
                        if (isTimeActive) {
                            Toast.makeText(context, context.getString(R.string.sessions_locked_toast), Toast.LENGTH_SHORT).show()
                        } else {
                            showMenu = true 
                        }
                    }) {
                        Icon(
                            imageVector = if (isTimeActive) Icons.Default.Lock else Icons.Default.Settings, 
                            contentDescription = stringResource(R.string.nav_settings), 
                            tint = if (isTimeActive) DangerRed else TextHint
                        )
                    }
                    
                    if (!isTimeActive) {
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                            modifier = Modifier.background(DarkSurface)
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.sessions_menu_add), color = TextPrimary) },
                                onClick = { showMenu = false; onAddContent() },
                                leadingIcon = { Icon(Icons.Default.Add, null, tint = AccentCyan) }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.sessions_menu_remove), color = TextPrimary) },
                                onClick = { showMenu = false; onClick() },
                                leadingIcon = { Icon(Icons.Default.RemoveCircleOutline, null, tint = AccentCyan) }
                            )
                            HorizontalDivider(color = CardBorder, thickness = 0.5.dp)
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.sessions_menu_delete), color = DangerRed) },
                                onClick = { showMenu = false; onRemoveBlock() },
                                leadingIcon = { Icon(Icons.Default.Delete, null, tint = DangerRed) }
                            )
                        }
                    }
                }
            }
            
            Spacer(Modifier.height(16.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                InfoTag(Icons.Default.Apps, stringResource(R.string.sessions_apps_count, session.blockedAppsCount))
                InfoTag(Icons.Default.Language, stringResource(R.string.sessions_sites_count, session.blockedWebsitesCount))
                
                if (session.sessionType == "TIME" && session.endTime != null) {
                    val remainingMs = (session.endTime - System.currentTimeMillis()).coerceAtLeast(0L)
                    val remainingDays = TimeUnit.MILLISECONDS.toDays(remainingMs)
                    val remainingHours = TimeUnit.MILLISECONDS.toHours(remainingMs) % 24
                    InfoTag(Icons.Default.AccessTime, stringResource(R.string.sessions_time_remaining, remainingDays, remainingHours))
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
                        text = stringResource(R.string.sessions_schedule_label, String.format("%02d:%02d", session.recurringStartHour, session.recurringStartMinute), String.format("%02d:%02d", session.recurringEndHour, session.recurringEndMinute)),
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
