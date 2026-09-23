package com.focusguard.ui.compose.screens

import android.app.TimePickerDialog
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.focusguard.receiver.BlockingScheduleCalculator
import com.focusguard.security.BlockDurationPolicy
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
import kotlinx.coroutines.launch

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
    onFinish: () -> Unit,
    mode: TimeBlockConfigMode = TimeBlockConfigMode.CONTINUOUS
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sessionManager = remember(context) { BlockingSessionManager.getInstance(context) }
    val isScheduled = mode == TimeBlockConfigMode.DAILY_PERIODS

    var page by remember { mutableStateOf(TimeBlockConfigPage.TERMS) }
    val websiteCompanionOptions = remember(apps) {
        AssociatedBlockTargets.websiteCompanionsForApps(apps)
    }
    val appCompanionOptions = remember(sites) {
        AssociatedBlockTargets.appCompanionsForWebsiteRules(sites)
    }
    var selectedCompanionDomains by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedCompanionPackages by remember { mutableStateOf<Set<String>>(emptySet()) }

    val availableWebsiteCompanionOptions = remember(websiteCompanionOptions, sites) {
        websiteCompanionOptions.filterNot { option ->
            BlockingSessionManager.isWebsiteRuleCoveredBy(option.domain, sites)
        }
    }
    val availableAppCompanionOptions = remember(appCompanionOptions, apps) {
        appCompanionOptions.filterNot { option ->
            option.packageName in apps
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
    var startHour by remember { mutableIntStateOf(0) }
    var startMinute by remember { mutableIntStateOf(0) }
    var endHour by remember { mutableIntStateOf(24) }
    var endMinute by remember { mutableIntStateOf(0) }
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
    val timeWindowValid = !isScheduled || BlockingScheduleCalculator.isValidRecurringWindow(
        startHour = startHour,
        startMinute = startMinute,
        endHour = endHour,
        endMinute = endMinute
    )
    val canContinue = termsAccepted && hasTargets
    val canSave = duration != null &&
        termsAccepted &&
        hasTargets &&
        (!isScheduled || (selectedDays.isNotEmpty() && timeWindowValid))

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
                title = {
                    Text(
                        stringResource(
                            if (isScheduled) {
                                R.string.block_type_periods_title
                            } else {
                                R.string.dopamine_title
                            }
                        ),
                        color = TextPrimary
                    )
                },
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
                    mode = mode,
                    termsAccepted = termsAccepted,
                    canContinue = canContinue,
                    onTermsAcceptedChange = { termsAccepted = it },
                    onContinue = { page = TimeBlockConfigPage.SCHEDULE },
                    onBack = onBack
                )

                TimeBlockConfigPage.SCHEDULE -> TimeBlockSchedulePage(
                    mode = mode,
                    durationUnit = durationUnit,
                    amountText = amountText,
                    availableUnits = availableUnits,
                    selectedDays = selectedDays,
                    startHour = startHour,
                    startMinute = startMinute,
                    endHour = endHour,
                    endMinute = endMinute,
                    timeWindowValid = timeWindowValid,
                    websiteCompanionOptions = availableWebsiteCompanionOptions,
                    selectedCompanionDomains = selectedCompanionDomains,
                    appCompanionOptions = availableAppCompanionOptions,
                    selectedCompanionPackages = selectedCompanionPackages,
                    isSaving = isSaving,
                    canSave = canSave,
                    onDurationUnitChange = { durationUnit = it },
                    onAmountChange = { amountText = it },
                    onEditStartTime = {
                        TimePickerDialog(
                            context,
                            { _, hour, minute ->
                                startHour = hour
                                startMinute = minute
                            },
                            startHour,
                            startMinute,
                            true
                        ).show()
                    },
                    onEditEndTime = {
                        TimePickerDialog(
                            context,
                            { _, hour, minute ->
                                endHour = hour
                                endMinute = minute
                            },
                            if (endHour == 24) 0 else endHour,
                            endMinute,
                            true
                        ).show()
                    },
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
                                    isFixed24h = !isScheduled,
                                    startHour = if (isScheduled) startHour else 0,
                                    endHour = if (isScheduled) endHour else 24,
                                    startMinute = if (isScheduled) startMinute else 0,
                                    endMinute = if (isScheduled) endMinute else 0,
                                    daysOfWeek = if (isScheduled) selectedDaysSerialized else "",
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
                                    context.getString(R.string.erro_ao_iniciar_sessao),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    },
                    onBack = { page = TimeBlockConfigPage.TERMS }
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
    mode: TimeBlockConfigMode,
    termsAccepted: Boolean,
    canContinue: Boolean,
    onTermsAcceptedChange: (Boolean) -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit
) {
    DopamineHowItWorksCard(
        mode = mode,
        termsAccepted = termsAccepted,
        onTermsAcceptedChange = onTermsAcceptedChange
    )

    Spacer(modifier = Modifier.height(16.dp))

    SelectedAppsSummary(
        appName = appName,
        apps = apps,
        sites = sites
    )

    Spacer(modifier = Modifier.height(16.dp))

    Surface(
        color = AccentCyan.copy(alpha = 0.10f),
        shape = RoundedCornerShape(14.dp)
    ) {
        Text(
            text = stringResource(
                if (mode == TimeBlockConfigMode.DAILY_PERIODS) {
                    R.string.block_periods_simple_mode_info
                } else {
                    R.string.dopamine_simple_mode_info
                }
            ),
            color = TextSecondary,
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            fontSize = 13.sp
        )
    }

    Spacer(modifier = Modifier.height(24.dp))

    Button(
        onClick = onContinue,
        enabled = canContinue,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
        shape = RoundedCornerShape(16.dp)
    ) {
        Text(
            stringResource(
                if (mode == TimeBlockConfigMode.DAILY_PERIODS) {
                    R.string.dopamine_continue_to_schedule
                } else {
                    R.string.final_config_proceed
                }
            ),
            color = DarkBg,
            fontWeight = FontWeight.Bold
        )
    }

    Spacer(modifier = Modifier.height(10.dp))

    TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.common_back), color = TextSecondary)
    }
}

@Composable
private fun TimeBlockSchedulePage(
    mode: TimeBlockConfigMode,
    durationUnit: BlockDurationPolicy.Unit,
    amountText: String,
    availableUnits: List<BlockDurationPolicy.Unit>,
    selectedDays: Set<Int>,
    startHour: Int,
    startMinute: Int,
    endHour: Int,
    endMinute: Int,
    timeWindowValid: Boolean,
    websiteCompanionOptions: List<AssociatedBlockTargets.WebsiteCompanion>,
    selectedCompanionDomains: Set<String>,
    appCompanionOptions: List<AssociatedBlockTargets.AppCompanion>,
    selectedCompanionPackages: Set<String>,
    isSaving: Boolean,
    canSave: Boolean,
    onDurationUnitChange: (BlockDurationPolicy.Unit) -> Unit,
    onAmountChange: (String) -> Unit,
    onEditStartTime: () -> Unit,
    onEditEndTime: () -> Unit,
    onSelectedCompanionDomainsChange: (Set<String>) -> Unit,
    onSelectedCompanionPackagesChange: (Set<String>) -> Unit,
    onToggleDay: (Int) -> Unit,
    onActivate: () -> Unit,
    onBack: () -> Unit
) {
    val isScheduled = mode == TimeBlockConfigMode.DAILY_PERIODS

    if (isScheduled) {
        Text(
            text = stringResource(R.string.dopamine_schedule_config_title),
            color = TextPrimary,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.dopamine_schedule_config_subtitle),
            color = TextSecondary,
            fontSize = 13.sp
        )

        Spacer(modifier = Modifier.height(18.dp))

        DopamineWeekdaySelector(
            selectedDays = selectedDays,
            onToggleDay = onToggleDay
        )

        Spacer(modifier = Modifier.height(16.dp))

        DopamineTimeWindowCard(
            startHour = startHour,
            startMinute = startMinute,
            endHour = endHour,
            endMinute = endMinute,
            isValid = timeWindowValid,
            onEditStartTime = onEditStartTime,
            onEditEndTime = onEditEndTime
        )

        Spacer(modifier = Modifier.height(16.dp))
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = stringResource(
                    if (isScheduled) {
                        R.string.dopamine_duration_days_question
                    } else {
                        R.string.dopamine_duration_question
                    }
                ),
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(14.dp))
            BlockDurationPicker(
                unit = durationUnit,
                amountText = amountText,
                onUnitChange = onDurationUnitChange,
                onAmountChange = onAmountChange,
                accent = DangerRed,
                units = availableUnits
            )
            Spacer(modifier = Modifier.height(14.dp))
            Surface(
                color = DangerRed.copy(alpha = 0.10f),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(
                    text = if (durationUnit == BlockDurationPolicy.Unit.FOREVER) {
                        stringResource(R.string.dopamine_duration_forever_warning)
                    } else {
                        stringResource(R.string.dopamine_warning)
                    },
                    color = DangerRed,
                    modifier = Modifier.padding(12.dp),
                    textAlign = TextAlign.Start
                )
            }
        }
    }

    if (websiteCompanionOptions.isNotEmpty()) {
        Spacer(modifier = Modifier.height(16.dp))
        AssociatedWebsiteOptionsCard(
            options = websiteCompanionOptions,
            selectedDomains = selectedCompanionDomains,
            onSelectedDomainsChange = onSelectedCompanionDomainsChange
        )
    }

    if (appCompanionOptions.isNotEmpty()) {
        Spacer(modifier = Modifier.height(16.dp))
        AssociatedAppOptionsCard(
            options = appCompanionOptions,
            selectedPackages = selectedCompanionPackages,
            onSelectedPackagesChange = onSelectedCompanionPackagesChange
        )
    }

    Spacer(modifier = Modifier.height(24.dp))

    Button(
        onClick = onActivate,
        enabled = canSave && !isSaving,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
        shape = RoundedCornerShape(16.dp)
    ) {
        Text(
            stringResource(
                if (isScheduled) {
                    R.string.block_periods_activate
                } else {
                    R.string.dopamine_activate
                }
            ),
            color = DarkBg,
            fontWeight = FontWeight.Bold
        )
    }

    Spacer(modifier = Modifier.height(10.dp))

    TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.common_back), color = TextSecondary)
    }
}

@Composable
private fun DopamineTimeWindowCard(
    startHour: Int,
    startMinute: Int,
    endHour: Int,
    endMinute: Int,
    isValid: Boolean,
    onEditStartTime: () -> Unit,
    onEditEndTime: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = stringResource(R.string.dopamine_time_window_question),
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.dopamine_time_window_hint),
                color = TextHint,
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onEditStartTime,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = stringResource(R.string.dopamine_start_time),
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                        Text(
                            text = formatBlockTime(startHour, startMinute),
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                    }
                }
                OutlinedButton(
                    onClick = onEditEndTime,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = stringResource(R.string.dopamine_end_time),
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                        Text(
                            text = formatBlockTime(endHour, endMinute),
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                    }
                }
            }
            if (!isValid) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.dopamine_time_window_invalid),
                    color = DangerRed,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun SelectedAppsSummary(
    appName: String,
    apps: List<String>,
    sites: List<String>
) {
    val context = LocalContext.current
    val totalTargets = selectedTargetCount(apps.size, sites.size)
    val labels = remember(apps, context) {
        apps.associateWith { packageName -> resolveAppLabel(context, packageName) }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = pluralStringResource(
                    R.plurals.dopamine_selected_targets_count,
                    totalTargets,
                    totalTargets
                ),
                color = TextPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.dopamine_schedule_description),
                color = TextSecondary,
                fontSize = 13.sp
            )

            if (apps.isNotEmpty()) {
                Spacer(modifier = Modifier.height(14.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clipToBounds(),
                    contentAlignment = Alignment.CenterStart
                ) {
                    apps.take(8).forEachIndexed { index, packageName ->
                        val fade = when (index) {
                            0, 1, 2, 3 -> 1f
                            4 -> 0.70f
                            5 -> 0.44f
                            6 -> 0.24f
                            else -> 0.12f
                        }
                        FocusGuardAppIcon(
                            packageName = packageName,
                            appName = labels[packageName] ?: packageName,
                            modifier = Modifier
                                .offset(x = (index * 38).dp)
                                .size(44.dp)
                                .alpha(fade),
                            cornerRadius = 11.dp,
                            allowRemoteFallback = true
                        )
                    }
                }
            } else if (sites.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = sites.take(4).joinToString(", ") { WebsiteBlocker.displayRule(it) },
                    color = TextHint,
                    fontSize = 12.sp
                )
            } else if (appName.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.dopamine_configure_for, appName),
                    color = TextHint,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun DopamineHowItWorksCard(
    mode: TimeBlockConfigMode,
    termsAccepted: Boolean,
    onTermsAcceptedChange: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            val isScheduled = mode == TimeBlockConfigMode.DAILY_PERIODS
            Text(
                text = stringResource(
                    if (isScheduled) {
                        R.string.block_periods_terms_title
                    } else {
                        R.string.dopamine_terms_title
                    }
                ),
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
            Spacer(modifier = Modifier.height(10.dp))
            listOf(
                if (isScheduled) {
                    R.string.block_periods_terms_intro
                } else {
                    R.string.dopamine_terms_intro
                },
                if (isScheduled) {
                    R.string.dopamine_schedule_terms_how
                } else {
                    R.string.dopamine_terms_how
                },
                R.string.dopamine_terms_escape
            ).forEach { paragraph ->
                Text(
                    text = stringResource(paragraph),
                    color = TextSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(bottom = 10.dp)
                )
            }
            Text(
                text = stringResource(R.string.dopamine_terms_question),
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onTermsAcceptedChange(!termsAccepted) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = termsAccepted,
                    onCheckedChange = onTermsAcceptedChange,
                    colors = CheckboxDefaults.colors(checkedColor = DangerRed)
                )
                Spacer(modifier = Modifier.padding(horizontal = 2.dp))
                Text(
                    text = stringResource(
                        if (isScheduled) {
                            R.string.block_periods_terms_accept
                        } else {
                            R.string.dopamine_terms_accept
                        }
                    ),
                    color = TextPrimary,
                    fontSize = 13.sp
                )
            }
        }
    }
}

@Composable
private fun DopamineWeekdaySelector(
    selectedDays: Set<Int>,
    onToggleDay: (Int) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = stringResource(R.string.dopamine_weekdays_question),
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.dopamine_weekdays_hint),
                color = TextHint,
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                DOPAMINE_WEEKDAYS.forEach { weekday ->
                    val selected = weekday.calendarDay in selectedDays
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clickable { onToggleDay(weekday.calendarDay) },
                        color = if (selected) {
                            AccentCyan.copy(alpha = 0.18f)
                        } else {
                            DarkBg
                        },
                        shape = RoundedCornerShape(11.dp),
                        border = BorderStroke(
                            1.dp,
                            if (selected) AccentCyan else TextHint.copy(alpha = 0.30f)
                        )
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = stringResource(weekday.labelRes),
                                color = if (selected) AccentCyan else TextHint,
                                fontSize = 11.sp,
                                fontWeight = if (selected) {
                                    FontWeight.Bold
                                } else {
                                    FontWeight.Medium
                                },
                                maxLines = 1
                            )
                        }
                    }
                }
            }
            if (selectedDays.isEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.dopamine_weekdays_required),
                    color = DangerRed,
                    fontSize = 12.sp
                )
            }
        }
    }
}

private fun formatBlockTime(hour: Int, minute: Int): String =
    "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"

private fun resolveAppLabel(context: Context, packageName: String): String {
    val installed = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrNull()
    if (!installed.isNullOrBlank()) return installed

    return PredefinedApps.PREVENTIVE_APPS
        .firstOrNull { it.packageName == packageName }
        ?.appName
        ?.takeIf(String::isNotBlank)
        ?: packageName.substringAfterLast('.').ifBlank { packageName }
}
