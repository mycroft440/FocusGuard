package com.focusguard.ui

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.focusguard.R
import android.content.Intent
import androidx.compose.ui.res.stringResource
import android.content.pm.PackageManager
import android.os.Bundle
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
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
import com.focusguard.security.AuthManager
import com.focusguard.ui.compose.screens.AppSelectionScreen
import com.focusguard.ui.compose.screens.SelectableAppUi
import com.focusguard.ui.compose.screens.TimeAwareFinalConfigStep
import com.focusguard.ui.compose.screens.UnifiedProtectionSetupWizard
import com.focusguard.ui.compose.theme.AccentCyan
import com.focusguard.ui.compose.theme.DangerRed
import com.focusguard.ui.compose.theme.DarkBg
import com.focusguard.ui.compose.theme.DarkCard
import com.focusguard.ui.compose.theme.DarkSurface
import com.focusguard.ui.compose.theme.FocusGuardTheme
import com.focusguard.ui.compose.theme.TextHint
import com.focusguard.ui.compose.theme.TextPrimary
import com.focusguard.ui.compose.theme.TextSecondary
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
    var selectedApps by remember { mutableStateOf<List<SelectableAppUi>>(emptyList()) }

    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
        userScrollEnabled = false
    ) { page ->
        when (page) {
            0 -> AppSelectionStep(
                onNext = { apps ->
                    selectedApps = apps
                    scope.launch { pagerState.animateScrollToPage(1) }
                },
                onBack = onFinish
            )
            1 -> {
                val sites = selectedApps.filter { it.packageName.startsWith("site:") }.map { it.packageName.removePrefix("site:") }
                val pureApps = selectedApps.filter { !it.packageName.startsWith("site:") }.map { it.packageName }
                val appNameLabel = selectedApps.firstOrNull()?.appName ?: if (selectedApps.size > 1) "${selectedApps.size} itens" else "aplicativo"
                
                TimeAwareFinalConfigStep(
                    sessionType = sessionType,
                    authManager = authManager,
                    sites = sites,
                    apps = pureApps,
                    appName = appNameLabel,
                    onFinish = onFinish,
                    onBack = { scope.launch { pagerState.animateScrollToPage(0) } }
                )
            }
        }
    }
}

@Composable
fun AppSelectionStep(
    onNext: (List<SelectableAppUi>) -> Unit,
    onBack: () -> Unit,
    initialSelectedPackages: Set<String> = emptySet(),
    includeWebsiteSuggestions: Boolean = true
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val pm = context.packageManager
    var apps by remember { mutableStateOf<List<SelectableAppUi>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val launchables = pm.queryIntentActivities(launcherIntent, 0).map { it.activityInfo.packageName }.toSet()
            val installedPackageNames = mutableSetOf<String>()

            val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
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
                        isSelected = info.packageName in initialSelectedPackages,
                        isInstalled = true
                    )
                }
                .sortedBy { it.appName.lowercase() }

            val predefinedApps = PredefinedApps.PREVENTIVE_APPS
                .filter { !installedPackageNames.contains(it.packageName) }
                .filter { includeWebsiteSuggestions || !it.packageName.startsWith("site:") }
                .map {
                    val iconUrl = if (!it.domain.isNullOrBlank()) {
                        "https://www.google.com/s2/favicons?domain=${it.domain}&sz=128"
                    } else null
                    
                    SelectableAppUi(
                        packageName = it.packageName,
                        appName = it.appName,
                        isSelected = it.packageName in initialSelectedPackages,
                        isInstalled = false,
                        category = it.category,
                        iconUrl = iconUrl
                    )
                }

            withContext(Dispatchers.Main) {
                apps = predefinedApps + installedApps
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
            modifier = Modifier.fillMaxWidth().padding(16.dp).height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text(stringResource(R.string.final_config_proceed), color = DarkBg, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
    }
}

// Fluxo de criação de sessão unificado.

@Composable
fun PasswordCreationDialog(
    onDismiss: () -> Unit,
    onPasswordCreated: (String) -> Unit
) {
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
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
                        error = "A senha não pode estar vazia"
                    } else if (password != confirmPassword) {
                        error = "As senhas não coincidem"
                    } else {
                        onPasswordCreated(password)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = AccentCyan)
            ) {
                Text(stringResource(R.string.create_password_save), color = DarkBg)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.create_password_cancel), color = TextSecondary)
            }
        },
        containerColor = DarkSurface,
        shape = RoundedCornerShape(24.dp)
    )
}
