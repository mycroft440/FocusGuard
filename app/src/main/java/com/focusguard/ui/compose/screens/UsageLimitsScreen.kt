package com.focusguard.ui.compose.screens

import kotlin.OptIn
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.material3.ExperimentalMaterial3Api
import com.focusguard.ui.compose.components.limits.*

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.security.MasterCredentialPolicy
import com.focusguard.ui.MasterPasswordActivity
import com.focusguard.ui.compose.theme.*
import com.focusguard.R
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageLimitsScreen(
    onPermissionsRequired: () -> Unit,
    onBack: () -> Unit,
    viewModel: UsageLimitsViewModel = hiltViewModel()
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val masterPasswordLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.refresh()
    }
    val openMasterPassword: () -> Unit = {
        masterPasswordLauncher.launch(MasterPasswordActivity.createIntent(context))
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.consumeError()
        }
    }

    if (uiState.permissionReadiness != PermissionReadiness.READY) {
        UsageLimitsPermissionGate(
            checking = uiState.permissionReadiness == PermissionReadiness.CHECKING,
            onPermissionsRequired = onPermissionsRequired,
            onBack = onBack
        )
        return
    }

    val permissionsMissing = false

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.limits_title), color = MaterialTheme.colorScheme.onBackground, fontSize = 18.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), tint = MaterialTheme.colorScheme.onBackground)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = AccentCyan
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text(stringResource(R.string.sessions_category_apps), color = if (selectedTab == 0) AccentCyan else TextHint, fontWeight = FontWeight.Bold) }
                )
                // "Sites", não "Sites e palavras": um limite conta tempo gasto
                // num alvo, e palavra não é alvo de tempo — ver BlockTargetPolicy.
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text(stringResource(R.string.block_targets_tab_sites), color = if (selectedTab == 1) AccentCyan else TextHint, fontWeight = FontWeight.Bold) }
                )
            }

            when (selectedTab) {
                0 -> AppLimitsTab(
                    permissionsMissing = permissionsMissing,
                    apps = uiState.apps,
                    isLoading = uiState.isLoading,
                    hasMasterCredential = uiState.hasMasterCredential,
                    evaluateMutation = viewModel::evaluateMutation,
                    onSave = viewModel::saveAppLimit,
                    onConfigureMasterPassword = openMasterPassword,
                )
                1 -> WebsiteLimitsTab(
                    permissionsMissing = permissionsMissing,
                    sites = uiState.websites,
                    isLoading = uiState.isLoading,
                    hasMasterCredential = uiState.hasMasterCredential,
                    evaluateMutation = viewModel::evaluateMutation,
                    onSave = viewModel::saveWebsiteLimit,
                    onDelete = viewModel::deleteWebsiteLimit,
                    onConfigureMasterPassword = openMasterPassword,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UsageLimitsPermissionGate(
    checking: Boolean,
    onPermissionsRequired: () -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.limits_title),
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 18.sp
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            stringResource(R.string.action_back),
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (checking) {
                CircularProgressIndicator(color = AccentCyan)
            } else {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = DangerRed,
                    modifier = Modifier.size(52.dp)
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.pending_permissions_title),
                    color = TextPrimary,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.blocking_permissions_required_desc),
                    color = TextSecondary,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = onPermissionsRequired,
                    colors = ButtonDefaults.buttonColors(containerColor = AccentCyan)
                ) {
                    Text(
                        stringResource(R.string.dopamine_open_permissions),
                        color = DarkBg,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun AppLimitsTab(
    permissionsMissing: Boolean,
    apps: List<UsageLimitAppUi>,
    isLoading: Boolean,
    hasMasterCredential: Boolean,
    evaluateMutation: (String, Long?) -> MasterCredentialPolicy.MutationGate,
    onSave: (UsageLimitAppUi, Int?, Boolean, String, Long?) -> Unit,
    onConfigureMasterPassword: () -> Unit,
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedApp by remember { mutableStateOf<UsageLimitAppUi?>(null) }
    var showDialog by remember { mutableStateOf(false) }
    var showMasterCredentialConfirm by remember { mutableStateOf(false) }
    var showTimeLockedAlert by remember { mutableStateOf(false) }
    var showSafetyModeAlert by remember { mutableStateOf(false) }
    var showCredentialMissingAlert by remember { mutableStateOf(false) }

    /**
     * Single entry point for opening the edit dialog.
     *
     * Every path — active, paused or unconfigured — funnels through the policy, so
     * a limit cannot be edited or removed by taking a different route through the
     * list. Unbreakable refusals are reported before asking for a credential.
     */
    fun requestLimitEdit(app: UsageLimitAppUi) {
        val gate = evaluateMutation(app.lockMode, app.lockUntilTimestamp)
        when (gate) {
            MasterCredentialPolicy.MutationGate.BLOCKED_BY_TIME_HARDENING ->
                showTimeLockedAlert = true

            MasterCredentialPolicy.MutationGate.BLOCKED_BY_SAFETY_MODE ->
                showSafetyModeAlert = true

            MasterCredentialPolicy.MutationGate.MASTER_CREDENTIAL_NOT_CONFIGURED ->
                showCredentialMissingAlert = true

            MasterCredentialPolicy.MutationGate.MASTER_CREDENTIAL_REQUIRED -> {
                selectedApp = app
                showMasterCredentialConfirm = true
            }

            MasterCredentialPolicy.MutationGate.ALLOWED -> {
                selectedApp = app
                showDialog = true
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text(stringResource(R.string.limits_search_placeholder), color = TextHint) },
            leadingIcon = { Icon(Icons.Filled.Search, null, tint = TextHint) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AccentCyan,
                unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
                focusedTextColor = MaterialTheme.colorScheme.onBackground
            ),
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel), tint = TextHint)
                    }
                }
            },
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

                if (activeLimits.isNotEmpty()) {
                    item { Text(stringResource(R.string.limits_active_section), fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = AccentCyan, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)) }
                    items(activeLimits, key = { "active_${it.packageName}" }) { app ->
                        UsageLimitItem(app, isActive = true) { requestLimitEdit(app) }
                    }
                }

                if (inactiveLimits.isNotEmpty()) {
                    item { Text(stringResource(R.string.limits_paused_section), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextHint, modifier = Modifier.padding(top = 24.dp, bottom = 8.dp)) }
                    items(inactiveLimits, key = { "inactive_${it.packageName}" }) { app ->
                        UsageLimitItem(app, isActive = false) { requestLimitEdit(app) }
                    }
                }

                if (unconfiguredApps.isNotEmpty()) {
                    item {
                        val sectionTitle = if (activeLimits.isEmpty() && inactiveLimits.isEmpty()) stringResource(R.string.limits_setup_section) else stringResource(R.string.limits_other_section)
                        Text(sectionTitle, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextSecondary, modifier = Modifier.padding(top = 24.dp, bottom = 8.dp)) }
                    items(unconfiguredApps, key = { "unconf_${it.packageName}" }) { app ->
                        UsageLimitItem(app, isActive = false) { requestLimitEdit(app) }
                    }
                }
            }
        }
    }

    if (showDialog && selectedApp != null) {
        AppLimitDialog(
            app = selectedApp!!,
            permissionsMissing = permissionsMissing,
            hasMasterCredential = hasMasterCredential,
            onConfigureMasterPassword = onConfigureMasterPassword,
            onDismiss = { showDialog = false },
            onSave = { minutes, enabled, lockMode, _, lockUntil ->
                selectedApp?.let { app ->
                    onSave(app, minutes, enabled, lockMode, lockUntil)
                }
                showDialog = false
            }
        )
    }

    if (showMasterCredentialConfirm && selectedApp != null) {
        ConfirmMasterCredentialDialog(
            promptRes = R.string.master_credential_required_to_change_limit,
            onDismiss = { showMasterCredentialConfirm = false },
            onConfirmed = {
                showMasterCredentialConfirm = false
                showDialog = true
            }
        )
    }

    if (showTimeLockedAlert) {
        AlertDialog(
            onDismissRequest = { showTimeLockedAlert = false },
            title = { Text(stringResource(R.string.limits_locked_alert_title), color = DangerRed) },
            text = { Text(stringResource(R.string.limits_locked_alert_desc), color = MaterialTheme.colorScheme.onSurface) },
            confirmButton = { TextButton({ showTimeLockedAlert = false }) { Text(stringResource(R.string.action_ok), color = AccentCyan) } },
            containerColor = MaterialTheme.colorScheme.surface
        )
    }

    if (showSafetyModeAlert) {
        AlertDialog(
            onDismissRequest = { showSafetyModeAlert = false },
            title = { Text(stringResource(R.string.limits_security_mode), color = DangerRed) },
            text = {
                Text(
                    stringResource(R.string.master_credential_blocked_by_safety_mode),
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            confirmButton = {
                TextButton({ showSafetyModeAlert = false }) {
                    Text(stringResource(R.string.action_ok), color = AccentCyan)
                }
            },
            containerColor = MaterialTheme.colorScheme.surface
        )
    }

    if (showCredentialMissingAlert) {
        AlertDialog(
            onDismissRequest = { showCredentialMissingAlert = false },
            title = { Text(stringResource(R.string.deactivation_password_title), color = DangerRed) },
            text = {
                Text(
                    stringResource(R.string.master_credential_not_configured),
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            confirmButton = {
                TextButton({
                    showCredentialMissingAlert = false
                    onConfigureMasterPassword()
                }) {
                    Text(
                        stringResource(R.string.master_credential_create_action),
                        color = AccentCyan
                    )
                }
            },
            containerColor = MaterialTheme.colorScheme.surface
        )
    }
}

@Composable
fun WebsiteLimitsTab(
    permissionsMissing: Boolean,
    sites: List<WebsiteLimitUi>,
    isLoading: Boolean,
    hasMasterCredential: Boolean,
    evaluateMutation: (String, Long?) -> MasterCredentialPolicy.MutationGate,
    onSave: (String?, String, Int, Boolean, String, Long?) -> Unit,
    onDelete: (String) -> Unit,
    onConfigureMasterPassword: () -> Unit,
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var selectedSite by remember { mutableStateOf<WebsiteLimitUi?>(null) }
    var showEditDialog by remember { mutableStateOf(false) }
    var showMasterCredentialConfirm by remember { mutableStateOf(false) }
    var masterCredentialPromptRes by remember {
        mutableIntStateOf(R.string.master_credential_required_to_change_limit)
    }
    var showTimeLockedAlert by remember { mutableStateOf(false) }
    var showSafetyModeAlert by remember { mutableStateOf(false) }
    var showCredentialMissingAlert by remember { mutableStateOf(false) }
    var pendingAction: (() -> Unit)? by remember { mutableStateOf(null) }

    /**
     * Gate for editing or deleting a website limit. Mirrors the app-limit path so
     * both kinds of usage limit obey the same rules.
     */
    fun requestSiteMutation(
        site: WebsiteLimitUi,
        promptRes: Int,
        action: () -> Unit
    ) {
        val gate = evaluateMutation(site.lockMode, site.lockUntilTimestamp)
        when (gate) {
            MasterCredentialPolicy.MutationGate.BLOCKED_BY_TIME_HARDENING ->
                showTimeLockedAlert = true

            MasterCredentialPolicy.MutationGate.BLOCKED_BY_SAFETY_MODE ->
                showSafetyModeAlert = true

            MasterCredentialPolicy.MutationGate.MASTER_CREDENTIAL_NOT_CONFIGURED ->
                showCredentialMissingAlert = true

            MasterCredentialPolicy.MutationGate.MASTER_CREDENTIAL_REQUIRED -> {
                selectedSite = site
                masterCredentialPromptRes = promptRes
                pendingAction = action
                showMasterCredentialConfirm = true
            }

            MasterCredentialPolicy.MutationGate.ALLOWED -> action()
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
            Text(stringResource(R.string.limits_add_site_btn), color = DarkBg, fontWeight = FontWeight.Bold)
        }

        if (isLoading) {
            Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = AccentCyan) }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                items(sites, key = { it.domain }) { site ->
                    WebsiteLimitItem(
                        site = site,
                        onClick = {
                            requestSiteMutation(
                                site = site,
                                promptRes = R.string.master_credential_required_to_change_limit
                            ) {
                                selectedSite = site
                                showEditDialog = true
                            }
                        },
                        onDelete = {
                            requestSiteMutation(
                                site = site,
                                promptRes = R.string.master_credential_required_to_remove_limit
                            ) {
                                onDelete(site.domain)
                            }
                        }
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        AddWebsiteLimitDialog(
            permissionsMissing = permissionsMissing,
            hasMasterCredential = hasMasterCredential,
            onConfigureMasterPassword = onConfigureMasterPassword,
            onDismiss = { showAddDialog = false },
            onSave = { domain, minutes, lockMode, _, lockUntil ->
                onSave(null, domain, minutes, true, lockMode, lockUntil)
                showAddDialog = false
            }
        )
    }

    if (showEditDialog && selectedSite != null) {
        EditWebsiteLimitDialog(site = selectedSite!!, permissionsMissing = permissionsMissing, onDismiss = { showEditDialog = false }, onSave = { minutes, enabled, lockMode, _, lockUntil ->
            val siteToEdit = selectedSite ?: return@EditWebsiteLimitDialog
            onSave(siteToEdit.domain, siteToEdit.domain, minutes, enabled, lockMode, lockUntil)
            showEditDialog = false
        })
    }

    if (showMasterCredentialConfirm && selectedSite != null) {
        ConfirmMasterCredentialDialog(
            promptRes = masterCredentialPromptRes,
            onDismiss = {
                showMasterCredentialConfirm = false
                pendingAction = null
            },
            onConfirmed = {
                showMasterCredentialConfirm = false
                pendingAction?.invoke()
                pendingAction = null
            }
        )
    }

    if (showSafetyModeAlert) {
        AlertDialog(
            onDismissRequest = { showSafetyModeAlert = false },
            title = { Text(stringResource(R.string.limits_security_mode), color = DangerRed) },
            text = {
                Text(
                    stringResource(R.string.master_credential_blocked_by_safety_mode),
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            confirmButton = {
                TextButton({ showSafetyModeAlert = false }) {
                    Text(stringResource(R.string.action_ok), color = AccentCyan)
                }
            },
            containerColor = MaterialTheme.colorScheme.surface
        )
    }

    if (showCredentialMissingAlert) {
        AlertDialog(
            onDismissRequest = { showCredentialMissingAlert = false },
            title = {
                Text(stringResource(R.string.deactivation_password_title), color = DangerRed)
            },
            text = {
                Text(
                    stringResource(R.string.master_credential_not_configured),
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            confirmButton = {
                TextButton({
                    showCredentialMissingAlert = false
                    onConfigureMasterPassword()
                }) {
                    Text(
                        stringResource(R.string.master_credential_create_action),
                        color = AccentCyan
                    )
                }
            },
            containerColor = MaterialTheme.colorScheme.surface
        )
    }

    if (showTimeLockedAlert) {
        AlertDialog(
            onDismissRequest = { showTimeLockedAlert = false },
            title = { Text(stringResource(R.string.limits_locked_alert_title), color = DangerRed) },
            text = { Text(stringResource(R.string.limits_locked_alert_desc), color = TextPrimary) },
            confirmButton = { TextButton({ showTimeLockedAlert = false }) { Text(stringResource(R.string.action_ok), color = AccentCyan) } },
            containerColor = MaterialTheme.colorScheme.surface
        )
    }
}
