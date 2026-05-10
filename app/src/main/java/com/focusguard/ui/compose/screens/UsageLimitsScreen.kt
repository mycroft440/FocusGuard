package com.focusguard.ui.compose.screens

import kotlin.OptIn
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.database.AppDatabase
import com.focusguard.database.AppUsageLimit
import com.focusguard.database.WebsiteUsageLimit
import com.focusguard.ui.compose.theme.*
import com.focusguard.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageLimitsScreen(onBack: () -> Unit) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    var permissionsMissing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val deviceOwnerManager = com.focusguard.admin.DeviceOwnerManager.getInstance(context)
            val isA11yEnabled = com.focusguard.utils.PermissionUtils.isAccessibilityServiceEnabled(context)
            val isAdminActive = deviceOwnerManager.isDeviceAdminActive() || deviceOwnerManager.isDeviceOwnerActive()
            val isUsageAccessEnabled = com.focusguard.utils.PermissionUtils.isUsageAccessEnabled(context)
            val isBatteryIgnored = com.focusguard.utils.PermissionUtils.isBatteryOptimizationIgnored(context)
            permissionsMissing = !isA11yEnabled || !isAdminActive || !isUsageAccessEnabled || !isBatteryIgnored
        }
    }

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
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text(stringResource(R.string.sessions_category_websites), color = if (selectedTab == 1) AccentCyan else TextHint, fontWeight = FontWeight.Bold) }
                )
            }

            when (selectedTab) {
                0 -> AppLimitsTab(permissionsMissing)
                1 -> WebsiteLimitsTab(permissionsMissing)
            }
        }
    }
}

@Composable
fun AppLimitsTab(permissionsMissing: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var apps by remember { mutableStateOf<List<UsageLimitAppUi>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }
    var selectedApp by remember { mutableStateOf<UsageLimitAppUi?>(null) }
    var showDialog by remember { mutableStateOf(false) }
    var showPasswordConfirm by remember { mutableStateOf(false) }
    var showTimeLockedAlert by remember { mutableStateOf(false) }
    var pendingAction: (() -> Unit)? by remember { mutableStateOf(null) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            val resolveInfos = pm.queryIntentActivities(intent, 0)
            val db = AppDatabase.getDatabase(context)
            val limitDao = db.appUsageLimitDao()
            val existingLimits = limitDao.getAllStatic().associateBy { it.packageName }
            val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
            val cal = java.util.Calendar.getInstance().apply { 
                set(java.util.Calendar.HOUR_OF_DAY, 0)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
            }
            val stats = usageStatsManager.queryAndAggregateUsageStats(cal.timeInMillis, System.currentTimeMillis())

            val loadedApps = resolveInfos.mapNotNull { info ->
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
            }.sortedBy { it.appName }

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
                        UsageLimitItem(app, isActive = true) { 
                            if (app.lockMode == "TIME" && app.lockUntilTimestamp != null && System.currentTimeMillis() < app.lockUntilTimestamp) {
                                showTimeLockedAlert = true
                            } else if (app.lockMode == "PASSWORD") {
                                pendingAction = { selectedApp = app; showDialog = true }
                                showPasswordConfirm = true
                            } else {
                                selectedApp = app; showDialog = true 
                            }
                        }
                    }
                }

                if (inactiveLimits.isNotEmpty()) {
                    item { Text(stringResource(R.string.limits_paused_section), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextHint, modifier = Modifier.padding(top = 24.dp, bottom = 8.dp)) }
                    items(inactiveLimits, key = { "inactive_${it.packageName}" }) { app ->
                        UsageLimitItem(app, isActive = false) { 
                            selectedApp = app; showDialog = true 
                        }
                    }
                }

                if (unconfiguredApps.isNotEmpty()) {
                    item {
                        val sectionTitle = if (activeLimits.isEmpty() && inactiveLimits.isEmpty()) stringResource(R.string.limits_setup_section) else stringResource(R.string.limits_other_section)
                        Text(sectionTitle, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextSecondary, modifier = Modifier.padding(top = 24.dp, bottom = 8.dp)) }
                    items(unconfiguredApps, key = { "unconf_${it.packageName}" }) { app ->
                        UsageLimitItem(app, isActive = false) { 
                            selectedApp = app; showDialog = true 
                        }
                    }
                }
            }
        }
    }

    if (showDialog && selectedApp != null) {
        AppLimitDialog(
            app = selectedApp!!,
            permissionsMissing = permissionsMissing,
            onDismiss = { showDialog = false },
            onSave = { minutes, enabled, lockMode, lockPassword, lockUntil ->
                scope.launch(Dispatchers.IO) {
                    val db = AppDatabase.getDatabase(context).appUsageLimitDao()
                    if (minutes != null && minutes > 0) {
                        db.insert(AppUsageLimit(selectedApp!!.packageName, selectedApp!!.appName, minutes, enabled, lockMode, lockPassword, lockUntil))
                        val updated = selectedApp!!.copy(currentLimitMinutes = minutes, isEnabled = enabled, lockMode = lockMode, lockPasswordHash = lockPassword, lockUntilTimestamp = lockUntil)
                        apps = apps.map { if (it.packageName == updated.packageName) updated else it }
                    } else {
                        val existing = db.getAllStatic().find { it.packageName == selectedApp!!.packageName }
                        if (existing != null) db.delete(existing)
                        val updated = selectedApp!!.copy(currentLimitMinutes = null, isEnabled = false, lockMode = "NONE", lockPasswordHash = null, lockUntilTimestamp = null)
                        apps = apps.map { if (it.packageName == updated.packageName) updated else it }
                    }
                    withContext(Dispatchers.Main) { showDialog = false }
                }
            }
        )
    }

    if (showPasswordConfirm && selectedApp != null) {
        ConfirmLimitPasswordDialog(
            expectedHash = selectedApp?.lockPasswordHash ?: "",
            onDismiss = { showPasswordConfirm = false },
            onConfirm = { 
                showPasswordConfirm = false
                pendingAction?.invoke()
                pendingAction = null
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
}

@Composable
fun WebsiteLimitsTab(permissionsMissing: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sites by remember { mutableStateOf<List<WebsiteLimitUi>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var showAddDialog by remember { mutableStateOf(false) }
    var selectedSite by remember { mutableStateOf<WebsiteLimitUi?>(null) }
    var showEditDialog by remember { mutableStateOf(false) }
    var showPasswordConfirm by remember { mutableStateOf(false) }
    var showTimeLockedAlert by remember { mutableStateOf(false) }
    var pendingAction: (() -> Unit)? by remember { mutableStateOf(null) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(context)
            val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
            val allLimits = db.websiteUsageLimitDao().getAllStatic()
            val usageStats = db.dailyUsageStatDao().getStatsForDateStatic(today).associate { it.identifier to it.timeSpentMs }
            sites = allLimits.map { WebsiteLimitUi(it.domain, it.dailyLimitMinutes, it.isEnabled, usageStats[it.domain] ?: 0L, it.lockMode, it.lockPasswordHash, it.lockUntilTimestamp) }
            withContext(Dispatchers.Main) { isLoading = false }
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
                            if (site.lockMode == "TIME" && site.lockUntilTimestamp != null && System.currentTimeMillis() < site.lockUntilTimestamp) {
                                showTimeLockedAlert = true
                            } else if (site.lockMode == "PASSWORD") {
                                pendingAction = { selectedSite = site; showEditDialog = true }
                                showPasswordConfirm = true
                            } else {
                                selectedSite = site; showEditDialog = true 
                            }
                        },
                        onDelete = {
                            if (site.lockMode == "TIME" && site.lockUntilTimestamp != null && System.currentTimeMillis() < site.lockUntilTimestamp) {
                                showTimeLockedAlert = true
                            } else {
                                val action = {
                                    scope.launch(Dispatchers.IO) {
                                        val db = AppDatabase.getDatabase(context).websiteUsageLimitDao()
                                        val existing = db.getAllStatic().find { it.domain == site.domain }
                                        if (existing != null) db.delete(existing)
                                        withContext(Dispatchers.Main) { sites = sites.filter { it.domain != site.domain } }
                                    }
                                    Unit
                                }
                                if (site.lockMode == "PASSWORD") {
                                    selectedSite = site
                                    pendingAction = action
                                    showPasswordConfirm = true
                                } else {
                                    action()
                                }
                            }
                        }
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        AddWebsiteLimitDialog(permissionsMissing = permissionsMissing, onDismiss = { showAddDialog = false }, onSave = { domain, minutes, lockMode, lockPassword, lockUntil ->
            scope.launch(Dispatchers.IO) {
                val db = AppDatabase.getDatabase(context).websiteUsageLimitDao()
                val clean = domain.trim().lowercase().removePrefix("http://").removePrefix("https://").removePrefix("www.").trimEnd('/')
                db.insert(WebsiteUsageLimit(clean, minutes, true, lockMode, lockPassword, lockUntil))
                withContext(Dispatchers.Main) { sites = sites + WebsiteLimitUi(clean, minutes, true, 0L, lockMode, lockPassword, lockUntil); showAddDialog = false }
            }
        })
    }

    if (showEditDialog && selectedSite != null) {
        EditWebsiteLimitDialog(site = selectedSite!!, permissionsMissing = permissionsMissing, onDismiss = { showEditDialog = false }, onSave = { minutes, enabled, lockMode, lockPassword, lockUntil ->
            scope.launch(Dispatchers.IO) {
                val db = AppDatabase.getDatabase(context).websiteUsageLimitDao()
                db.insert(WebsiteUsageLimit(selectedSite!!.domain, minutes, enabled, lockMode, lockPassword, lockUntil))
                withContext(Dispatchers.Main) {
                    sites = sites.map { if (it.domain == selectedSite!!.domain) it.copy(dailyLimitMinutes = minutes, isEnabled = enabled, lockMode = lockMode, lockPasswordHash = lockPassword, lockUntilTimestamp = lockUntil) else it }
                    showEditDialog = false
                }
            }
        })
    }

    if (showPasswordConfirm && selectedSite != null) {
        ConfirmLimitPasswordDialog(
            expectedHash = selectedSite?.lockPasswordHash ?: "",
            onDismiss = { showPasswordConfirm = false },
            onConfirm = { 
                showPasswordConfirm = false
                pendingAction?.invoke()
                pendingAction = null
            }
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