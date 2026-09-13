package com.focusguard.ui.compose.screens

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.R
import com.focusguard.data.PredefinedApps
import com.focusguard.manager.BlockingSessionManager
import com.focusguard.manager.BlockingSessionManager.BlockingProtectionUnavailableException
import com.focusguard.security.BlockDurationPolicy
import com.focusguard.security.MasterCredentialConfigurationManager
import com.focusguard.ui.PermissionsActivity
import com.focusguard.ui.compose.components.FocusGuardAppIcon
import com.focusguard.ui.compose.theme.AccentCyan
import com.focusguard.ui.compose.theme.DangerRed
import com.focusguard.ui.compose.theme.DarkBg
import com.focusguard.ui.compose.theme.DarkCard
import com.focusguard.ui.compose.theme.TextHint
import com.focusguard.ui.compose.theme.TextPrimary
import com.focusguard.ui.compose.theme.TextSecondary
import com.focusguard.utils.AssociatedBlockTargets
import com.focusguard.utils.FocusGuardLogger
import com.focusguard.utils.WebsiteBlocker
import java.util.Calendar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun selectedTargetCount(appCount: Int, siteCount: Int): Int = appCount + siteCount

private enum class TimeBlockConfigPage {
    TERMS,
    SCHEDULE
}

private data class DopamineWeekday(
    val calendarDay: Int,
    @StringRes val labelRes: Int
)

private val DOPAMINE_WEEKDAYS = listOf(
    DopamineWeekday(Calendar.MONDAY, R.string.dopamine_weekday_mon),
    DopamineWeekday(Calendar.TUESDAY, R.string.dopamine_weekday_tue),
    DopamineWeekday(Calendar.WEDNESDAY, R.string.dopamine_weekday_wed),
    DopamineWeekday(Calendar.THURSDAY, R.string.dopamine_weekday_thu),
    DopamineWeekday(Calendar.FRIDAY, R.string.dopamine_weekday_fri),
    DopamineWeekday(Calendar.SATURDAY, R.string.dopamine_weekday_sat),
    DopamineWeekday(Calendar.SUNDAY, R.string.dopamine_weekday_sun)
)

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun TimeBlockSessionConfigScreen(
    appName: String,
    apps: List<String>,
    sites: List<String>,
    onBack: () -> Unit,
    onFinish: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sessionManager = remember(context) { BlockingSessionManager.getInstance(context) }
    val credentialConfigurationManager = remember(context) {
        MasterCredentialConfigurationManager(context)
    }

    var page by remember { mutableStateOf(TimeBlockConfigPage.TERMS) }
    val websiteCompanionOptions = remember(apps) {
        AssociatedBlockTargets.websiteCompanionsForApps(apps)
    }
    val appCompanionOptions = remember(sites) {
        AssociatedBlockTargets.appCompanionsForWebsiteRules(sites)
    }
    var configuredBlockedTargets by remember {
        mutableStateOf(BlockingSessionManager.ConfiguredBlockedTargets())
    }
    var selectedCompanionDomains by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedCompanionPackages by remember { mutableStateOf<Set<String>>(emptySet()) }

    LaunchedEffect(apps, sites) {
        configuredBlockedTargets = withContext(Dispatchers.IO) {
            runCatching { sessionManager.getConfiguredBlockedTargets() }
                .getOrDefault(BlockingSessionManager.ConfiguredBlockedTargets())
        }
    }

    val availableWebsiteCompanionOptions = remember(
        websiteCompanionOptions,
        sites,
        configuredBlockedTargets
    ) {
        websiteCompanionOptions.filterNot { option ->
            BlockingSessionManager.isWebsiteRuleCoveredBy(option.domain, sites) ||
                BlockingSessionManager.isWebsiteRuleCoveredBy(
                    option.domain,
                    configuredBlockedTargets.allWebsiteRules
                )
        }
    }
    val availableAppCompanionOptions = remember(
        appCompanionOptions,
        apps,
        configuredBlockedTargets
    ) {
        appCompanionOptions.filterNot { option ->
            option.packageName in apps ||
                option.packageName in configuredBlockedTargets.allAppPackageNames
        }
    }
    val availableCompanionDomains = remember(availableWebsiteCompanionOptions) {
        availableWebsiteCompanionOptions.mapTo(linkedSetOf()) { it.domain }.toSet()
    }
    val availableCompanionPackages = remember(availableAppCompanionOptions) {
        availableAppCompanionOptions.mapTo(linkedSetOf()) { it.packageName }.toSet()
    }
    LaunchedEffect(availableCompanionDomains) {
        selectedCompanionDomains = selectedCompanionDomains.intersect(availableCompanionDomains)
    }
    LaunchedEffect(availableCompanionPackages) {
        selectedCompanionPackages = selectedCompanionPackages.intersect(availableCompanionPackages)
    }

    val effectiveSites = remember(sites, selectedCompanionDomains) {
        (sites + selectedCompanionDomains).distinct()
    }
    val effectiveApps = remember(apps, selectedCompanionPackages) {
        (apps + selectedCompanionPackages).distinct()
    }
    val availableUnits = remember(effectiveApps, effectiveSites) {
        BlockDurationPolicy.availableUnits(
            rules = effectiveSites,
            hasApps = effectiveApps.isNotEmpty()
        )
    }
    var durationUnit by remember { mutableStateOf(BlockDurationPolicy.Unit.DAYS) }
    if (durationUnit !in availableUnits) {
        durationUnit = BlockDurationPolicy.Unit.DAYS
    }
    var amountText by remember { mutableStateOf("") }
    var termsAccepted by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var selectedDays by remember {
        mutableStateOf(DOPAMINE_WEEKDAYS.mapTo(linkedSetOf()) { it.calendarDay }.toSet())
    }
    var pendingProtectionReason by remember {
        mutableStateOf<BlockingProtectionUnavailableException.Reason?>(null)
    }
    var showMasterCredentialSetup by remember { mutableStateOf(false) }

    val duration = BlockDurationPolicy.resolve(durationUnit, amountText.toIntOrNull())
    val selectedDaysSerialized = remember(selectedDays) {
        DOPAMINE_WEEKDAYS
            .filter { it.calendarDay in selectedDays }
            .joinToString(",") { it.calendarDay.toString() }
    }
    val hasTargets = effectiveApps.isNotEmpty() || effectiveSites.isNotEmpty()
    val canContinue = termsAccepted && hasTargets
    val canSave = duration != null &&
        termsAccepted &&
        selectedDays.isNotEmpty() &&
        hasTargets

    fun navigateBack() {
        if (page == TimeBlockConfigPage.SCHEDULE) {
            page = TimeBlockConfigPage.TERMS
        } else {
            onBack()
        }
    }

    BackHandler(onBack = ::navigateBack)

    if (showMasterCredentialSetup) {
        DeactivationCredentialDialog(
            managementLocked = false,
            configureCredential = credentialConfigurationManager::configure,
            onDismiss = { showMasterCredentialSetup = false },
            onCredentialChanged = { showMasterCredentialSetup = false }
        )
    }

    pendingProtectionReason?.let { reason ->
        val message = when (reason) {
            BlockingProtectionUnavailableException.Reason.PROTECTION_PERMISSIONS_REQUIRED -> {
                R.string.blocking_permissions_required_desc
            }
            BlockingProtectionUnavailableException.Reason.MASTER_CREDENTIAL_REQUIRED -> {
                R.string.master_credential_required_to_block
            }
        }
        val confirmLabel = when (reason) {
            BlockingProtectionUnavailableException.Reason.PROTECTION_PERMISSIONS_REQUIRED -> {
                R.string.dopamine_open_permissions
            }
            BlockingProtectionUnavailableException.Reason.MASTER_CREDENTIAL_REQUIRED -> {
                R.string.master_credential_create_action
            }
        }

        AlertDialog(
            onDismissRequest = { pendingProtectionReason = null },
            title = { Text(stringResource(R.string.dopamine_requirement_title)) },
            text = { Text(stringResource(message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingProtectionReason = null
                        when (reason) {
                            BlockingProtectionUnavailableException
                                .Reason.PROTECTION_PERMISSIONS_REQUIRED ->
                                context.startActivity(
                                    PermissionsActivity.createPendingProtectionIntent(context)
                                )

                            BlockingProtectionUnavailableException
                                .Reason.MASTER_CREDENTIAL_REQUIRED ->
                                showMasterCredentialSetup = true
                        }
                    }
                ) {
                    Text(stringResource(confirmLabel))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingProtectionReason = null }) {
                    Text(stringResource(R.string.status_close))
                }
            }
        )
    }

    Scaffold(
        containerColor = DarkBg,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.dopamine_title), color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = ::navigateBack) {
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
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(DarkBg)
                .padding(innerPadding)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp)
        ) {
            when (page) {
                TimeBlockConfigPage.TERMS -> TimeBlockTermsPage(
                    appName = appName,
                    apps = apps,
                    sites = sites,
                    termsAccepted = termsAccepted,
                    canContinue = canContinue,
                    onTermsAcceptedChange = { termsAccepted = it },
                    onContinue = { page = TimeBlockConfigPage.SCHEDULE },
                    onBack = onBack
                )

                TimeBlockConfigPage.SCHEDULE -> TimeBlockSchedulePage(
                    durationUnit = durationUnit,
                    amountText = amountText,
                    availableUnits = availableUnits,
                    selectedDays = selectedDays,
                    websiteCompanionOptions = availableWebsiteCompanionOptions,
                    selectedCompanionDomains = selectedCompanionDomains,
                    appCompanionOptions = availableAppCompanionOptions,
                    selectedCompanionPackages = selectedCompanionPackages,
                    isSaving = isSaving,
                    canSave = canSave,
                    onDurationUnitChange = { durationUnit = it },
                    onAmountChange = { amountText = it },
                    onSelectedCompanionDomainsChange = { selectedCompanionDomains = it },
                    onSelectedCompanionPackagesChange = { selectedCompanionPackages = it },
                    onToggleDay = { day ->
                        selectedDays = if (day in selectedDays) {
                            selectedDays - day
                        } else {
                            selectedDays + day
                        }
                    },
                    onActivate = {
                        if (isSaving) return@TimeBlockSchedulePage
                        isSaving = true
                        scope.launch {
                            try {
                                val resolved = duration ?: run {
                                    isSaving = false
                                    return@launch
                                }
                                sessionManager.startTimeSession(
                                    days = 0,
                                    hours = when (resolved) {
                                        is BlockDurationPolicy.Duration.Finite -> resolved.totalHours
                                        BlockDurationPolicy.Duration.Forever -> 0
                                    },
                                    openEnded = resolved is BlockDurationPolicy.Duration.Forever,
                                    // A recorrência continua sendo de dia inteiro; a separação
                                    // em duas telas é somente de configuração/apresentação.
                                    isFixed24h = false,
                                    startHour = 0,
                                    endHour = 24,
                                    startMinute = 0,
                                    endMinute = 0,
                                    daysOfWeek = selectedDaysSerialized,
                                    apps = effectiveApps,
                                    sites = effectiveSites
                                )
                                isSaving = false
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.bloqueio_por_tempo_ativado),
                                    Toast.LENGTH_LONG
                                ).show()
                                onFinish()
                            } catch (cancelled: CancellationException) {
                                isSaving = false
                                throw cancelled
                            } catch (error: BlockingProtectionUnavailableException) {
                                isSaving = false
                                pendingProtectionReason = error.reason
                            } catch (error: Exception) {
                                isSaving = false
                                FocusGuardLogger.logError(
                                    "TimeBlockConfig",
                                    "Falha ao ativar bloqueio por tempo",
                                    error
                                )
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.blocking_failed_generic),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun TimeBlockTermsPage(
    appName: String,
    apps: List<String>,
    sites: List<String>,
    termsAccepted: Boolean,
    canContinue: Boolean,
    onTermsAcceptedChange: (Boolean) -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit
) {
    val totalTargets = selectedTargetCount(apps.size, sites.size)
    val resolvedAppName = resolveDisplayName(LocalContext.current, appName, apps)
    val targetSummary = if (sites.isEmpty()) {
        resolvedAppName
    } else {
        pluralStringResource(
            R.plurals.dopamine_target_count,
            totalTargets,
            totalTargets
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = stringResource(R.string.dopamine_terms_title),
            color = TextPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = targetSummary,
            color = AccentCyan,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = stringResource(R.string.dopamine_terms_warning),
            color = DangerRed,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = stringResource(R.string.dopamine_terms_irreversible),
            color = TextSecondary,
            fontSize = 13.sp
        )
        Card(
            colors = CardDefaults.cardColors(containerColor = DarkCard),
            border = BorderStroke(1.dp, DangerRed.copy(alpha = 0.45f))
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.Top
            ) {
                Checkbox(
                    checked = termsAccepted,
                    onCheckedChange = onTermsAcceptedChange,
                    colors = CheckboxDefaults.colors(checkedColor = DangerRed)
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = stringResource(R.string.dopamine_terms_accept),
                    color = TextPrimary,
                    fontSize = 13.sp
                )
            }
        }
        Button(
            onClick = onContinue,
            enabled = canContinue,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = DangerRed)
        ) {
            Text(stringResource(R.string.dopamine_continue))
        }
        TextButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(stringResource(R.string.status_close), color = TextHint)
        }
    }
}

@Composable
private fun TimeBlockSchedulePage(
    durationUnit: BlockDurationPolicy.Unit,
    amountText: String,
    availableUnits: List<BlockDurationPolicy.Unit>,
    selectedDays: Set<Int>,
    websiteCompanionOptions: List<AssociatedBlockTargets.WebsiteOption>,
    selectedCompanionDomains: Set<String>,
    appCompanionOptions: List<AssociatedBlockTargets.AppOption>,
    selectedCompanionPackages: Set<String>,
    isSaving: Boolean,
    canSave: Boolean,
    onDurationUnitChange: (BlockDurationPolicy.Unit) -> Unit,
    onAmountChange: (String) -> Unit,
    onSelectedCompanionDomainsChange: (Set<String>) -> Unit,
    onSelectedCompanionPackagesChange: (Set<String>) -> Unit,
    onToggleDay: (Int) -> Unit,
    onActivate: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = stringResource(R.string.dopamine_schedule_title),
            color = TextPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = stringResource(R.string.dopamine_schedule_subtitle),
            color = TextSecondary,
            fontSize = 13.sp
        )
        DurationSelector(
            durationUnit = durationUnit,
            amountText = amountText,
            availableUnits = availableUnits,
            onDurationUnitChange = onDurationUnitChange,
            onAmountChange = onAmountChange
        )
        if (websiteCompanionOptions.isNotEmpty()) {
            CompanionWebsitePicker(
                options = websiteCompanionOptions,
                selectedDomains = selectedCompanionDomains,
                onSelectionChange = onSelectedCompanionDomainsChange
            )
        }
        if (appCompanionOptions.isNotEmpty()) {
            CompanionAppPicker(
                options = appCompanionOptions,
                selectedPackages = selectedCompanionPackages,
                onSelectionChange = onSelectedCompanionPackagesChange
            )
        }
        WeekdaySelector(
            selectedDays = selectedDays,
            onToggleDay = onToggleDay
        )
        Button(
            onClick = onActivate,
            enabled = canSave && !isSaving,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = DangerRed)
        ) {
            Text(
                if (isSaving) {
                    stringResource(R.string.dopamine_activating)
                } else {
                    stringResource(R.string.dopamine_activate)
                }
            )
        }
    }
}

@Composable
private fun DurationSelector(
    durationUnit: BlockDurationPolicy.Unit,
    amountText: String,
    availableUnits: List<BlockDurationPolicy.Unit>,
    onDurationUnitChange: (BlockDurationPolicy.Unit) -> Unit,
    onAmountChange: (String) -> Unit
) {
    val units = listOf(
        BlockDurationPolicy.Unit.HOURS to R.string.unit_hours,
        BlockDurationPolicy.Unit.DAYS to R.string.unit_days,
        BlockDurationPolicy.Unit.MONTHS to R.string.unit_months,
        BlockDurationPolicy.Unit.YEARS to R.string.unit_years,
        BlockDurationPolicy.Unit.FOREVER to R.string.unit_forever
    )

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = stringResource(R.string.dopamine_duration_label),
            color = TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            units.forEach { (unit, labelRes) ->
                val enabled = unit in availableUnits
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .alpha(if (enabled) 1f else 0.35f)
                        .clickable(enabled = enabled) { onDurationUnitChange(unit) },
                    color = if (durationUnit == unit) AccentCyan.copy(alpha = 0.18f) else DarkCard,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(
                        1.dp,
                        if (durationUnit == unit) AccentCyan else TextHint.copy(alpha = 0.35f)
                    )
                ) {
                    Box(
                        modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(labelRes),
                            color = if (durationUnit == unit) AccentCyan else TextSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
        if (durationUnit != BlockDurationPolicy.Unit.FOREVER) {
            DurationAmountField(
                amountText = amountText,
                onAmountChange = onAmountChange
            )
        }
    }
}

@Composable
private fun DurationAmountField(
    amountText: String,
    onAmountChange: (String) -> Unit
) {
    androidx.compose.material3.OutlinedTextField(
        value = amountText,
        onValueChange = { raw ->
            if (raw.length <= 4 && raw.all(Char::isDigit)) onAmountChange(raw)
        },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text(stringResource(R.string.dopamine_duration_amount)) },
        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary,
            cursorColor = AccentCyan,
            focusedBorderColor = AccentCyan,
            unfocusedBorderColor = TextHint.copy(alpha = 0.5f),
            focusedLabelColor = AccentCyan,
            unfocusedLabelColor = TextHint
        )
    )
}

@Composable
private fun WeekdaySelector(
    selectedDays: Set<Int>,
    onToggleDay: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.dopamine_weekdays_label),
            color = TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            DOPAMINE_WEEKDAYS.forEach { day ->
                val selected = day.calendarDay in selectedDays
                Surface(
                    modifier = Modifier
                        .size(38.dp)
                        .clickable { onToggleDay(day.calendarDay) },
                    shape = RoundedCornerShape(12.dp),
                    color = if (selected) AccentCyan.copy(alpha = 0.2f) else DarkCard,
                    border = BorderStroke(
                        1.dp,
                        if (selected) AccentCyan else TextHint.copy(alpha = 0.35f)
                    )
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(day.labelRes),
                            color = if (selected) AccentCyan else TextSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

private fun resolveDisplayName(
    context: Context,
    appName: String,
    packages: List<String>
): String {
    if (appName.isNotBlank()) return appName
    val packageName = packages.firstOrNull().orEmpty()
    val predefined = PredefinedApps.getAppByPackage(packageName)?.name
    if (!predefined.isNullOrBlank()) return predefined
    return runCatching {
        val info = context.packageManager.getApplicationInfo(packageName, 0)
        context.packageManager.getApplicationLabel(info).toString()
    }.getOrDefault(packageName)
}
