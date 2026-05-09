package com.focusguard.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
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
fun CreateSessionWizard(
    sessionType: String,
    authManager: AuthManager,
    onFinish: () -> Unit
) {
    val pagerState = rememberPagerState(pageCount = { 3 })
    val scope = rememberCoroutineScope()
    var selectedApps by remember { mutableStateOf<List<SelectableAppUi>>(emptyList()) }
    var selectedSites by remember { mutableStateOf<List<String>>(emptyList()) }

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
            1 -> WebsiteSelectionStep(
                initialSites = selectedSites,
                onNext = { sites ->
                    selectedSites = sites
                    scope.launch { pagerState.animateScrollToPage(2) }
                },
                onBack = { scope.launch { pagerState.animateScrollToPage(0) } }
            )
            2 -> TimeAwareFinalConfigStep(
                sessionType = sessionType,
                authManager = authManager,
                sites = selectedSites,
                apps = selectedApps.map { it.packageName },
                appName = selectedApps.firstOrNull()?.appName ?: if (selectedApps.size > 1) "${selectedApps.size} aplicativos" else "aplicativo",
                onFinish = onFinish,
                onBack = { scope.launch { pagerState.animateScrollToPage(1) } }
            )
        }
    }
}

@Composable
fun AppSelectionStep(
    onNext: (List<SelectableAppUi>) -> Unit,
    onBack: () -> Unit
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
                        isSelected = false,
                        isInstalled = true
                    )
                }
                .sortedBy { it.appName.lowercase() }

            val predefinedApps = PredefinedApps.PREVENTIVE_APPS
                .filter { !installedPackageNames.contains(it.packageName) }
                .map {
                    SelectableAppUi(
                        packageName = it.packageName,
                        appName = it.appName,
                        isSelected = false,
                        isInstalled = false,
                        category = it.category
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
            Text("Prosseguir", color = DarkBg, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
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
                title = { Text("Sites", color = TextPrimary) },
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
                modifier = Modifier.fillMaxWidth().padding(16.dp).height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("Prosseguir", color = DarkBg, fontWeight = FontWeight.Bold)
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState())
        ) {
            Text("Adicione sites que também devem ser bloqueados.", color = TextSecondary, fontSize = 14.sp)
            Spacer(modifier = Modifier.height(24.dp))
            OutlinedTextField(
                value = urlInput,
                onValueChange = { urlInput = it },
                placeholder = { Text("exemplo.com", color = TextHint) },
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    TextButton(
                        onClick = {
                            val cleanSite = urlInput.trim().lowercase()
                            if (cleanSite.isNotBlank() && !sites.contains(cleanSite)) {
                                sites = sites + cleanSite
                                urlInput = ""
                            }
                        }
                    ) {
                        Text("Adicionar", color = AccentCyan, fontWeight = FontWeight.Bold)
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedBorderColor = AccentCyan
                )
            )

            if (sites.isNotEmpty()) {
                Spacer(modifier = Modifier.height(24.dp))
                Text("Sites selecionados", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
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
        title = { Text("Criar senha", color = TextPrimary) },
        text = {
            Column {
                Text("Crie uma senha para desbloqueios autorizados.", color = TextSecondary, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; error = null },
                    label = { Text("Nova senha") },
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
                    label = { Text("Confirmar senha") },
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
