package com.focusguard.ui

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.focusguard.R
import android.content.Intent
import androidx.compose.ui.res.stringResource
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import dagger.hilt.android.AndroidEntryPoint
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.data.PredefinedApps
import com.focusguard.manager.BlockingSessionManager
import com.focusguard.security.AuthManager
import com.focusguard.security.BlockTargetPolicy
import com.focusguard.security.ProtectionPermissionGate
import com.focusguard.ui.compose.screens.AppSelectionList
import com.focusguard.ui.compose.screens.AppSelectionScreen
import com.focusguard.ui.compose.screens.KeywordRulesTab
import com.focusguard.ui.compose.screens.SelectableAppUi
import com.focusguard.ui.compose.screens.TimeAwareFinalConfigStep
import com.focusguard.ui.compose.screens.UnifiedProtectionSetupWizard
import com.focusguard.ui.compose.screens.WebsiteRulesTab
import com.focusguard.ui.compose.theme.AccentCyan
import com.focusguard.ui.compose.theme.DangerRed
import com.focusguard.ui.compose.theme.DarkBg
import com.focusguard.ui.compose.theme.DarkCard
import com.focusguard.ui.compose.theme.DarkSurface
import com.focusguard.ui.compose.theme.FocusGuardTheme
import com.focusguard.ui.compose.theme.TextHint
import com.focusguard.ui.compose.theme.TextPrimary
import com.focusguard.ui.compose.theme.TextSecondary
import com.focusguard.utils.FocusGuardLogger
import com.focusguard.utils.WebsiteBlocker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class CreateSessionActivity : ComponentActivity() {

    // Injetado via Hilt (AuthModule.provideAuthManager) — usa a MESMA instância
    // singleton do app inteiro. Antes era `AuthManager(this)` direto, criando
    // uma instância paralela que burlava o singleton do Hilt e podia disparar
    // migrações em paralelo (corrigido em P0-2 no PR #20).
    @Inject lateinit var authManager: AuthManager
    private var redirectedToPermissions = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!ensureProtectionPermissions()) return
        val sessionType = intent.getStringExtra("SESSION_TYPE") ?: "PASSWORD"

        setContent {
            FocusGuardTheme {
                if (sessionType == "UNIFIED") {
                    UnifiedProtectionSetupWizard(
                        authManager = authManager,
                        onFinish = { finish() }
                    )
                } else {
                    CreateSessionWizard(
                        sessionType = sessionType,
                        authManager = authManager,
                        onFinish = { finish() }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isFinishing) ensureProtectionPermissions()
    }

    private fun ensureProtectionPermissions(): Boolean {
        if (ProtectionPermissionGate.read(this).isReady) return true
        if (!redirectedToPermissions) {
            redirectedToPermissions = true
            startActivity(PermissionsActivity.createPendingProtectionIntent(this))
            finish()
        }
        return false
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CreateSessionWizard(
    sessionType: String,
    authManager: AuthManager,
    onFinish: () -> Unit
) {
    val pagerState = rememberPagerState(pageCount = { 2 })
    val scope = rememberCoroutineScope()
    // O que cada bloqueio aceita como alvo é decidido por tipo, não pela tela:
    // senha protege só aplicativos, jejum aceita apps, sites e palavras.
    val kinds = remember(sessionType) { BlockTargetPolicy.forSessionType(sessionType) }
    var selectedApps by remember { mutableStateOf<List<SelectableAppUi>>(emptyList()) }
    var selectedRules by remember { mutableStateOf<List<String>>(emptyList()) }

    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
        userScrollEnabled = false
    ) { page ->
        when (page) {
            0 -> AppSelectionStep(
                kinds = kinds,
                // Voltar da segunda página reabre esta com a escolha intacta, em
                // vez de exigir que tudo seja marcado de novo.
                initialSelectedPackages = selectedApps.mapTo(linkedSetOf()) {
                    it.packageName
                },
                initialRules = selectedRules,
                onNext = { apps, rules ->
                    selectedApps = apps
                    selectedRules = rules
                    scope.launch { pagerState.animateScrollToPage(1) }
                },
                onBack = onFinish
            )
            1 -> {
                val appPackages = selectedApps.map { it.packageName }
                val targetCount = appPackages.size + selectedRules.size
                val appNameLabel = when {
                    targetCount > 1 -> stringResource(R.string.block_targets_count, targetCount)
                    selectedApps.isNotEmpty() -> selectedApps.first().appName
                    selectedRules.isNotEmpty() ->
                        WebsiteBlocker.displayRule(selectedRules.first())
                    else -> stringResource(R.string.block_targets_fallback_name)
                }

                TimeAwareFinalConfigStep(
                    sessionType = sessionType,
                    authManager = authManager,
                    sites = selectedRules,
                    apps = appPackages,
                    appName = appNameLabel,
                    onFinish = onFinish,
                    onBack = { scope.launch { pagerState.animateScrollToPage(0) } }
                )
            }
        }
    }
}

/**
 * First page of the block wizard: what the block will hold.
 *
 * Which tabs exist is [kinds]' decision, not this screen's. With a single kind
 * the tab bar disappears entirely, so a password block still shows exactly the
 * app list it always did.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSelectionStep(
    onNext: (List<SelectableAppUi>, List<String>) -> Unit,
    onBack: () -> Unit,
    initialSelectedPackages: Set<String> = emptySet(),
    allowCompatibleProtection: Boolean = false,
    kinds: BlockTargetPolicy.Kinds = BlockTargetPolicy.APPS_ONLY,
    initialRules: List<String> = emptyList()
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val pm = context.packageManager
    var apps by remember { mutableStateOf<List<SelectableAppUi>>(emptyList()) }
    var configuredBlockedPackages by remember { mutableStateOf<Set<String>>(emptySet()) }
    var configuredBlockedRules by remember { mutableStateOf<Set<String>>(emptySet()) }
    var rules by remember { mutableStateOf(initialRules) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(initialSelectedPackages) {
        withContext(Dispatchers.IO) {
            val configured = try {
                BlockingSessionManager.getInstance(context).getConfiguredBlockedTargets()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                FocusGuardLogger.logError(
                    "AppSelectionStep",
                    "Falha ao carregar alvos já bloqueados",
                    error
                )
                BlockingSessionManager.ConfiguredBlockedTargets()
            }
            val blockedPackages = if (allowCompatibleProtection) {
                configured.unavailableAppPackageNames
            } else {
                configured.allAppPackageNames
            }
            val blockedRules = if (allowCompatibleProtection) {
                configured.unavailableWebsiteRules
            } else {
                configured.allWebsiteRules
            }
            val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val launcherQueryFlags = PackageManager.MATCH_DISABLED_COMPONENTS
            val launchables = pm.queryIntentActivities(launcherIntent, launcherQueryFlags)
                .map { it.activityInfo.packageName }
                .toSet()
            val installedPackageNames = mutableSetOf<String>()

            val installedApps = pm.getInstalledApplications(
                PackageManager.GET_META_DATA or PackageManager.MATCH_DISABLED_COMPONENTS
            )
                .filter { info ->
                    info.packageName != context.packageName &&
                        info.packageName != "com.focusguard" &&
                        launchables.contains(info.packageName)
                }
                .map { info ->
                    installedPackageNames.add(info.packageName)
                    SelectableAppUi(
                        packageName = info.packageName,
                        appName = info.loadLabel(pm).toString(),
                        isSelected = info.packageName in initialSelectedPackages &&
                            info.packageName !in blockedPackages,
                        isInstalled = true
                    )
                }
                .sortedBy { it.appName.lowercase() }

            val predefinedApps = PredefinedApps.PREVENTIVE_APPS
                .filter { !installedPackageNames.contains(it.packageName) }
                .filterNot { it.packageName.startsWith("site:") }
                .map {
                    val iconUrl = if (!it.domain.isNullOrBlank()) {
                        "https://www.google.com/s2/favicons?domain=${it.domain}&sz=128"
                    } else null

                    SelectableAppUi(
                        packageName = it.packageName,
                        appName = it.appName,
                        isSelected = it.packageName in initialSelectedPackages &&
                            it.packageName !in blockedPackages,
                        isInstalled = false,
                        category = it.category,
                        iconUrl = iconUrl
                    )
                }

            withContext(Dispatchers.Main) {
                configuredBlockedPackages = blockedPackages
                configuredBlockedRules = blockedRules
                apps = predefinedApps + installedApps
                isLoading = false
            }
        }
    }

    val toggleApp: (String) -> Unit = { pkg ->
        if (pkg in configuredBlockedPackages) {
            apps = apps.map {
                if (it.packageName == pkg) it.copy(isSelected = false) else it
            }
            Toast.makeText(
                context,
                context.getString(R.string.app_already_blocked),
                Toast.LENGTH_SHORT
            ).show()
        } else {
            apps = apps.map {
                if (it.packageName == pkg) it.copy(isSelected = !it.isSelected) else it
            }
        }
    }

    val proceed: () -> Unit = {
        onNext(
            apps.filter { it.isSelected && it.packageName !in configuredBlockedPackages },
            rules
        )
    }

    if (!kinds.needsTabs) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f)) {
                AppSelectionScreen(
                    apps = apps,
                    isLoading = isLoading,
                    onToggleApp = toggleApp,
                    onBack = onBack
                )
            }
            ProceedButton(onClick = proceed)
        }
        return
    }

    val tabs = remember(kinds) {
        buildList {
            if (kinds.apps) add(BlockTargetTab.APPS)
            if (kinds.websites) add(BlockTargetTab.SITES)
            if (kinds.keywords) add(BlockTargetTab.KEYWORDS)
        }
    }
    var selectedTab by remember { mutableStateOf(tabs.first()) }
    val onAlreadyBlocked: () -> Unit = {
        Toast.makeText(
            context,
            context.getString(R.string.site_already_blocked),
            Toast.LENGTH_SHORT
        ).show()
    }

    Scaffold(
        containerColor = DarkBg,
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text(stringResource(R.string.block_targets_title), color = TextPrimary)
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.action_back),
                                tint = TextPrimary
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBg)
                )
                TabRow(
                    selectedTabIndex = tabs.indexOf(selectedTab),
                    containerColor = DarkSurface,
                    contentColor = AccentCyan
                ) {
                    tabs.forEach { tab ->
                        Tab(
                            selected = tab == selectedTab,
                            onClick = { selectedTab = tab },
                            text = {
                                Text(
                                    stringResource(tab.titleRes),
                                    color = if (tab == selectedTab) AccentCyan else TextHint,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        )
                    }
                }
            }
        },
        bottomBar = { ProceedButton(onClick = proceed) }
    ) { padding ->
        when (selectedTab) {
            BlockTargetTab.APPS -> AppSelectionList(
                apps = apps,
                isLoading = isLoading,
                onToggleApp = toggleApp,
                modifier = Modifier.padding(padding)
            )

            BlockTargetTab.SITES -> WebsiteRulesTab(
                rules = rules,
                blockedRules = configuredBlockedRules,
                onRulesChange = { rules = it },
                onAlreadyBlocked = onAlreadyBlocked,
                modifier = Modifier.padding(padding)
            )

            BlockTargetTab.KEYWORDS -> KeywordRulesTab(
                rules = rules,
                blockedRules = configuredBlockedRules,
                onRulesChange = { rules = it },
                onAlreadyBlocked = onAlreadyBlocked,
                modifier = Modifier.padding(padding)
            )
        }
    }
}

private enum class BlockTargetTab(val titleRes: Int) {
    APPS(R.string.block_targets_tab_apps),
    SITES(R.string.block_targets_tab_sites),
    KEYWORDS(R.string.block_targets_tab_keywords)
}

@Composable
private fun ProceedButton(onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(16.dp).height(56.dp),
        colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
        shape = RoundedCornerShape(16.dp)
    ) {
        Text(
            stringResource(R.string.final_config_proceed),
            color = DarkBg,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp
        )
    }
}

// Fluxo de criação de sessão unificado.

@Composable
fun PasswordCreationDialog(
    onDismiss: () -> Unit,
    onPasswordCreated: (String) -> Unit,
    isSaving: Boolean = false
) {
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    // Içadas: stringResource é @Composable e não pode ser chamada dentro do onClick.
    val emptyPasswordError = stringResource(R.string.create_session_error_empty)
    val passwordMismatchError = stringResource(R.string.create_session_error_mismatch)

    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = { Text(stringResource(R.string.final_config_create_password), color = TextPrimary) },
        text = {
            Column {
                Text(stringResource(R.string.create_password_subtitle), color = TextSecondary, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; error = null },
                    label = { Text(stringResource(R.string.new_password)) },
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = AccentCyan
                    )
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = confirmPassword,
                    onValueChange = { confirmPassword = it; error = null },
                    label = { Text(stringResource(R.string.confirmar_senha)) },
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = AccentCyan
                    )
                )
                if (error != null) {
                    Text(error!!, color = DangerRed, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (password.isBlank()) {
                        error = emptyPasswordError
                    } else if (password != confirmPassword) {
                        error = passwordMismatchError
                    } else {
                        onPasswordCreated(password)
                    }
                },
                enabled = !isSaving,
                colors = ButtonDefaults.buttonColors(containerColor = AccentCyan)
            ) {
                Text(stringResource(R.string.create_password_save), color = DarkBg)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSaving) {
                Text(stringResource(R.string.create_password_cancel), color = TextSecondary)
            }
        },
        containerColor = DarkSurface,
        shape = RoundedCornerShape(24.dp)
    )
}