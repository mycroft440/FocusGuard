package com.focusguard.ui.compose.screens

import com.focusguard.monetization.RewardedGateCoordinator
import com.focusguard.monetization.MonetizationPolicy
import kotlin.OptIn
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.material3.ExperimentalMaterial3Api
import com.focusguard.ui.compose.components.limits.*

import android.content.Context
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.database.AppUsageLimit
import com.focusguard.database.WebsiteUsageLimit
import com.focusguard.data.PredefinedApps
import com.focusguard.manager.BlockingSessionManager
import com.focusguard.security.AuthManager
import com.focusguard.security.DeactivationCredentialManager
import com.focusguard.security.MasterCredentialPolicy
import com.focusguard.security.ProtectionPermissionGate
import com.focusguard.ui.MasterPasswordActivity
import com.focusguard.ui.compose.rememberAppDatabase
import com.focusguard.ui.compose.theme.*
import com.focusguard.R
import com.focusguard.utils.WebsiteBlocker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageLimitsScreen(
    authManager: AuthManager,
    onPermissionsRequired: () -> Unit,
    onBack: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var permissionResumeKey by remember { mutableIntStateOf(0) }
    var protectionPermissionsReady by remember { mutableStateOf<Boolean?>(null) }
    var credentialRevision by remember { mutableIntStateOf(0) }
    val credentialManager = remember(context) { DeactivationCredentialManager(context) }
    val hasMasterCredential = remember(credentialRevision) {
        credentialManager.hasCredential()
    }
    val masterPasswordLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        credentialRevision++
    }
    val openMasterPassword: () -> Unit = {
        masterPasswordLauncher.launch(MasterPasswordActivity.createIntent(context))
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permissionResumeKey++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(permissionResumeKey) {
        protectionPermissionsReady = withContext(Dispatchers.IO) {
            ProtectionPermissionGate.read(context).isReady
        }
    }

    if (protectionPermissionsReady != true) {
        UsageLimitsPermissionGate(
            checking = protectionPermissionsReady == null,
            onPermissionsRequired = onPermissionsRequired,
            onBack = onBack
        )
        return
    }

    val permissionsMissing = false

    Scaffold(
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
                    authManager = authManager,
                    hasMasterCredential = hasMasterCredential,
                    onConfigureMasterPassword = openMasterPassword,
                    onPermissionsRequired = onPermissionsRequired
                )
                1 -> WebsiteLimitsTab(
                    permissionsMissing = permissionsMissing,
                    authManager = authManager,
                    hasMasterCredential = hasMasterCredential,
                    onConfigureMasterPassword = openMasterPassword,
                    onPermissionsRequired = onPermissionsRequired
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
    authManager: AuthManager,
    hasMasterCredential: Boolean,
    onConfigureMasterPassword: () -> Unit,
    onPermissionsRequired: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // P2-2: usa Hilt EntryPoint via rememberAppDatabase()
    val db = rememberAppDatabase()
    val blockingSessionManager = remember(context) {
        BlockingSessionManager.getInstance(context)
    }
    var apps by remember { mutableStateOf<List<UsageLimitAppUi>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }
    var selectedApp by remember { mutableStateOf<UsageLimitAppUi?>(null) }
    var showDialog by remember { mutableStateOf(false) }
    var showMasterCredentialConfirm by remember { mutableStateOf(false) }
    var showTimeLockedAlert by remember { mutableStateOf(false) }
    var showSafetyModeAlert by remember { mutableStateOf(false) }
    var showCredentialMissingAlert by remember { mutableStateOf(false) }

    val credentialManager = remember(context) { DeactivationCredentialManager(context) }

    /**
     * Single entry point for opening the edit dialog.
     *
     * Every path — active, paused or unconfigured — funnels through the policy, so
     * a limit cannot be edited or removed by taking a different route through the
     * list. Unbreakable refusals are reported before asking for a credential.
     */
    fun requestLimitEdit(app: UsageLimitAppUi) {
        val gate = MasterCredentialPolicy.evaluateLimitMutation(
            lockMode = app.lockMode,
            lockUntilTimestamp = app.lockUntilTimestamp,
            safetyModeEnabled = authManager.isSafetyModeEnabled(),
            hasMasterCredential = credentialManager.hasCredential(),
            masterCredentialVerified = false
        )
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

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            val resolveInfos = pm.queryIntentActivities(intent, 0)
            val limitDao = db.appUsageLimitDao()
            val existingLimits = limitDao.getAllStatic().associateBy { it.packageName }
            val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
            val cal = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, 0)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
            }
            val stats = usageStatsManager.queryAndAggregateUsageStats(cal.timeInMillis, System.currentTimeMillis())

            val installedApps = resolveInfos.mapNotNull { info ->
                val packageName = info.activityInfo.packageName
                if (packageName == context.packageName) return@mapNotNull null
                val appName = info.loadLabel(pm).toString()
                val limit = existingLimits[packageName]
                UsageLimitAppUi(
                    packageName = packageName,
                    appName = appName,
                    currentLimitMinutes = limit?.dailyLimitMinutes,
                    isEnabled = limit?.isEnabled ?: false,
                    usageMs = stats[packageName]?.totalTimeInForeground ?: 0L,
                    lockMode = limit?.lockMode ?: "NONE",
                    lockPasswordHash = limit?.lockPasswordHash,
                    lockUntilTimestamp = limit?.lockUntilTimestamp
                )
            }
            val installedPackages = installedApps.mapTo(mutableSetOf()) { it.packageName }
            val absentKnownApps = PredefinedApps.PREVENTIVE_APPS
                .asSequence()
                .filter { it.packageName !in installedPackages }
                .map { predefined ->
                    val limit = existingLimits[predefined.packageName]
                    UsageLimitAppUi(
                        packageName = predefined.packageName,
                        appName = predefined.appName,
                        currentLimitMinutes = limit?.dailyLimitMinutes,
                        isEnabled = limit?.isEnabled ?: false,
                        usageMs = 0L,
                        lockMode = limit?.lockMode ?: "NONE",
                        lockPasswordHash = limit?.lockPasswordHash,
                        lockUntilTimestamp = limit?.lockUntilTimestamp
                    )
                }
                .toList()
            val absentConfiguredApps = existingLimits.values
                .asSequence()
                .filter { it.packageName !in installedPackages }
                .filter { limit -> absentKnownApps.none { it.packageName == limit.packageName } }
                .map { limit ->
                    UsageLimitAppUi(
                        packageName = limit.packageName,
                        appName = limit.appName.ifBlank { limit.packageName },
                        currentLimitMinutes = limit.dailyLimitMinutes,
                        isEnabled = limit.isEnabled,
                        usageMs = 0L,
                        lockMode = limit.lockMode,
                        lockPasswordHash = limit.lockPasswordHash,
                        lockUntilTimestamp = limit.lockUntilTimestamp
                    )
                }
                .toList()
            val loadedApps = (installedApps + absentKnownApps + absentConfiguredApps)
                .distinctBy { it.packageName }
                .sortedBy { it.appName }

            withContext(Dispatchers.Main) { apps = loadedApps; isLoading = false }
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
        AppLimitRedesignedSheet(
            app = selectedApp!!,
            permissionsMissing = permissionsMissing,
            hasMasterCredential = hasMasterCredential,
            onConfigureMasterPassword = onConfigureMasterPassword,
            onDismiss = { showDialog = false },
            onSave = { minutes, enabled, lockMode, lockPassword, lockUntil ->
                val monetizedAction: () -> Unit = {
                scope.launch(Dispatchers.IO) {
                    if (!ProtectionPermissionGate.read(context).isReady) {
                        withContext(Dispatchers.Main) { onPermissionsRequired() }
                        return@launch
                    }
                    val limitDao = db.appUsageLimitDao()
                    if (minutes != null && minutes > 0) {
                        limitDao.insert(
                            AppUsageLimit(
                                packageName = selectedApp!!.packageName,
                                appName = selectedApp!!.appName,
                                dailyLimitMinutes = minutes,
                                isEnabled = enabled,
                                lockMode = lockMode,
                                lockPasswordHash = null,
                                lockUntilTimestamp = lockUntil,
                                preventOpeningAfterLimit = true,
                                unlockWithPassword = lockMode.equals(
                                    "PASSWORD",
                                    ignoreCase = true
                                )
                            )
                        )
                        val updated = selectedApp!!.copy(currentLimitMinutes = minutes, isEnabled = enabled, lockMode = lockMode, lockPasswordHash = null, lockUntilTimestamp = lockUntil)
                        apps = apps.map { if (it.packageName == updated.packageName) updated else it }
                    } else {
                        val existing = limitDao.getAllStatic().find { it.packageName == selectedApp!!.packageName }
                        if (existing != null) limitDao.delete(existing)
                        val updated = selectedApp!!.copy(currentLimitMinutes = null, isEnabled = false, lockMode = "NONE", lockPasswordHash = null, lockUntilTimestamp = null)
                        apps = apps.map { if (it.packageName == updated.packageName) updated else it }
                    }
                    blockingSessionManager.checkAndEnforce()
                    withContext(Dispatchers.Main) { showDialog = false }
                }

                }
                val targetAlreadyConfigured = selectedApp!!.currentLimitMinutes != null
                val isCreatingLimit = minutes != null && minutes > 0 && !targetAlreadyConfigured
                val configuredCount = apps.count { it.currentLimitMinutes != null }
                if (isCreatingLimit && MonetizationPolicy.requiresExtraUsageLimitAd(configuredCount, targetAlreadyConfigured)) {
                    RewardedGateCoordinator.launch(
                        context = context,
                        requiredAds = 1,
                        title = "Adicionar mais um aplicativo",
                        description = "Assista a 1 anúncio para adicionar este aplicativo ao limite diário.",
                        action = monetizedAction
                    )
                } else {
                    monetizedAction()
                }
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
    authManager: AuthManager,
    hasMasterCredential: Boolean,
    onConfigureMasterPassword: () -> Unit,
    onPermissionsRequired: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // P2-2: usa Hilt EntryPoint via rememberAppDatabase()
    val db = rememberAppDatabase()
    val blockingSessionManager = remember(context) {
        BlockingSessionManager.getInstance(context)
    }
    var sites by remember { mutableStateOf<List<WebsiteLimitUi>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
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

    val credentialManager = remember(context) { DeactivationCredentialManager(context) }

    /**
     * Gate for editing or deleting a website limit. Mirrors the app-limit path so
     * both kinds of usage limit obey the same rules.
     */
    fun requestSiteMutation(
        site: WebsiteLimitUi,
        promptRes: Int,
        action: () -> Unit
    ) {
        val gate = MasterCredentialPolicy.evaluateLimitMutation(
            lockMode = site.lockMode,
            lockUntilTimestamp = site.lockUntilTimestamp,
            safetyModeEnabled = authManager.isSafetyModeEnabled(),
            hasMasterCredential = credentialManager.hasCredential(),
            masterCredentialVerified = false
        )
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

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val today = java.text.SimpleDateFormat(
                "yyyy-MM-dd",
                java.util.Locale.US
            ).format(java.util.Date())
            val allLimits = db.websiteUsageLimitDao().getAllStatic()
            val usageStats = com.focusguard.utils.WebsiteUsageLimitPolicy.aggregateUsageByRule(
                usageByIdentifier = db.dailyUsageStatDao()
                    .getStatsForDateStatic(today)
                    .map { it.identifier to it.timeSpentMs },
                configuredRules = allLimits.map { it.domain }
            )
            val loadedSites = allLimits.map {
                WebsiteLimitUi(
                    it.domain,
                    it.dailyLimitMinutes,
                    it.isEnabled,
                    usageStats[WebsiteBlocker.normalizeRule(it.domain)] ?: 0L,
                    it.lockMode,
                    it.lockPasswordHash,
                    it.lockUntilTimestamp
                )
            }
            withContext(Dispatchers.Main) {
                sites = loadedSites
                isLoading = false
            }
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
                                scope.launch(Dispatchers.IO) {
                                    val websiteDao = db.websiteUsageLimitDao()
                                    val existing = websiteDao.getAllStatic()
                                        .find { it.domain == site.domain }
                                    if (existing != null) websiteDao.delete(existing)
                                    blockingSessionManager.checkAndEnforce()
                                    withContext(Dispatchers.Main) {
                                        sites = sites.filter { it.domain != site.domain }
                                    }
                                }
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
                val monetizedAction: () -> Unit = {
                scope.launch(Dispatchers.IO) {
                    if (!ProtectionPermissionGate.read(context).isReady) {
                        withContext(Dispatchers.Main) { onPermissionsRequired() }
                        return@launch
                    }
                    val websiteDao = db.websiteUsageLimitDao()
                    val clean = WebsiteBlocker.normalizeRule(domain)
                    if (clean.isEmpty()) return@launch
                    websiteDao.getAllStatic()
                        .filter { existing ->
                            existing.domain != clean &&
                                WebsiteBlocker.normalizeRule(existing.domain) == clean
                        }
                        .forEach { websiteDao.delete(it) }
                    websiteDao.insert(
                        WebsiteUsageLimit(
                            domain = clean,
                            dailyLimitMinutes = minutes,
                            isEnabled = true,
                            lockMode = lockMode,
                            lockPasswordHash = null,
                            lockUntilTimestamp = lockUntil
                        )
                    )
                    blockingSessionManager.checkAndEnforce()
                    withContext(Dispatchers.Main) {
                        sites = sites.filterNot {
                            WebsiteBlocker.normalizeRule(it.domain) == clean
                        } + WebsiteLimitUi(
                            clean,
                            minutes,
                            true,
                            0L,
                            lockMode,
                            null,
                            lockUntil
                        )
                        showAddDialog = false
                    }
                }

                }
                val normalizedTarget = WebsiteBlocker.normalizeRule(domain)
                val targetAlreadyConfigured = sites.any { WebsiteBlocker.normalizeRule(it.domain) == normalizedTarget }
                val isCreatingLimit = normalizedTarget.isNotBlank() && minutes > 0 && !targetAlreadyConfigured
                if (isCreatingLimit && MonetizationPolicy.requiresExtraUsageLimitAd(sites.size, targetAlreadyConfigured)) {
                    RewardedGateCoordinator.launch(
                        context = context,
                        requiredAds = 1,
                        title = "Adicionar mais um site",
                        description = "Assista a 1 anúncio para adicionar este site ao limite diário.",
                        action = monetizedAction
                    )
                } else {
                    monetizedAction()
                }
            }
        )
    }

    if (showEditDialog && selectedSite != null) {
        EditWebsiteLimitDialog(site = selectedSite!!, permissionsMissing = permissionsMissing, onDismiss = { showEditDialog = false }, onSave = { minutes, enabled, lockMode, _, lockUntil ->
            val siteToEdit = selectedSite ?: return@EditWebsiteLimitDialog
            scope.launch(Dispatchers.IO) {
                if (!ProtectionPermissionGate.read(context).isReady) {
                    withContext(Dispatchers.Main) { onPermissionsRequired() }
                    return@launch
                }
                val websiteDao = db.websiteUsageLimitDao()
                val normalizedDomain = WebsiteBlocker.normalizeRule(siteToEdit.domain)
                if (normalizedDomain.isEmpty()) return@launch
                if (normalizedDomain != siteToEdit.domain) {
                    websiteDao.getAllStatic()
                        .firstOrNull { it.domain == siteToEdit.domain }
                        ?.let { websiteDao.delete(it) }
                }
                websiteDao.insert(
                    WebsiteUsageLimit(
                        domain = normalizedDomain,
                        dailyLimitMinutes = minutes,
                        isEnabled = enabled,
                        lockMode = lockMode,
                        lockPasswordHash = null,
                        lockUntilTimestamp = lockUntil
                    )
                )
                blockingSessionManager.checkAndEnforce()
                withContext(Dispatchers.Main) {
                    sites = sites.map {
                        if (it.domain == siteToEdit.domain) {
                            it.copy(
                                domain = normalizedDomain,
                                dailyLimitMinutes = minutes,
                                isEnabled = enabled,
                                lockMode = lockMode,
                                lockPasswordHash = null,
                                lockUntilTimestamp = lockUntil
                            )
                        } else {
                            it
                        }
                    }
                    showEditDialog = false
                }
            }
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
