package com.focusguard.ui.compose.screens

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.focusguard.R
import com.focusguard.data.PredefinedApps
import com.focusguard.data.PredefinedWebsites
import com.focusguard.database.AppUsageLimit
import com.focusguard.database.WebsiteUsageLimit
import com.focusguard.manager.BlockingSessionManager
import com.focusguard.monetization.MonetizationPolicy
import com.focusguard.monetization.RewardedGateCoordinator
import com.focusguard.security.AuthManager
import com.focusguard.security.DeactivationCredentialManager
import com.focusguard.security.MasterCredentialPolicy
import com.focusguard.security.ProtectionPermissionGate
import com.focusguard.ui.MasterPasswordActivity
import com.focusguard.ui.compose.components.limits.UsageLimitItem
import com.focusguard.ui.compose.components.limits.WebsiteLimitItem
import com.focusguard.ui.compose.rememberAppDatabase
import com.focusguard.ui.compose.theme.*
import com.focusguard.utils.UsageLimitBehaviorPolicy
import com.focusguard.utils.UsageLimitPauseStateStore
import com.focusguard.utils.WebsiteBlocker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Stable
class AppLimitsTabState {
    internal val apps = mutableStateOf<List<UsageLimitAppUi>>(emptyList())
    internal val socialPackages = mutableStateOf<Set<String>>(emptySet())
    internal val searchQuery = mutableStateOf("")
    internal val isLoading = mutableStateOf(true)
    internal var hasLoaded = false
}

@Stable
class WebsiteLimitsSharedState {
    internal val allConfiguredCount = mutableIntStateOf(0)
}

@Stable
class WebsiteLimitsTabState(
    internal val shared: WebsiteLimitsSharedState
) {
    internal val sites = mutableStateOf<List<WebsiteLimitUi>>(emptyList())
    internal val isLoading = mutableStateOf(true)
    internal var hasLoaded = false
}

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
    val appLimitsState = remember { AppLimitsTabState() }
    val websiteLimitsSharedState = remember { WebsiteLimitsSharedState() }
    val websiteLimitsState = remember(websiteLimitsSharedState) {
        WebsiteLimitsTabState(websiteLimitsSharedState)
    }
    val keywordLimitsState = remember(websiteLimitsSharedState) {
        WebsiteLimitsTabState(websiteLimitsSharedState)
    }
    val credentialManager = remember(context) { DeactivationCredentialManager(context) }
    val hasMasterCredential = remember(credentialRevision) { credentialManager.hasCredential() }
    val masterPasswordLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { credentialRevision++ }
    val openMasterPassword: () -> Unit = {
        masterPasswordLauncher.launch(MasterPasswordActivity.createIntent(context))
    }

    DisposableEffect(lifecycleOwner) {
        var initialResumeObserved = false
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (initialResumeObserved) {
                    permissionResumeKey++
                } else {
                    initialResumeObserved = true
                }
            }
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
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = AccentCyan
            ) {
                UsageLimitTab(
                    selected = selectedTab == 0,
                    text = stringResource(R.string.sessions_category_apps),
                    onClick = { selectedTab = 0 }
                )
                UsageLimitTab(
                    selected = selectedTab == 1,
                    text = stringResource(R.string.block_targets_tab_sites),
                    onClick = { selectedTab = 1 }
                )
                UsageLimitTab(
                    selected = selectedTab == 2,
                    text = stringResource(R.string.limits_tab_keywords),
                    onClick = { selectedTab = 2 }
                )
            }

            when (selectedTab) {
                0 -> AppLimitsTab(
                    permissionsMissing = false,
                    authManager = authManager,
                    hasMasterCredential = hasMasterCredential,
                    onConfigureMasterPassword = openMasterPassword,
                    onPermissionsRequired = onPermissionsRequired,
                    onWebsiteLimitCreated = {
                        websiteLimitsState.hasLoaded = false
                        websiteLimitsState.isLoading.value = true
                    },
                    state = appLimitsState,
                    usageRefreshKey = permissionResumeKey
                )
                1 -> WebsiteLimitsTab(
                    permissionsMissing = false,
                    authManager = authManager,
                    hasMasterCredential = hasMasterCredential,
                    onConfigureMasterPassword = openMasterPassword,
                    onPermissionsRequired = onPermissionsRequired,
                    keywordMode = false,
                    state = websiteLimitsState,
                    usageRefreshKey = permissionResumeKey
                )
                else -> WebsiteLimitsTab(
                    permissionsMissing = false,
                    authManager = authManager,
                    hasMasterCredential = hasMasterCredential,
                    onConfigureMasterPassword = openMasterPassword,
                    onPermissionsRequired = onPermissionsRequired,
                    keywordMode = true,
                    state = keywordLimitsState,
                    usageRefreshKey = permissionResumeKey
                )
            }
        }
    }
}

@Composable
private fun UsageLimitTab(selected: Boolean, text: String, onClick: () -> Unit) {
    Tab(
        selected = selected,
        onClick = onClick,
        text = {
            Text(
                text,
                color = if (selected) AccentCyan else TextHint,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            )
        }
    )
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
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
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
                    color = TextSecondary
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
    onPermissionsRequired: () -> Unit,
    onWebsiteLimitCreated: () -> Unit,
    state: AppLimitsTabState,
    /** Muda a cada volta à tela: o uso de hoje é relido sem recarregar a lista. */
    usageRefreshKey: Int = 0
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val db = rememberAppDatabase()
    val blockingSessionManager = remember(context) { BlockingSessionManager.getInstance(context) }
    var apps by state.apps
    var socialPackages by state.socialPackages
    var searchQuery by state.searchQuery
    var isLoading by state.isLoading
    var selectedApp by remember { mutableStateOf<UsageLimitAppUi?>(null) }
    var showDialog by remember { mutableStateOf(false) }
    var showMasterCredentialConfirm by remember { mutableStateOf(false) }
    var showTimeLockedAlert by remember { mutableStateOf(false) }
    var showSafetyModeAlert by remember { mutableStateOf(false) }
    var showCredentialMissingAlert by remember { mutableStateOf(false) }
    val credentialManager = remember(context) { DeactivationCredentialManager(context) }

    fun limitGate(limit: AppUsageLimit?): MasterCredentialPolicy.MutationGate =
        if (limit == null) {
            MasterCredentialPolicy.MutationGate.ALLOWED
        } else {
            limitMutationGate(
                authManager = authManager,
                hasMasterCredential = credentialManager.hasCredential(),
                lockMode = limit.lockMode,
                lockUntilTimestamp = limit.lockUntilTimestamp,
                lockAfter = limit.lockAfter
            )
        }

    fun showRefusal(gate: MasterCredentialPolicy.MutationGate) {
        when (gate) {
            MasterCredentialPolicy.MutationGate.BLOCKED_BY_TIME_HARDENING ->
                showTimeLockedAlert = true
            MasterCredentialPolicy.MutationGate.BLOCKED_BY_SAFETY_MODE ->
                showSafetyModeAlert = true
            MasterCredentialPolicy.MutationGate.MASTER_CREDENTIAL_NOT_CONFIGURED ->
                showCredentialMissingAlert = true
            MasterCredentialPolicy.MutationGate.MASTER_CREDENTIAL_REQUIRED,
            MasterCredentialPolicy.MutationGate.ALLOWED -> Unit
        }
    }

    fun requestLimitEdit(app: UsageLimitAppUi) {
        // A search-result tap leaves the search field focused in the Activity.
        // End that input session before a modal takes over the window, so the
        // search IME cannot compete with the editor's numeric fields or restart
        // when the modal closes. Keep the query so the filtered list is preserved.
        focusManager.clearFocus(force = true)
        keyboardController?.hide()

        scope.launch {
            // A lista foi lida ao abrir a tela; a trava e o fim da regra valem como
            // estão gravados agora (o relógio pode ter sido mudado nesse meio-tempo).
            val stored = blockingSessionManager.currentAppLimit(db, app.packageName)
            val current = app.withStoredLimit(stored)
            if (current != app) {
                apps = apps.map { if (it.packageName == current.packageName) current else it }
            }
            when (val gate = limitGate(stored)) {
                MasterCredentialPolicy.MutationGate.MASTER_CREDENTIAL_REQUIRED -> {
                    selectedApp = current
                    showMasterCredentialConfirm = true
                }
                MasterCredentialPolicy.MutationGate.ALLOWED -> {
                    selectedApp = current
                    showDialog = true
                }
                else -> showRefusal(gate)
            }
        }
    }

    LaunchedEffect(usageRefreshKey) {
        if (usageRefreshKey == 0 || !state.hasLoaded) return@LaunchedEffect
        val readFrom = apps.associateBy { it.packageName }
        val packages = readFrom.keys
        // Na volta à tela relê também os limites: o relógio mudado nas Configurações
        // desloca o fim da regra e a trava gravados.
        val (storedLimits, refreshed) = withContext(Dispatchers.IO) {
            blockingSessionManager.syncManualClockCompensation()
            val limits = db.appUsageLimitDao().getAllStatic()
            limits to loadUsageToday(
                context = context,
                packages = packages,
                limits = limits,
                nowMillis = System.currentTimeMillis()
            )
        }
        val limitByPackage = storedLimits.associateBy { it.packageName }
        apps = apps.map { app ->
            // Um item salvo enquanto a releitura corria já está mais novo que ela.
            if (readFrom[app.packageName] != app) return@map app
            app.withStoredLimit(limitByPackage[app.packageName])
                .copy(usageMs = refreshed[app.packageName] ?: 0L)
        }
    }

    LaunchedEffect(state) {
        if (state.hasLoaded) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val resolveInfos = pm.queryIntentActivities(intent, 0)
            val limitDao = db.appUsageLimitDao()
            val existingLimits = limitDao.getAllStatic().associateBy { it.packageName }
            val now = System.currentTimeMillis()
            val usageToday = loadUsageToday(
                context = context,
                packages = resolveInfos.map { it.activityInfo.packageName },
                limits = existingLimits.values,
                nowMillis = now
            )
            val discoveredSocialPackages = PredefinedApps.PREVENTIVE_APPS
                .asSequence()
                .filter { it.category.equals("Redes Sociais", ignoreCase = true) }
                .mapTo(linkedSetOf()) { it.packageName }

            val installedApps = resolveInfos.mapNotNull { info ->
                val packageName = info.activityInfo.packageName
                if (packageName == context.packageName) return@mapNotNull null
                if (
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                    info.activityInfo.applicationInfo.category == ApplicationInfo.CATEGORY_SOCIAL
                ) {
                    discoveredSocialPackages += packageName
                }
                val limit = existingLimits[packageName]
                val displayedUsageMillis = usageToday[packageName] ?: 0L
                UsageLimitAppUi(
                    packageName = packageName,
                    appName = info.loadLabel(pm).toString(),
                    currentLimitMinutes = limit?.dailyLimitMinutes,
                    isEnabled = limit?.isEnabled ?: false,
                    usageMs = displayedUsageMillis,
                    lockMode = limit?.lockMode ?: "NONE",
                    lockPasswordHash = limit?.lockPasswordHash,
                    lockUntilTimestamp = limit?.lockUntilTimestamp,
                    lockAfter = limit?.lockAfter,
                    heldUntil = limit?.heldUntilNow()
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
                        lockUntilTimestamp = limit?.lockUntilTimestamp,
                        lockAfter = limit?.lockAfter,
                        heldUntil = limit?.heldUntilNow()
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
                        lockUntilTimestamp = limit.lockUntilTimestamp,
                        lockAfter = limit.lockAfter,
                        heldUntil = limit.heldUntilNow()
                    )
                }
                .toList()
            val loadedApps = (installedApps + absentKnownApps + absentConfiguredApps)
                .distinctBy { it.packageName }

            withContext(Dispatchers.Main) {
                apps = loadedApps
                socialPackages = discoveredSocialPackages
                isLoading = false
                state.hasLoaded = true
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = {
                Text(stringResource(R.string.limits_search_placeholder), color = TextHint)
            },
            leadingIcon = { Icon(Icons.Default.Search, null, tint = TextHint) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.cancel),
                            tint = TextHint
                        )
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AccentCyan,
                unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
                focusedTextColor = MaterialTheme.colorScheme.onBackground
            ),
            shape = RoundedCornerShape(16.dp),
            singleLine = true
        )

        if (isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AccentCyan)
            }
        } else {
            val filtered = filteredApps(apps, searchQuery)
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                if (searchQuery.isNotBlank()) {
                    if (filtered.isNotEmpty()) {
                        item {
                            UsageLimitSectionHeader(
                                stringResource(R.string.limits_search_results_section)
                            )
                        }
                        items(filtered.sortedBy { it.appName.lowercase() }, key = { "search_${it.packageName}" }) { app ->
                            UsageLimitItem(
                                app,
                                isActive = app.currentLimitMinutes != null && app.isEnabled
                            ) { requestLimitEdit(app) }
                        }
                    }
                } else {
                    val sections = buildAppLimitSections(filtered, socialPackages)
                    if (sections.topUsed.isNotEmpty()) {
                        item {
                            UsageLimitSectionHeader(
                                stringResource(R.string.limits_top_used_section),
                                accent = true
                            )
                        }
                        items(sections.topUsed, key = { "top_${it.packageName}" }) { app ->
                            UsageLimitItem(
                                app,
                                isActive = app.currentLimitMinutes != null && app.isEnabled
                            ) { requestLimitEdit(app) }
                        }
                    }
                    if (sections.social.isNotEmpty()) {
                        item {
                            UsageLimitSectionHeader(stringResource(R.string.limits_social_section))
                        }
                        items(sections.social, key = { "social_${it.packageName}" }) { app ->
                            UsageLimitItem(
                                app,
                                isActive = app.currentLimitMinutes != null && app.isEnabled
                            ) { requestLimitEdit(app) }
                        }
                    }
                    if (sections.other.isNotEmpty()) {
                        item {
                            UsageLimitSectionHeader(stringResource(R.string.limits_other_apps_section))
                        }
                        items(sections.other, key = { "other_${it.packageName}" }) { app ->
                            UsageLimitItem(
                                app,
                                isActive = app.currentLimitMinutes != null && app.isEnabled
                            ) { requestLimitEdit(app) }
                        }
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
            onSave = { minutes, enabled, lockMode, _, lockUntil, companionDomains, lockAfter ->
                val appToSave = selectedApp ?: return@AppLimitRedesignedSheet
                val monetizedAction: () -> Unit = {
                    scope.launch(Dispatchers.IO) {
                        if (!ProtectionPermissionGate.read(context).isReady) {
                            withContext(Dispatchers.Main) { onPermissionsRequired() }
                            return@launch
                        }
                        val limitDao = db.appUsageLimitDao()
                        // O editor pode ter ficado aberto até a trava começar (ou até o
                        // relógio ser mudado): só altera o que ainda pode ser alterado.
                        val refusal = limitGate(
                            blockingSessionManager.currentAppLimit(db, appToSave.packageName)
                        ).takeIf { it.refusesMutation() }
                        if (refusal != null) {
                            withContext(Dispatchers.Main) {
                                showDialog = false
                                showRefusal(refusal)
                            }
                            return@launch
                        }
                        var companionWebsiteCreated = false
                        val updated = if (minutes != null && minutes > 0) {
                            // AppUsageLimitMeter conta o primeiro plano a partir de
                            // createdAt: um limite novo começa do zero aqui, e a edição
                            // mantém a ativação original (o DAO preserva createdAt).
                            val activationTime = System.currentTimeMillis()
                            limitDao.insert(
                                AppUsageLimit(
                                    packageName = appToSave.packageName,
                                    appName = appToSave.appName,
                                    dailyLimitMinutes = minutes,
                                    isEnabled = enabled,
                                    lockMode = lockMode,
                                    lockPasswordHash = null,
                                    lockUntilTimestamp = lockUntil,
                                    createdAt = activationTime,
                                    preventOpeningAfterLimit = true,
                                    unlockWithPassword = lockMode.equals("PASSWORD", ignoreCase = true),
                                    lockAfter = lockAfter
                                )
                            )

                            if (companionDomains.isNotEmpty()) {
                                val websiteDao = db.websiteUsageLimitDao()
                                val existingWebsiteRules = websiteDao.getAllStatic()
                                    .mapTo(mutableSetOf()) {
                                        WebsiteBlocker.normalizeRule(it.domain)
                                    }
                                companionDomains
                                    .map(WebsiteBlocker::normalizeRule)
                                    .filter(String::isNotEmpty)
                                    .distinct()
                                    .forEach { domain ->
                                        if (domain !in existingWebsiteRules) {
                                            val websiteLockMode = when {
                                                UsageLimitBehaviorPolicy.isPauseMode(lockMode) ->
                                                    UsageLimitBehaviorPolicy.pauseModeFor(domain)
                                                UsageLimitBehaviorPolicy
                                                    .isBlockUntilTomorrowMode(lockMode) ->
                                                    UsageLimitBehaviorPolicy
                                                        .blockUntilTomorrowModeFor(domain)
                                                else -> lockMode
                                            }
                                            websiteDao.insert(
                                                WebsiteUsageLimit(
                                                    domain = domain,
                                                    dailyLimitMinutes = minutes,
                                                    isEnabled = enabled,
                                                    lockMode = websiteLockMode,
                                                    lockPasswordHash = null,
                                                    lockUntilTimestamp = lockUntil,
                                                    lockAfter = lockAfter
                                                )
                                            )
                                            existingWebsiteRules += domain
                                            companionWebsiteCreated = true
                                        }
                                    }
                            }

                            appToSave.copy(
                                currentLimitMinutes = minutes,
                                isEnabled = enabled,
                                lockMode = lockMode,
                                lockPasswordHash = null,
                                lockUntilTimestamp = lockUntil,
                                lockAfter = lockAfter
                            )
                        } else {
                            limitDao.getAllStatic()
                                .firstOrNull { it.packageName == appToSave.packageName }
                                ?.let { limitDao.delete(it) }
                            // Sem limite não sobra trava: a de um limite removido
                            // faria o próximo toque no app abrir o aviso de travado.
                            appToSave.copy(
                                currentLimitMinutes = null,
                                isEnabled = false,
                                lockMode = "NONE",
                                lockPasswordHash = null,
                                lockUntilTimestamp = null,
                                lockAfter = null
                            )
                        }
                        // Um limite com mais minutos (ou criado de novo) não herda o
                        // "esgotado" de hoje; se ainda estiver esgotado, volta a valer já.
                        UsageLimitPauseStateStore.clearDailyStateFor(appToSave.packageName)
                        blockingSessionManager.checkAndEnforce()
                        // O número da lista é o mesmo que decide o bloqueio: editar
                        // não zera o uso já contado, e sem limite volta a ser o dia todo.
                        val savedLimit = limitDao.getLimitForPackage(updated.packageName)
                        val savedUsageMillis = loadUsageToday(
                            context = context,
                            packages = listOf(updated.packageName),
                            limits = listOfNotNull(savedLimit),
                            nowMillis = System.currentTimeMillis()
                        )[updated.packageName] ?: 0L
                        withContext(Dispatchers.Main) {
                            val shown = updated.copy(
                                usageMs = savedUsageMillis,
                                heldUntil = savedLimit?.heldUntilNow()
                            )
                            apps = apps.map { if (it.packageName == shown.packageName) shown else it }
                            selectedApp = shown
                            if (companionWebsiteCreated) onWebsiteLimitCreated()
                            showDialog = false
                        }
                    }
                }
                val targetAlreadyConfigured = appToSave.currentLimitMinutes != null
                val isCreatingLimit = minutes != null && minutes > 0 && !targetAlreadyConfigured
                val configuredCount = apps.count { it.currentLimitMinutes != null }
                if (
                    isCreatingLimit &&
                    MonetizationPolicy.requiresExtraUsageLimitAd(configuredCount, targetAlreadyConfigured)
                ) {
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

    LimitMutationAlerts(
        showTimeLocked = showTimeLockedAlert,
        onTimeLockedDismiss = { showTimeLockedAlert = false },
        showSafetyMode = showSafetyModeAlert,
        onSafetyModeDismiss = { showSafetyModeAlert = false },
        showCredentialMissing = showCredentialMissingAlert,
        onCredentialMissingDismiss = { showCredentialMissingAlert = false },
        onConfigureMasterPassword = onConfigureMasterPassword
    )
}

/**
 * O limite de app como está gravado agora, depois de aplicar a compensação de um
 * relógio mudado à mão. A lista da tela foi lida ao abrir: decidir pela trava (ou
 * pelo fim da regra) dela deixava editar ou remover um limite que já tinha travado.
 */
private suspend fun BlockingSessionManager.currentAppLimit(
    db: com.focusguard.database.AppDatabase,
    packageName: String
): AppUsageLimit? = withContext(Dispatchers.IO) {
    syncManualClockCompensation()
    db.appUsageLimitDao().getLimitForPackage(packageName)
}

/** O mesmo que [currentAppLimit] para um limite de site ou palavra. */
private suspend fun BlockingSessionManager.currentWebsiteLimit(
    db: com.focusguard.database.AppDatabase,
    normalizedRule: String
): WebsiteUsageLimit? = withContext(Dispatchers.IO) {
    syncManualClockCompensation()
    db.websiteUsageLimitDao().getAllStatic()
        .firstOrNull { WebsiteBlocker.normalizeRule(it.domain) == normalizedRule }
}

private fun UsageLimitAppUi.withStoredLimit(limit: AppUsageLimit?): UsageLimitAppUi = copy(
    currentLimitMinutes = limit?.dailyLimitMinutes,
    isEnabled = limit?.isEnabled ?: false,
    lockMode = limit?.lockMode ?: "NONE",
    lockPasswordHash = limit?.lockPasswordHash,
    lockUntilTimestamp = limit?.lockUntilTimestamp,
    lockAfter = limit?.lockAfter,
    heldUntil = limit?.heldUntilNow()
)

/** Uma regra encerrada não aparece marcada entre os atalhos. */
private fun WebsiteLimitUi.isInForceNow(): Boolean = UsageLimitBehaviorPolicy.isInForce(
    isEnabled = isEnabled,
    lockMode = lockMode,
    ruleEndMillis = lockUntilTimestamp,
    nowMillis = System.currentTimeMillis()
)

/** Ver [UsageLimitAppUi.heldUntil]. */
private fun AppUsageLimit.heldUntilNow(): Long? = UsageLimitPauseStateStore.heldUntil(
    lockMode = lockMode,
    ruleEndMillis = lockUntilTimestamp,
    nowMillis = System.currentTimeMillis()
)

private fun WebsiteLimitUi.withStoredLimit(limit: WebsiteUsageLimit): WebsiteLimitUi = copy(
    dailyLimitMinutes = limit.dailyLimitMinutes,
    isEnabled = limit.isEnabled,
    lockMode = limit.lockMode,
    lockPasswordHash = limit.lockPasswordHash,
    lockUntilTimestamp = limit.lockUntilTimestamp,
    lockAfter = limit.lockAfter
)

private fun limitMutationGate(
    authManager: AuthManager,
    hasMasterCredential: Boolean,
    lockMode: String,
    lockUntilTimestamp: Long?,
    lockAfter: Long?
): MasterCredentialPolicy.MutationGate = MasterCredentialPolicy.evaluateLimitMutation(
    lockMode = lockMode,
    lockUntilTimestamp = lockUntilTimestamp,
    safetyModeEnabled = authManager.isSafetyModeEnabled(),
    hasMasterCredential = hasMasterCredential,
    masterCredentialVerified = false,
    lockAfter = lockAfter
)

/** Recusa salvar ou remover; o pedido antigo de senha mestra já foi confirmado antes. */
private fun MasterCredentialPolicy.MutationGate.refusesMutation(): Boolean =
    this != MasterCredentialPolicy.MutationGate.ALLOWED &&
        this != MasterCredentialPolicy.MutationGate.MASTER_CREDENTIAL_REQUIRED

private data class AppLimitSections(
    val topUsed: List<UsageLimitAppUi>,
    val social: List<UsageLimitAppUi>,
    val other: List<UsageLimitAppUi>
)

/**
 * Uso de hoje mostrado na lista. Com limite ativo é o mesmo número que decide o
 * bloqueio (só desde a criação do limite e sem o tempo em que outra camada segurava
 * o app); sem limite, todo o primeiro plano desde a meia-noite. Os dois vêm do
 * mesmo coletor de eventos: o agregado do Android começa o dia antes da meia-noite.
 */
private suspend fun loadUsageToday(
    context: Context,
    packages: Collection<String>,
    limits: Collection<com.focusguard.database.AppUsageLimit>,
    nowMillis: Long
): Map<String, Long> {
    val manager = context.getSystemService(Context.USAGE_STATS_SERVICE)
        as? android.app.usage.UsageStatsManager ?: return emptyMap()
    val interactive = (context.getSystemService(Context.POWER_SERVICE)
        as? android.os.PowerManager)?.isInteractive == true
    val totals = runCatching {
        com.focusguard.utils.AppUsageLimitMeter.foregroundTodayByPackage(
            usageStatsManager = manager,
            packages = packages,
            nowMillis = nowMillis,
            isDeviceInteractive = interactive
        )
    }.getOrNull().orEmpty()
    val limited = runCatching {
        BlockingSessionManager.getInstance(context).appLimitUsageMillis(
            limits = limits.filter { it.isEnabled },
            nowMillis = nowMillis
        )
    }.getOrDefault(emptyMap())
    return totals + limited
}

private suspend fun loadWebsiteUsageToday(
    db: com.focusguard.database.AppDatabase,
    rules: Collection<String>
): Map<String, Long> {
    val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        .format(java.util.Date())
    return com.focusguard.utils.WebsiteUsageLimitPolicy.aggregateUsageByRule(
        usageByIdentifier = db.dailyUsageStatDao()
            .getStatsForDateStatic(today)
            .map { it.identifier to it.timeSpentMs },
        configuredRules = rules
    )
}

private fun buildAppLimitSections(
    apps: List<UsageLimitAppUi>,
    socialPackages: Set<String>
): AppLimitSections {
    val byUsage = compareByDescending<UsageLimitAppUi> { it.usageMs }
        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.appName }
    val topUsed = apps.sortedWith(byUsage).take(3)
    val topPackages = topUsed.mapTo(hashSetOf()) { it.packageName }
    val social = apps
        .asSequence()
        .filter { it.packageName !in topPackages && it.packageName in socialPackages }
        .sortedWith(byUsage)
        .toList()
    val socialSet = social.mapTo(hashSetOf()) { it.packageName }
    val other = apps
        .filter { it.packageName !in topPackages && it.packageName !in socialSet }
        .sortedBy { it.appName.lowercase() }
    return AppLimitSections(topUsed, social, other)
}

@Composable
private fun UsageLimitSectionHeader(text: String, accent: Boolean = false) {
    Text(
        text,
        color = if (accent) AccentCyan else TextSecondary,
        fontSize = if (accent) 16.sp else 14.sp,
        fontWeight = FontWeight.ExtraBold,
        modifier = Modifier.padding(top = 18.dp, bottom = 8.dp)
    )
}

@Composable
fun WebsiteLimitsTab(
    permissionsMissing: Boolean,
    authManager: AuthManager,
    hasMasterCredential: Boolean,
    onConfigureMasterPassword: () -> Unit,
    onPermissionsRequired: () -> Unit,
    keywordMode: Boolean = false,
    state: WebsiteLimitsTabState,
    /** Muda a cada volta à tela: o uso de hoje é relido sem recarregar a lista. */
    usageRefreshKey: Int = 0
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = rememberAppDatabase()
    val blockingSessionManager = remember(context) { BlockingSessionManager.getInstance(context) }
    var sites by state.sites
    var allConfiguredCount by state.shared.allConfiguredCount
    var isLoading by state.isLoading
    var showAddDialog by remember { mutableStateOf(false) }
    var initialRuleForAdd by remember { mutableStateOf<String?>(null) }
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
    var siteInput by remember { mutableStateOf("") }
    var invalidSiteInput by remember { mutableStateOf(false) }
    val credentialManager = remember(context) { DeactivationCredentialManager(context) }

    fun siteGate(limit: WebsiteUsageLimit?): MasterCredentialPolicy.MutationGate =
        if (limit == null) {
            MasterCredentialPolicy.MutationGate.ALLOWED
        } else {
            limitMutationGate(
                authManager = authManager,
                hasMasterCredential = credentialManager.hasCredential(),
                lockMode = limit.lockMode,
                lockUntilTimestamp = limit.lockUntilTimestamp,
                lockAfter = limit.lockAfter
            )
        }

    fun showRefusal(gate: MasterCredentialPolicy.MutationGate) {
        when (gate) {
            MasterCredentialPolicy.MutationGate.BLOCKED_BY_TIME_HARDENING ->
                showTimeLockedAlert = true
            MasterCredentialPolicy.MutationGate.BLOCKED_BY_SAFETY_MODE ->
                showSafetyModeAlert = true
            MasterCredentialPolicy.MutationGate.MASTER_CREDENTIAL_NOT_CONFIGURED ->
                showCredentialMissingAlert = true
            MasterCredentialPolicy.MutationGate.MASTER_CREDENTIAL_REQUIRED,
            MasterCredentialPolicy.MutationGate.ALLOWED -> Unit
        }
    }

    /**
     * Decide pela trava gravada agora (não pela lista lida ao abrir a tela) e passa
     * adiante o limite com os dados atuais.
     */
    fun requestSiteMutation(
        site: WebsiteLimitUi,
        promptRes: Int,
        action: (WebsiteLimitUi) -> Unit
    ) {
        val normalized = WebsiteBlocker.normalizeRule(site.domain)
        scope.launch {
            val stored = blockingSessionManager.currentWebsiteLimit(db, normalized)
            val current = stored?.let { site.withStoredLimit(it) } ?: site
            if (current != site) {
                sites = sites.map {
                    if (WebsiteBlocker.normalizeRule(it.domain) == normalized) current else it
                }
            }
            when (val gate = siteGate(stored)) {
                MasterCredentialPolicy.MutationGate.MASTER_CREDENTIAL_REQUIRED -> {
                    selectedSite = current
                    masterCredentialPromptRes = promptRes
                    pendingAction = { action(current) }
                    showMasterCredentialConfirm = true
                }
                MasterCredentialPolicy.MutationGate.ALLOWED -> action(current)
                else -> showRefusal(gate)
            }
        }
    }

    fun requestRuleEditor(rule: String) {
        val normalized = WebsiteBlocker.normalizeRule(rule)
        if (normalized.isEmpty()) return
        val configured = sites.firstOrNull {
            WebsiteBlocker.normalizeRule(it.domain) == normalized
        }
        if (configured == null) {
            initialRuleForAdd = normalized
            showAddDialog = true
        } else {
            requestSiteMutation(
                configured,
                R.string.master_credential_required_to_change_limit
            ) { current ->
                selectedSite = current
                showEditDialog = true
            }
        }
    }

    fun requestSiteDelete(site: WebsiteLimitUi) {
        val normalized = WebsiteBlocker.normalizeRule(site.domain)
        if (normalized.isEmpty()) return
        requestSiteMutation(
            site,
            R.string.master_credential_required_to_remove_limit
        ) { _ ->
            scope.launch(Dispatchers.IO) {
                val dao = db.websiteUsageLimitDao()
                dao.getAllStatic()
                    .firstOrNull {
                        WebsiteBlocker.normalizeRule(it.domain) == normalized
                    }
                    ?.let { dao.delete(it) }
                UsageLimitPauseStateStore.clearDailyStateFor(normalized)
                blockingSessionManager.checkAndEnforce()
                withContext(Dispatchers.Main) {
                    sites = sites.filterNot {
                        WebsiteBlocker.normalizeRule(it.domain) == normalized
                    }
                    allConfiguredCount = (allConfiguredCount - 1).coerceAtLeast(0)
                }
            }
        }
    }

    LaunchedEffect(usageRefreshKey) {
        if (usageRefreshKey == 0 || !state.hasLoaded) return@LaunchedEffect
        val readFrom = sites.associateBy { WebsiteBlocker.normalizeRule(it.domain) }
        val rules = sites.map { it.domain }
        val (storedLimits, refreshed) = withContext(Dispatchers.IO) {
            blockingSessionManager.syncManualClockCompensation()
            db.websiteUsageLimitDao().getAllStatic() to loadWebsiteUsageToday(db, rules)
        }
        val limitByRule = storedLimits.associateBy { WebsiteBlocker.normalizeRule(it.domain) }
        sites = sites.map { site ->
            val rule = WebsiteBlocker.normalizeRule(site.domain)
            // Um item salvo enquanto a releitura corria já está mais novo que ela.
            if (readFrom[rule] != site) return@map site
            val stored = limitByRule[rule]
            (stored?.let { site.withStoredLimit(it) } ?: site)
                .copy(usageMs = refreshed[site.domain] ?: 0L)
        }
    }

    LaunchedEffect(state) {
        if (state.hasLoaded) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val allLimits = db.websiteUsageLimitDao().getAllStatic()
            val usageStats = loadWebsiteUsageToday(db, allLimits.map { it.domain })
            val loaded = allLimits
                .filter {
                    WebsiteBlocker.isKeywordRule(WebsiteBlocker.normalizeRule(it.domain)) == keywordMode
                }
                .map {
                    val normalized = WebsiteBlocker.normalizeRule(it.domain)
                    WebsiteLimitUi(
                        domain = normalized,
                        dailyLimitMinutes = it.dailyLimitMinutes,
                        isEnabled = it.isEnabled,
                        usageMs = usageStats[normalized] ?: 0L,
                        lockMode = it.lockMode,
                        lockPasswordHash = it.lockPasswordHash,
                        lockUntilTimestamp = it.lockUntilTimestamp,
                        lockAfter = it.lockAfter
                    )
                }
            withContext(Dispatchers.Main) {
                sites = loaded
                allConfiguredCount = allLimits.size
                isLoading = false
                state.hasLoaded = true
            }
        }
    }

    val keywordPresetRules = remember {
        PredefinedWebsites.PORNOGRAPHY_KEYWORDS
            .map { WebsiteBlocker.normalizeRule("keyword:$it") }
            .filter(String::isNotEmpty)
    }
    val orderedKeywordRules = remember(keywordPresetRules, sites) {
        (keywordPresetRules + sites.map { WebsiteBlocker.normalizeRule(it.domain) })
            .filter(String::isNotEmpty)
            .distinct()
    }
    val configuredByRule = remember(sites) {
        sites.associateBy { WebsiteBlocker.normalizeRule(it.domain) }
    }

    if (!keywordMode) {
        if (isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AccentCyan)
            }
        } else {
            // Keep this catalogue on the exact same visual primitives as the
            // passwordless timed-block picker. Usage Limits only changes what a
            // tap does: it opens the daily-limit editor instead of selecting a
            // session target.
            val pornographyRule = WebsiteBlocker.normalizeRule(
                PredefinedWebsites.PORNOGRAPHY_RULE
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    PornographyPresetRow(
                        selected = configuredByRule[pornographyRule]?.isInForceNow() == true,
                        onToggle = { requestRuleEditor(pornographyRule) }
                    )
                }
                item { SectionLabel(stringResource(R.string.protection_sites_add_new)) }
                item {
                    RuleInputRow(
                        value = siteInput,
                        onValueChange = {
                            siteInput = it
                            invalidSiteInput = false
                        },
                        placeholder = stringResource(R.string.block_targets_site_placeholder),
                        icon = Icons.Default.Public,
                        isError = invalidSiteInput,
                        onAdd = {
                            val domain = WebsiteBlocker.extractDomain(siteInput)
                            if (domain.isEmpty()) {
                                invalidSiteInput = true
                            } else {
                                siteInput = ""
                                invalidSiteInput = false
                                requestRuleEditor(domain)
                            }
                        }
                    )
                }
                item {
                    Text(
                        text = if (invalidSiteInput) {
                            stringResource(R.string.block_targets_site_invalid)
                        } else {
                            stringResource(R.string.block_targets_site_helper)
                        },
                        color = if (invalidSiteInput) DangerRed else TextHint,
                        fontSize = 12.sp
                    )
                }
                item { SectionLabel(stringResource(R.string.protection_sites_common)) }
                items(
                    PredefinedWebsites.ALL_PRESETS,
                    key = { "limit_preset_${it.domain}" }
                ) { website ->
                    val normalized = WebsiteBlocker.normalizeRule(website.domain)
                    WebsitePresetRow(
                        website = website,
                        selected = configuredByRule[normalized]?.isInForceNow() == true,
                        onToggle = { requestRuleEditor(normalized) }
                    )
                }

                item {
                    SelectedSectionHeader(stringResource(R.string.protection_sites_selected))
                }
                if (sites.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.protection_sites_none),
                            color = TextHint,
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
                    }
                } else {
                    items(
                        sites.sortedBy { WebsiteBlocker.displayRule(it.domain).lowercase() },
                        key = { "configured_${WebsiteBlocker.normalizeRule(it.domain)}" }
                    ) { configured ->
                        WebsiteLimitItem(
                            site = configured,
                            onClick = { requestRuleEditor(configured.domain) },
                            onDelete = { requestSiteDelete(configured) }
                        )
                    }
                }
            }
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            Button(
                onClick = {
                    initialRuleForAdd = null
                    showAddDialog = true
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Default.Add, null, tint = DarkBg, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.limits_add_keyword_btn),
                    color = DarkBg,
                    fontWeight = FontWeight.Bold
                )
            }

            if (isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AccentCyan)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    item {
                        UsageLimitSectionHeader(
                            stringResource(R.string.limits_common_keywords_section),
                            accent = true
                        )
                    }
                    items(orderedKeywordRules, key = { "rule_$it" }) { rule ->
                        val configured = configuredByRule[rule]
                        if (configured != null) {
                            WebsiteLimitItem(
                                site = configured,
                                onClick = { requestRuleEditor(rule) },
                                onDelete = { requestSiteDelete(configured) }
                            )
                        } else {
                            UsageLimitPresetRow(
                                rule = rule,
                                keywordMode = true,
                                onClick = {
                                    initialRuleForAdd = rule
                                    showAddDialog = true
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddUsageLimitRuleDialog(
            initialRule = initialRuleForAdd,
            keywordMode = keywordMode,
            permissionsMissing = permissionsMissing,
            hasMasterCredential = hasMasterCredential,
            onConfigureMasterPassword = onConfigureMasterPassword,
            onDismiss = {
                showAddDialog = false
                initialRuleForAdd = null
            },
            onSave = { rule, minutes, lockMode, _, lockUntil, lockAfter ->
                val clean = WebsiteBlocker.normalizeRule(rule)
                if (clean.isEmpty() || WebsiteBlocker.isKeywordRule(clean) != keywordMode) {
                    return@AddUsageLimitRuleDialog
                }
                // Digitar um alvo que já tem limite substitui esse limite: passa pela
                // mesma trava da edição e mantém o prazo das 48 h que ele já tinha.
                val existingSite = sites.firstOrNull {
                    WebsiteBlocker.normalizeRule(it.domain) == clean
                }
                val targetAlreadyConfigured = existingSite != null
                val saveRule: (Long?) -> Unit = { effectiveLockAfter ->
                    scope.launch(Dispatchers.IO) {
                        if (!ProtectionPermissionGate.read(context).isReady) {
                            withContext(Dispatchers.Main) { onPermissionsRequired() }
                            return@launch
                        }
                        val dao = db.websiteUsageLimitDao()
                        dao.getAllStatic()
                            .filter {
                                it.domain != clean && WebsiteBlocker.normalizeRule(it.domain) == clean
                            }
                            .forEach { dao.delete(it) }
                        dao.insert(
                            WebsiteUsageLimit(
                                domain = clean,
                                dailyLimitMinutes = minutes,
                                isEnabled = true,
                                lockMode = lockMode,
                                lockPasswordHash = null,
                                lockUntilTimestamp = lockUntil,
                                lockAfter = effectiveLockAfter
                            )
                        )
                        UsageLimitPauseStateStore.clearDailyStateFor(clean)
                        blockingSessionManager.checkAndEnforce()
                        withContext(Dispatchers.Main) {
                            sites = sites.filterNot {
                                WebsiteBlocker.normalizeRule(it.domain) == clean
                            } + WebsiteLimitUi(
                                domain = clean,
                                dailyLimitMinutes = minutes,
                                isEnabled = true,
                                usageMs = 0L,
                                lockMode = lockMode,
                                lockPasswordHash = null,
                                lockUntilTimestamp = lockUntil,
                                lockAfter = effectiveLockAfter
                            )
                            if (!targetAlreadyConfigured) allConfiguredCount++
                            showAddDialog = false
                            initialRuleForAdd = null
                        }
                    }
                }
                val isCreatingLimit = minutes > 0 && !targetAlreadyConfigured
                val monetizedAction: () -> Unit = { saveRule(lockAfter) }
                if (existingSite != null) {
                    requestSiteMutation(
                        existingSite,
                        R.string.master_credential_required_to_change_limit
                    ) { current -> saveRule(current.lockAfter) }
                } else if (
                    isCreatingLimit &&
                    MonetizationPolicy.requiresExtraUsageLimitAd(
                        allConfiguredCount,
                        targetAlreadyConfigured
                    )
                ) {
                    RewardedGateCoordinator.launch(
                        context = context,
                        requiredAds = 1,
                        title = if (keywordMode) "Adicionar mais uma palavra" else "Adicionar mais um site",
                        description = if (keywordMode) {
                            "Assista a 1 anúncio para adicionar esta palavra ao limite diário."
                        } else {
                            "Assista a 1 anúncio para adicionar este site ao limite diário."
                        },
                        action = monetizedAction
                    )
                } else {
                    monetizedAction()
                }
            }
        )
    }

    if (showEditDialog && selectedSite != null) {
        EditWebsiteLimitDialog(
            site = selectedSite!!,
            permissionsMissing = permissionsMissing,
            onDismiss = { showEditDialog = false },
            onSave = { minutes, enabled, lockMode, _, lockUntil ->
                val siteToEdit = selectedSite ?: return@EditWebsiteLimitDialog
                scope.launch(Dispatchers.IO) {
                    if (!ProtectionPermissionGate.read(context).isReady) {
                        withContext(Dispatchers.Main) { onPermissionsRequired() }
                        return@launch
                    }
                    val dao = db.websiteUsageLimitDao()
                    val normalizedRule = WebsiteBlocker.normalizeRule(siteToEdit.domain)
                    if (normalizedRule.isEmpty()) return@launch
                    // O editor pode ter ficado aberto até a trava começar (ou até o
                    // relógio ser mudado): só altera o que ainda pode ser alterado.
                    val refusal = siteGate(
                        blockingSessionManager.currentWebsiteLimit(db, normalizedRule)
                    ).takeIf { it.refusesMutation() }
                    if (refusal != null) {
                        withContext(Dispatchers.Main) {
                            showEditDialog = false
                            showRefusal(refusal)
                        }
                        return@launch
                    }
                    if (minutes <= 0) {
                        dao.getAllStatic()
                            .firstOrNull {
                                WebsiteBlocker.normalizeRule(it.domain) == normalizedRule
                            }
                            ?.let { dao.delete(it) }
                        UsageLimitPauseStateStore.clearDailyStateFor(normalizedRule)
                        blockingSessionManager.checkAndEnforce()
                        withContext(Dispatchers.Main) {
                            sites = sites.filterNot {
                                WebsiteBlocker.normalizeRule(it.domain) == normalizedRule
                            }
                            allConfiguredCount = (allConfiguredCount - 1).coerceAtLeast(0)
                            showEditDialog = false
                        }
                        return@launch
                    }
                    dao.insert(
                        WebsiteUsageLimit(
                            domain = normalizedRule,
                            dailyLimitMinutes = minutes,
                            isEnabled = enabled,
                            lockMode = lockMode,
                            lockPasswordHash = null,
                            lockUntilTimestamp = lockUntil,
                            // Editar dentro das 48 h não pode apagar a trava marcada.
                            lockAfter = siteToEdit.lockAfter
                        )
                    )
                    UsageLimitPauseStateStore.clearDailyStateFor(normalizedRule)
                    blockingSessionManager.checkAndEnforce()
                    withContext(Dispatchers.Main) {
                        sites = sites.map {
                            if (WebsiteBlocker.normalizeRule(it.domain) == normalizedRule) {
                                it.copy(
                                    domain = normalizedRule,
                                    dailyLimitMinutes = minutes,
                                    isEnabled = enabled,
                                    lockMode = lockMode,
                                    lockPasswordHash = null,
                                    lockUntilTimestamp = lockUntil
                                )
                            } else it
                        }
                        showEditDialog = false
                    }
                }
            }
        )
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

    LimitMutationAlerts(
        showTimeLocked = showTimeLockedAlert,
        onTimeLockedDismiss = { showTimeLockedAlert = false },
        showSafetyMode = showSafetyModeAlert,
        onSafetyModeDismiss = { showSafetyModeAlert = false },
        showCredentialMissing = showCredentialMissingAlert,
        onCredentialMissingDismiss = { showCredentialMissingAlert = false },
        onConfigureMasterPassword = onConfigureMasterPassword
    )
}

@Composable
private fun UsageLimitPresetRow(
    rule: String,
    keywordMode: Boolean,
    onClick: () -> Unit
) {
    val icon: ImageVector = if (keywordMode) Icons.Default.Tag else Icons.Default.Public
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(AccentCyan.copy(alpha = 0.10f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = AccentCyan)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    WebsiteBlocker.displayRule(rule),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
                Text(
                    stringResource(R.string.limits_preset_not_configured),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
            }
            Icon(Icons.Default.Add, contentDescription = null, tint = AccentCyan)
        }
    }
}

@Composable
private fun LimitMutationAlerts(
    showTimeLocked: Boolean,
    onTimeLockedDismiss: () -> Unit,
    showSafetyMode: Boolean,
    onSafetyModeDismiss: () -> Unit,
    showCredentialMissing: Boolean,
    onCredentialMissingDismiss: () -> Unit,
    onConfigureMasterPassword: () -> Unit
) {
    if (showTimeLocked) {
        AlertDialog(
            onDismissRequest = onTimeLockedDismiss,
            title = { Text(stringResource(R.string.limits_locked_alert_title), color = DangerRed) },
            text = {
                Text(
                    stringResource(R.string.limits_locked_alert_desc),
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            confirmButton = {
                TextButton(onClick = onTimeLockedDismiss) {
                    Text(stringResource(R.string.action_ok), color = AccentCyan)
                }
            },
            containerColor = MaterialTheme.colorScheme.surface
        )
    }
    if (showSafetyMode) {
        AlertDialog(
            onDismissRequest = onSafetyModeDismiss,
            title = { Text(stringResource(R.string.limits_security_mode), color = DangerRed) },
            text = {
                Text(
                    stringResource(R.string.master_credential_blocked_by_safety_mode),
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            confirmButton = {
                TextButton(onClick = onSafetyModeDismiss) {
                    Text(stringResource(R.string.action_ok), color = AccentCyan)
                }
            },
            containerColor = MaterialTheme.colorScheme.surface
        )
    }
    if (showCredentialMissing) {
        AlertDialog(
            onDismissRequest = onCredentialMissingDismiss,
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
                TextButton(
                    onClick = {
                        onCredentialMissingDismiss()
                        onConfigureMasterPassword()
                    }
                ) {
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