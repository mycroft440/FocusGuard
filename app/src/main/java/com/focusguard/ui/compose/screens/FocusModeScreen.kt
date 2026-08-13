package com.focusguard.ui.compose.screens

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LockClock
import androidx.compose.material.icons.filled.Message
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.focusguard.R
import com.focusguard.admin.DeviceOwnerManager
import com.focusguard.focusmode.FocusModeAppCatalog
import com.focusguard.focusmode.FocusModeManager
import com.focusguard.focusmode.FocusModePolicy
import com.focusguard.focusmode.FocusModeSelectableApp
import com.focusguard.focusmode.FocusModeSession
import com.focusguard.ui.compose.theme.AccentCyan
import com.focusguard.ui.compose.theme.CardBorder
import com.focusguard.ui.compose.theme.DarkCard
import com.focusguard.ui.compose.theme.TextHint
import com.focusguard.ui.compose.theme.TextPrimary
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun FocusModeScreen(
    manager: FocusModeManager,
    onStartLockTask: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val activeSession by manager.session.collectAsState()
    var apps by remember { mutableStateOf<List<FocusModeSelectableApp>>(emptyList()) }
    var isLoadingApps by remember { mutableStateOf(true) }
    var selectedPackages by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectionInitialized by remember { mutableStateOf(false) }
    var durationText by rememberSaveable { mutableStateOf("60") }
    var durationUnit by rememberSaveable {
        mutableStateOf(FocusModePolicy.DurationUnit.MINUTES)
    }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var showConfirmation by remember { mutableStateOf(false) }
    var termsAccepted by remember { mutableStateOf(false) }
    var isStarting by remember { mutableStateOf(false) }
    var startOutcome by remember { mutableStateOf<FocusModeManager.StartOutcome?>(null) }
    var showDeviceOwnerGuide by remember { mutableStateOf(false) }
    var permissionRevision by remember { mutableIntStateOf(0) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permissionRevision++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        isLoadingApps = true
        val loaded = manager.loadSelectableApps()
        apps = loaded
        if (!selectionInitialized) {
            selectedPackages = manager.initialSelectedPackages(loaded)
            selectionInitialized = true
        }
        isLoadingApps = false
    }

    val deviceOwnerManager = remember(context.applicationContext) {
        DeviceOwnerManager.getInstance(context.applicationContext)
    }
    val deviceOwnerActive = remember(permissionRevision, activeSession) {
        deviceOwnerManager.isDeviceOwnerActive()
    }
    val notificationAccessActive = remember(permissionRevision, activeSession) {
        manager.isNotificationAccessEnabled()
    }
    val mandatoryPackages = remember(context.applicationContext, permissionRevision) {
        FocusModeAppCatalog.mandatoryPackages(context.applicationContext)
    }

    if (showDeviceOwnerGuide) {
        DeviceOwnerSetupGuideDialog(
            onDismiss = { showDeviceOwnerGuide = false },
            onStateChanged = { permissionRevision++ }
        )
    }

    val durationMillis = FocusModePolicy.resolveDurationMillis(
        unit = durationUnit,
        amount = durationText.toIntOrNull()
    )

    if (showConfirmation && durationMillis != null) {
        FocusModeConsentDialog(
            durationMillis = durationMillis,
            accepted = termsAccepted,
            isStarting = isStarting,
            onAcceptedChange = { termsAccepted = it },
            onDismiss = {
                if (!isStarting) {
                    showConfirmation = false
                    termsAccepted = false
                }
            },
            onConfirm = {
                if (termsAccepted && !isStarting) {
                    isStarting = true
                    startOutcome = null
                    scope.launch {
                        val result = manager.start(durationMillis, selectedPackages)
                        isStarting = false
                        startOutcome = result.outcome
                        if (result.outcome == FocusModeManager.StartOutcome.STARTED) {
                            showConfirmation = false
                            termsAccepted = false
                            onStartLockTask()
                        }
                    }
                }
            }
        )
    }

    val session = activeSession
    if (session != null) {
        FocusModeActiveContent(
            session = session,
            apps = apps,
            mandatoryPackages = mandatoryPackages,
            manager = manager
        )
    } else {
        FocusModeSetupContent(
            apps = apps,
            mandatoryPackages = mandatoryPackages,
            isLoadingApps = isLoadingApps,
            selectedPackages = selectedPackages,
            onTogglePackage = { packageName ->
                val updatedSelection = if (packageName in selectedPackages) {
                    selectedPackages - packageName
                } else {
                    selectedPackages + packageName
                }
                selectedPackages = updatedSelection
                manager.saveDraftPackages(updatedSelection)
            },
            durationText = durationText,
            onDurationTextChange = {
                durationText = it.filter(Char::isDigit)
                startOutcome = null
            },
            durationUnit = durationUnit,
            onDurationUnitChange = {
                durationUnit = it
                startOutcome = null
            },
            durationValid = durationMillis != null,
            searchQuery = searchQuery,
            onSearchQueryChange = { searchQuery = it },
            deviceOwnerActive = deviceOwnerActive,
            notificationAccessActive = notificationAccessActive,
            onOpenDeviceOwnerGuide = { showDeviceOwnerGuide = true },
            onOpenNotificationAccess = { openNotificationAccess(context) },
            isStarting = isStarting,
            startOutcome = startOutcome,
            onStart = {
                when {
                    !deviceOwnerActive -> showDeviceOwnerGuide = true
                    !notificationAccessActive -> openNotificationAccess(context)
                    durationMillis == null -> startOutcome =
                        FocusModeManager.StartOutcome.INVALID_DURATION
                    else -> {
                        termsAccepted = false
                        showConfirmation = true
                    }
                }
            }
        )
    }
}

@Composable
private fun FocusModeSetupContent(
    apps: List<FocusModeSelectableApp>,
    mandatoryPackages: Set<String>,
    isLoadingApps: Boolean,
    selectedPackages: Set<String>,
    onTogglePackage: (String) -> Unit,
    durationText: String,
    onDurationTextChange: (String) -> Unit,
    durationUnit: FocusModePolicy.DurationUnit,
    onDurationUnitChange: (FocusModePolicy.DurationUnit) -> Unit,
    durationValid: Boolean,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    deviceOwnerActive: Boolean,
    notificationAccessActive: Boolean,
    onOpenDeviceOwnerGuide: () -> Unit,
    onOpenNotificationAccess: () -> Unit,
    isStarting: Boolean,
    startOutcome: FocusModeManager.StartOutcome?,
    onStart: () -> Unit
) {
    val selectableApps = remember(apps, mandatoryPackages, searchQuery) {
        apps.filterNot { it.packageName in mandatoryPackages }
            .filter {
                searchQuery.isBlank() ||
                    it.appName.contains(searchQuery, ignoreCase = true) ||
                    it.packageName.contains(searchQuery, ignoreCase = true)
            }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.LockClock,
                    contentDescription = null,
                    tint = AccentCyan,
                    modifier = Modifier.size(36.dp)
                )
                Spacer(Modifier.size(12.dp))
                Column {
                    Text(
                        stringResource(R.string.focus_mode_title),
                        color = TextPrimary,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        stringResource(R.string.focus_mode_subtitle),
                        color = TextHint,
                        fontSize = 13.sp
                    )
                }
            }
        }

        item {
            FocusInfoCard(
                title = stringResource(R.string.focus_mode_how_title),
                description = stringResource(R.string.focus_mode_how_description)
            )
        }

        item {
            Text(
                stringResource(R.string.focus_mode_requirements_title),
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp
            )
        }

        item {
            FocusRequirementCard(
                title = stringResource(R.string.focus_mode_device_owner_title),
                description = stringResource(R.string.focus_mode_device_owner_description),
                ready = deviceOwnerActive,
                actionLabel = stringResource(R.string.focus_mode_configure_device_owner),
                onAction = onOpenDeviceOwnerGuide
            )
        }

        item {
            FocusRequirementCard(
                title = stringResource(R.string.focus_mode_notification_access_title),
                description = stringResource(
                    R.string.focus_mode_notification_access_description
                ),
                ready = notificationAccessActive,
                actionLabel = stringResource(R.string.focus_mode_open_notification_access),
                onAction = onOpenNotificationAccess
            )
        }

        item {
            Text(
                stringResource(R.string.focus_mode_essentials_title),
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp
            )
            Text(
                stringResource(R.string.focus_mode_essentials_description),
                color = TextHint,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                EssentialBadge(
                    icon = Icons.Default.Phone,
                    text = stringResource(R.string.focus_mode_phone_emergency),
                    modifier = Modifier.weight(1f)
                )
                EssentialBadge(
                    icon = Icons.Default.Message,
                    text = stringResource(R.string.focus_mode_sms),
                    modifier = Modifier.weight(1f)
                )
            }
        }

        item {
            Text(
                stringResource(R.string.focus_mode_duration_title),
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp
            )
        }

        item {
            OutlinedTextField(
                value = durationText,
                onValueChange = onDurationTextChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.focus_mode_duration_value)) },
                leadingIcon = { Icon(Icons.Default.AccessTime, contentDescription = null) },
                isError = durationText.isNotBlank() && !durationValid,
                supportingText = if (!durationValid) {
                    { Text(stringResource(R.string.focus_mode_duration_invalid)) }
                } else null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DurationUnitChip(
                    label = stringResource(R.string.focus_mode_minutes),
                    selected = durationUnit == FocusModePolicy.DurationUnit.MINUTES,
                    onClick = {
                        onDurationUnitChange(FocusModePolicy.DurationUnit.MINUTES)
                    }
                )
                DurationUnitChip(
                    label = stringResource(R.string.focus_mode_hours),
                    selected = durationUnit == FocusModePolicy.DurationUnit.HOURS,
                    onClick = { onDurationUnitChange(FocusModePolicy.DurationUnit.HOURS) }
                )
                DurationUnitChip(
                    label = stringResource(R.string.focus_mode_days),
                    selected = durationUnit == FocusModePolicy.DurationUnit.DAYS,
                    onClick = { onDurationUnitChange(FocusModePolicy.DurationUnit.DAYS) }
                )
            }
        }

        item {
            Text(
                stringResource(
                    R.string.focus_mode_allowed_apps_title,
                    selectedPackages.size
                ),
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp
            )
            Text(
                stringResource(R.string.focus_mode_whatsapp_hint),
                color = TextHint,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        item {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.focus_mode_search_apps)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true
            )
        }

        if (isLoadingApps) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = AccentCyan)
                }
            }
        } else if (selectableApps.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.focus_mode_no_apps),
                    color = TextHint,
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    textAlign = TextAlign.Center
                )
            }
        } else {
            items(selectableApps, key = { it.packageName }) { app ->
                AppSelectionItem(
                    app = SelectableAppUi(
                        packageName = app.packageName,
                        appName = app.appName,
                        isSelected = app.packageName in selectedPackages
                    ),
                    onToggle = { onTogglePackage(app.packageName) }
                )
            }
        }

        item {
            FocusModeStartError(startOutcome)
        }

        item {
            Button(
                onClick = onStart,
                enabled = !isStarting && durationValid,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                shape = RoundedCornerShape(16.dp)
            ) {
                if (isStarting) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(22.dp)
                    )
                } else {
                    Text(
                        stringResource(R.string.focus_mode_start),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun FocusModeActiveContent(
    session: FocusModeSession,
    apps: List<FocusModeSelectableApp>,
    mandatoryPackages: Set<String>,
    manager: FocusModeManager
) {
    val context = LocalContext.current
    var nowMillis by remember(session.endTimeMillis) {
        mutableStateOf(System.currentTimeMillis())
    }
    val allowedApps = remember(apps, session.allowedPackages, mandatoryPackages) {
        apps.filter {
            it.packageName in session.allowedPackages &&
                it.packageName !in mandatoryPackages
        }
    }

    BackHandler(enabled = true) {
        // The root of an active kiosk must not be dismissed with the Back button.
    }

    LaunchedEffect(session.endTimeMillis) {
        while (nowMillis < session.endTimeMillis) {
            delay(1_000L)
            nowMillis = System.currentTimeMillis()
        }
        manager.finishExpiredSession()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Default.LockClock,
                    contentDescription = null,
                    tint = AccentCyan,
                    modifier = Modifier.size(44.dp)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.focus_mode_active_title),
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 23.sp
                )
                Text(
                    formatRemaining(session.remainingMillis(nowMillis)),
                    color = AccentCyan,
                    fontWeight = FontWeight.Bold,
                    fontSize = 34.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
                Text(
                    stringResource(R.string.focus_mode_remaining),
                    color = TextHint,
                    fontSize = 13.sp
                )
            }
        }

        item {
            FocusInfoCard(
                title = stringResource(R.string.focus_mode_kiosk_title),
                description = stringResource(R.string.focus_mode_kiosk_description)
            )
        }

        item {
            Text(
                stringResource(R.string.focus_mode_essential_actions),
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = {
                        launchFocusIntent(context, FocusModeAppCatalog.phoneIntent())
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentCyan)
                ) {
                    Icon(Icons.Default.Phone, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(R.string.focus_mode_call))
                }
                Button(
                    onClick = {
                        launchFocusIntent(context, FocusModeAppCatalog.smsIntent())
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentCyan)
                ) {
                    Icon(Icons.Default.Message, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(R.string.focus_mode_open_sms))
                }
            }
        }

        item {
            Text(
                stringResource(R.string.focus_mode_available_apps),
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp
            )
        }

        if (allowedApps.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.focus_mode_only_essentials),
                    color = TextHint,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
        } else {
            items(allowedApps, key = { it.packageName }) { app ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            manager.createOpenAppIntent(app.packageName)?.let {
                                launchFocusIntent(context, it)
                            }
                        },
                    colors = CardDefaults.cardColors(containerColor = DarkCard),
                    border = BorderStroke(1.dp, CardBorder),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = AccentCyan
                        )
                        Spacer(Modifier.size(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(app.appName, color = TextPrimary, fontWeight = FontWeight.Bold)
                            Text(app.packageName, color = TextHint, fontSize = 11.sp)
                        }
                        Text(
                            stringResource(R.string.action_open),
                            color = AccentCyan,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        if (session.nonSuspendablePackages.isNotEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(Modifier.size(10.dp))
                        Text(
                            stringResource(
                                R.string.focus_mode_system_apps_notice,
                                session.nonSuspendablePackages.size
                            ),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }

        item {
            Text(
                stringResource(R.string.focus_mode_no_early_stop),
                color = TextHint,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun FocusModeConsentDialog(
    durationMillis: Long,
    accepted: Boolean,
    isStarting: Boolean,
    onAcceptedChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.focus_mode_terms_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(
                        R.string.focus_mode_terms_duration,
                        formatRemaining(durationMillis)
                    )
                )
                Text(stringResource(R.string.focus_mode_terms_effects))
                Text(stringResource(R.string.focus_mode_terms_essentials))
                Text(stringResource(R.string.focus_mode_terms_exit))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !isStarting) {
                            onAcceptedChange(!accepted)
                        },
                    verticalAlignment = Alignment.Top
                ) {
                    Checkbox(
                        checked = accepted,
                        onCheckedChange = onAcceptedChange,
                        enabled = !isStarting
                    )
                    Text(
                        stringResource(R.string.focus_mode_terms_accept),
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = accepted && !isStarting
            ) {
                Text(stringResource(R.string.focus_mode_confirm_start))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isStarting) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun FocusRequirementCard(
    title: String,
    description: String,
    ready: Boolean,
    actionLabel: String,
    onAction: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        border = BorderStroke(1.dp, if (ready) AccentCyan.copy(alpha = 0.5f) else CardBorder),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (ready) Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = null,
                tint = if (ready) AccentCyan else MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = TextPrimary, fontWeight = FontWeight.Bold)
                Text(description, color = TextHint, fontSize = 12.sp)
                Text(
                    if (ready) {
                        stringResource(R.string.focus_mode_requirement_ready)
                    } else {
                        stringResource(R.string.focus_mode_requirement_pending)
                    },
                    color = if (ready) AccentCyan else MaterialTheme.colorScheme.error,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            if (!ready) {
                TextButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

@Composable
private fun EssentialBadge(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = AccentCyan.copy(alpha = 0.1f)),
        border = BorderStroke(1.dp, AccentCyan.copy(alpha = 0.35f)),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = AccentCyan)
            Spacer(Modifier.size(8.dp))
            Text(text, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        }
    }
}

@Composable
private fun FocusInfoCard(title: String, description: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        border = BorderStroke(1.dp, CardBorder),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Text(title, color = TextPrimary, fontWeight = FontWeight.Bold)
            Text(
                description,
                color = TextHint,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 5.dp)
            )
        }
    }
}

@Composable
private fun DurationUnitChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) }
    )
}

@Composable
private fun FocusModeStartError(outcome: FocusModeManager.StartOutcome?) {
    val message = when (outcome) {
        FocusModeManager.StartOutcome.INVALID_DURATION ->
            stringResource(R.string.focus_mode_duration_invalid)
        FocusModeManager.StartOutcome.DEVICE_OWNER_REQUIRED ->
            stringResource(R.string.focus_mode_device_owner_required)
        FocusModeManager.StartOutcome.NOTIFICATION_ACCESS_REQUIRED ->
            stringResource(R.string.focus_mode_notification_access_required)
        FocusModeManager.StartOutcome.STRICT_POMODORO_ACTIVE ->
            stringResource(R.string.focus_mode_pomodoro_conflict)
        FocusModeManager.StartOutcome.ENFORCEMENT_FAILED ->
            stringResource(R.string.focus_mode_start_failed)
        FocusModeManager.StartOutcome.STARTED,
        null -> null
    }
    if (message != null) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.error,
            fontSize = 13.sp,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private fun formatRemaining(milliseconds: Long): String {
    val totalSeconds = (milliseconds.coerceAtLeast(0L) + 999L) / 1_000L
    val days = totalSeconds / 86_400L
    val hours = (totalSeconds % 86_400L) / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (days > 0L) {
        String.format(Locale.getDefault(), "%dd %02d:%02d:%02d", days, hours, minutes, seconds)
    } else {
        String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
    }
}

private fun openNotificationAccess(context: Context) {
    val opened = runCatching {
        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        true
    }.getOrDefault(false)
    if (!opened) {
        runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }
}

private fun launchFocusIntent(context: Context, intent: Intent) {
    // The app list can change after the session starts; stale entries stay harmless.
    runCatching { context.startActivity(intent) }
}
