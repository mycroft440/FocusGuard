package com.focusguard.ui.compose.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.R
import com.focusguard.database.AppUsageLimit
import com.focusguard.database.WebsiteUsageLimit
import com.focusguard.manager.BlockingSessionManager
import com.focusguard.security.AuthManager
import com.focusguard.ui.AppSelectionStep
import com.focusguard.ui.FinalConfigStep
import com.focusguard.ui.compose.rememberAppDatabase
import com.focusguard.ui.compose.theme.AccentCyan
import com.focusguard.ui.compose.theme.AccentPurple
import com.focusguard.ui.compose.theme.CardBorder
import com.focusguard.ui.compose.theme.DangerRed
import com.focusguard.ui.compose.theme.DarkBg
import com.focusguard.ui.compose.theme.DarkCard
import com.focusguard.ui.compose.theme.TextHint
import com.focusguard.ui.compose.theme.TextPrimary
import com.focusguard.ui.compose.theme.TextSecondary
import com.focusguard.utils.FocusGuardLogger
import com.focusguard.utils.WebsiteBlocker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class ProtectionSetupPage {
    LIST,
    APP_PICKER,
    WEBSITE_PICKER,
    MODE,
    DAILY_LIMIT,
    PASSWORD,
    DOPAMINE_FAST
}

@Composable
fun UnifiedProtectionSetupWizard(
    authManager: AuthManager,
    onFinish: () -> Unit
) {
    var page by remember { mutableStateOf(ProtectionSetupPage.LIST) }
    var selectedApps by remember { mutableStateOf<List<SelectableAppUi>>(emptyList()) }
    var websiteRules by remember { mutableStateOf<List<String>>(emptyList()) }

    val draft = ProtectionDraft(
        appPackageNames = selectedApps.mapTo(linkedSetOf()) { it.packageName },
        websiteRules = websiteRules.toSet()
    )

    fun returnToList() {
        page = ProtectionSetupPage.LIST
    }

    BackHandler {
        when (page) {
            ProtectionSetupPage.LIST -> onFinish()
            ProtectionSetupPage.APP_PICKER,
            ProtectionSetupPage.WEBSITE_PICKER,
            ProtectionSetupPage.MODE -> returnToList()
            ProtectionSetupPage.DAILY_LIMIT,
            ProtectionSetupPage.PASSWORD,
            ProtectionSetupPage.DOPAMINE_FAST -> page = ProtectionSetupPage.MODE
        }
    }

    AnimatedContent(
        targetState = page,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "UnifiedProtectionSetup"
    ) { currentPage ->
        when (currentPage) {
            ProtectionSetupPage.LIST -> ProtectionListBuilderScreen(
                selectedApps = selectedApps,
                websiteRules = websiteRules,
                onAddApps = { page = ProtectionSetupPage.APP_PICKER },
                onAddSites = { page = ProtectionSetupPage.WEBSITE_PICKER },
                onRemoveApp = { packageName ->
                    selectedApps = selectedApps.filterNot { it.packageName == packageName }
                },
                onRemoveWebsite = { rule ->
                    websiteRules = websiteRules.filterNot { it == rule }
                },
                onContinue = {
                    if (draft.hasTargets) page = ProtectionSetupPage.MODE
                },
                onBack = onFinish
            )

            ProtectionSetupPage.APP_PICKER -> AppSelectionStep(
                onNext = { apps ->
                    selectedApps = apps
                    returnToList()
                },
                onBack = ::returnToList,
                initialSelectedPackages = selectedApps.mapTo(linkedSetOf()) { it.packageName },
                includeWebsiteSuggestions = false
            )

            ProtectionSetupPage.WEBSITE_PICKER -> WebsiteRuleSelectionScreen(
                initialRules = websiteRules,
                onSave = { rules ->
                    websiteRules = rules
                    returnToList()
                },
                onBack = ::returnToList
            )

            ProtectionSetupPage.MODE -> ProtectionModeSelectionScreen(
                draft = draft,
                selectedCount = selectedApps.size + websiteRules.size,
                onModeSelected = { mode ->
                    page = when (mode) {
                        ProtectionMode.LIMIT -> ProtectionSetupPage.DAILY_LIMIT
                        ProtectionMode.PASSWORD -> ProtectionSetupPage.PASSWORD
                        ProtectionMode.DOPAMINE_FAST -> ProtectionSetupPage.DOPAMINE_FAST
                    }
                },
                onBack = ::returnToList
            )

            ProtectionSetupPage.DAILY_LIMIT -> BatchDailyLimitConfigScreen(
                apps = selectedApps,
                websiteRules = websiteRules,
                onBack = { page = ProtectionSetupPage.MODE },
                onFinish = onFinish
            )

            ProtectionSetupPage.PASSWORD -> FinalConfigStep(
                sessionType = "PASSWORD",
                authManager = authManager,
                sites = websiteRules,
                apps = selectedApps.map { it.packageName },
                onFinish = onFinish,
                onBack = { page = ProtectionSetupPage.MODE }
            )

            ProtectionSetupPage.DOPAMINE_FAST -> TimeBlockSessionConfigScreen(
                appName = protectionTargetLabel(selectedApps, websiteRules),
                apps = selectedApps.map { it.packageName },
                sites = websiteRules,
                onBack = { page = ProtectionSetupPage.MODE },
                onFinish = onFinish
            )
        }
    }
}

private fun protectionTargetLabel(
    apps: List<SelectableAppUi>,
    websiteRules: List<String>
): String {
    val total = apps.size + websiteRules.size
    return when {
        total == 1 && apps.isNotEmpty() -> apps.first().appName
        total == 1 -> WebsiteBlocker.displayRule(websiteRules.first())
        else -> "$total itens"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProtectionListBuilderScreen(
    selectedApps: List<SelectableAppUi>,
    websiteRules: List<String>,
    onAddApps: () -> Unit,
    onAddSites: () -> Unit,
    onRemoveApp: (String) -> Unit,
    onRemoveWebsite: (String) -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit
) {
    val hasTargets = selectedApps.isNotEmpty() || websiteRules.isNotEmpty()

    Scaffold(
        containerColor = DarkBg,
        topBar = {
            ProtectionTopBar(
                title = stringResource(R.string.protection_setup_title),
                onBack = onBack
            )
        },
        bottomBar = {
            Surface(color = DarkBg, tonalElevation = 8.dp) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Button(
                        onClick = onContinue,
                        enabled = hasTargets,
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text(
                            stringResource(R.string.protection_continue),
                            color = DarkBg,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    if (!hasTargets) {
                        Text(
                            stringResource(R.string.protection_continue_hint),
                            color = TextHint,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                ProtectionActionCard(
                    icon = Icons.Default.Apps,
                    title = stringResource(R.string.protection_add_apps_title),
                    subtitle = stringResource(R.string.protection_add_apps_subtitle),
                    onClick = onAddApps
                )
            }
            item {
                ProtectionActionCard(
                    icon = Icons.Default.Public,
                    title = stringResource(R.string.protection_add_sites_title),
                    subtitle = stringResource(R.string.protection_add_sites_subtitle),
                    onClick = onAddSites
                )
            }
            item {
                Text(
                    stringResource(R.string.protection_block_list).uppercase(),
                    color = TextSecondary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 18.dp, bottom = 2.dp)
                )
            }

            if (!hasTargets) {
                item { ProtectionEmptyState() }
            } else {
                items(selectedApps, key = { "app_${it.packageName}" }) { app ->
                    ProtectionTargetRow(
                        icon = Icons.Default.Apps,
                        title = app.appName,
                        subtitle = app.packageName,
                        onRemove = { onRemoveApp(app.packageName) }
                    )
                }
                items(websiteRules, key = { "site_$it" }) { rule ->
                    ProtectionTargetRow(
                        icon = Icons.Default.Public,
                        title = WebsiteBlocker.displayRule(rule),
                        subtitle = stringResource(R.string.sessions_category_websites),
                        onRemove = { onRemoveWebsite(rule) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ProtectionActionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        border = BorderStroke(1.dp, CardBorder),
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .background(AccentCyan.copy(alpha = 0.10f), RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(25.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(Modifier.height(3.dp))
                Text(subtitle, color = TextSecondary, fontSize = 12.sp)
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = TextHint)
        }
    }
}

@Composable
private fun ProtectionEmptyState() {
    Card(
        modifier = Modifier.fillMaxWidth().height(260.dp),
        colors = CardDefaults.cardColors(containerColor = DarkCard.copy(alpha = 0.62f)),
        border = BorderStroke(1.dp, CardBorder),
        shape = RoundedCornerShape(22.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Default.SearchOff, contentDescription = null, tint = TextHint, modifier = Modifier.size(52.dp))
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.protection_empty_title),
                color = TextPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.protection_empty_description),
                color = TextSecondary,
                fontSize = 14.sp
            )
        }
    }
}

@Composable
private fun ProtectionTargetRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onRemove: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        border = BorderStroke(1.dp, CardBorder),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = TextHint, fontSize = 11.sp, maxLines = 1)
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.remove_button), tint = DangerRed)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WebsiteRuleSelectionScreen(
    initialRules: List<String>,
    onSave: (List<String>) -> Unit,
    onBack: () -> Unit
) {
    var input by remember { mutableStateOf("") }
    var rules by remember(initialRules) { mutableStateOf(initialRules.distinct()) }
    var invalidInput by remember { mutableStateOf(false) }
    val suggestions = remember { listOf("youtube.com", "facebook.com", "reddit.com", "porn", "xxx") }

    fun addRule(value: String) {
        val normalized = WebsiteBlocker.normalizeRule(value)
        if (normalized.isEmpty()) {
            invalidInput = true
            return
        }
        rules = (rules + normalized).distinct()
        input = ""
        invalidInput = false
    }

    Scaffold(
        containerColor = DarkBg,
        topBar = {
            ProtectionTopBar(
                title = stringResource(R.string.protection_sites_title),
                onBack = onBack
            )
        },
        bottomBar = {
            Surface(color = DarkBg, tonalElevation = 8.dp) {
                Button(
                    onClick = { onSave(rules) },
                    modifier = Modifier.fillMaxWidth().padding(20.dp, 12.dp).height(54.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(stringResource(R.string.protection_save_list), color = DarkBg, fontWeight = FontWeight.Bold)
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    stringResource(R.string.protection_sites_add_new).uppercase(),
                    color = TextSecondary,
                    fontWeight = FontWeight.Bold
                )
            }
            item {
                Row(verticalAlignment = Alignment.Top) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it; invalidInput = false },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(stringResource(R.string.protection_sites_placeholder), color = TextHint) },
                        leadingIcon = { Icon(Icons.Default.Public, contentDescription = null, tint = TextHint) },
                        isError = invalidInput,
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentCyan,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        shape = RoundedCornerShape(16.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Button(
                        onClick = { addRule(input) },
                        enabled = input.isNotBlank(),
                        modifier = Modifier.height(56.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, tint = DarkBg)
                    }
                }
            }
            item {
                Text(
                    if (invalidInput) stringResource(R.string.website_rule_invalid)
                    else stringResource(R.string.protection_sites_helper),
                    color = if (invalidInput) DangerRed else TextHint,
                    fontSize = 12.sp
                )
            }
            item {
                Text(
                    stringResource(R.string.protection_sites_common).uppercase(),
                    color = TextSecondary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(suggestions) { suggestion ->
                        val normalized = WebsiteBlocker.normalizeRule(suggestion)
                        FilterChip(
                            selected = normalized in rules,
                            onClick = {
                                rules = if (normalized in rules) {
                                    rules.filterNot { it == normalized }
                                } else {
                                    (rules + normalized).distinct()
                                }
                            },
                            label = { Text(WebsiteBlocker.displayRule(normalized)) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = AccentCyan.copy(alpha = 0.16f),
                                selectedLabelColor = AccentCyan,
                                labelColor = TextSecondary
                            )
                        )
                    }
                }
            }
            item {
                HorizontalDivider(color = CardBorder, modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    stringResource(R.string.protection_sites_selected).uppercase(),
                    color = TextSecondary,
                    fontWeight = FontWeight.Bold
                )
            }
            if (rules.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.protection_sites_none),
                        color = TextHint,
                        modifier = Modifier.padding(vertical = 20.dp)
                    )
                }
            } else {
                items(rules, key = { it }) { rule ->
                    ProtectionTargetRow(
                        icon = Icons.Default.Public,
                        title = WebsiteBlocker.displayRule(rule),
                        subtitle = stringResource(R.string.sessions_category_websites),
                        onRemove = { rules = rules.filterNot { it == rule } }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProtectionModeSelectionScreen(
    draft: ProtectionDraft,
    selectedCount: Int,
    onModeSelected: (ProtectionMode) -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        containerColor = DarkBg,
        topBar = {
            ProtectionTopBar(
                title = stringResource(R.string.protection_choose_mode_title),
                onBack = onBack
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Text(
                    stringResource(R.string.protection_choose_mode_subtitle),
                    color = TextSecondary,
                    fontSize = 15.sp
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.protection_selected_count, selectedCount),
                    color = AccentCyan,
                    fontWeight = FontWeight.Bold
                )
            }

            items(draft.availableModes) { mode ->
                when (mode) {
                    ProtectionMode.LIMIT -> ProtectionModeCard(
                        icon = Icons.Default.HourglassEmpty,
                        title = stringResource(R.string.protection_mode_limit_title),
                        subtitle = stringResource(R.string.protection_mode_limit_subtitle),
                        color = AccentCyan,
                        onClick = { onModeSelected(mode) }
                    )
                    ProtectionMode.PASSWORD -> ProtectionModeCard(
                        icon = Icons.Default.Lock,
                        title = stringResource(R.string.protection_mode_password_title),
                        subtitle = stringResource(R.string.protection_mode_password_subtitle),
                        color = AccentPurple,
                        onClick = { onModeSelected(mode) }
                    )
                    ProtectionMode.DOPAMINE_FAST -> ProtectionModeCard(
                        icon = Icons.Default.Shield,
                        title = stringResource(R.string.protection_mode_dopamine_title),
                        subtitle = stringResource(R.string.protection_mode_dopamine_subtitle),
                        color = DangerRed,
                        onClick = { onModeSelected(mode) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ProtectionModeCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    color: Color,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        border = BorderStroke(1.dp, color.copy(alpha = 0.28f)),
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(52.dp).background(color.copy(alpha = 0.12f), RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(25.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(Modifier.height(4.dp))
                Text(subtitle, color = TextSecondary, fontSize = 13.sp)
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = TextHint)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BatchDailyLimitConfigScreen(
    apps: List<SelectableAppUi>,
    websiteRules: List<String>,
    onBack: () -> Unit,
    onFinish: () -> Unit
) {
    val context = LocalContext.current
    val database = rememberAppDatabase()
    val manager = remember(context) { BlockingSessionManager.getInstance(context) }
    val scope = rememberCoroutineScope()
    var hoursText by remember { mutableStateOf("") }
    var minutesText by remember { mutableStateOf("") }
    var isSaving by remember { mutableStateOf(false) }
    val dailyLimitMinutes = parseDailyLimitMinutes(hoursText, minutesText)

    Scaffold(
        containerColor = DarkBg,
        topBar = {
            ProtectionTopBar(
                title = stringResource(R.string.protection_daily_limit_title),
                onBack = onBack
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp)
        ) {
            Text(
                stringResource(R.string.protection_daily_limit_description),
                color = TextSecondary,
                fontSize = 15.sp
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.protection_selected_count, apps.size + websiteRules.size),
                color = AccentCyan,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(24.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkCard),
                border = BorderStroke(1.dp, CardBorder),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(Modifier.padding(18.dp)) {
                    Text(
                        stringResource(R.string.protection_daily_limit_question),
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = hoursText,
                            onValueChange = { value -> hoursText = value.filter { it.isDigit() }.take(2) },
                            modifier = Modifier.weight(1f),
                            label = { Text(stringResource(R.string.protection_hours)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = minutesText,
                            onValueChange = { value -> minutesText = value.filter { it.isDigit() }.take(2) },
                            modifier = Modifier.weight(1f),
                            label = { Text(stringResource(R.string.protection_minutes)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true
                        )
                    }
                    if ((hoursText.isNotEmpty() || minutesText.isNotEmpty()) && dailyLimitMinutes == null) {
                        Text(
                            stringResource(R.string.protection_daily_limit_invalid),
                            color = DangerRed,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 10.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            Button(
                onClick = {
                    val minutes = dailyLimitMinutes ?: return@Button
                    isSaving = true
                    scope.launch {
                        val result = runCatching {
                            withContext(Dispatchers.IO) {
                                apps.forEach { app ->
                                    database.appUsageLimitDao().insert(
                                        AppUsageLimit(
                                            packageName = app.packageName,
                                            appName = app.appName,
                                            dailyLimitMinutes = minutes,
                                            isEnabled = true
                                        )
                                    )
                                }
                                WebsiteBlocker.normalizeRules(websiteRules).forEach { rule ->
                                    database.websiteUsageLimitDao().insert(
                                        WebsiteUsageLimit(
                                            domain = rule,
                                            dailyLimitMinutes = minutes,
                                            isEnabled = true
                                        )
                                    )
                                }
                                manager.checkAndEnforce()
                            }
                        }

                        isSaving = false
                        if (result.isSuccess) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.protection_limit_success),
                                Toast.LENGTH_LONG
                            ).show()
                            onFinish()
                        } else {
                            FocusGuardLogger.logError(
                                "ProtectionSetup",
                                "Falha ao salvar limite em lote",
                                result.exceptionOrNull() ?: IllegalStateException("Erro desconhecido")
                            )
                            Toast.makeText(
                                context,
                                context.getString(R.string.protection_limit_error),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                },
                enabled = dailyLimitMinutes != null && !isSaving,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                shape = RoundedCornerShape(16.dp)
            ) {
                if (isSaving) {
                    CircularProgressIndicator(color = DarkBg, modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                } else {
                    Text(
                        stringResource(R.string.protection_activate_limit),
                        color = DarkBg,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProtectionTopBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(title, color = TextPrimary, fontWeight = FontWeight.Bold) },
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
}
