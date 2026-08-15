package com.focusguard.ui.compose.screens

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ArrowDropDown
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.core.graphics.drawable.toBitmap
import com.focusguard.R
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    var durationText by rememberSaveable { mutableStateOf("45") }
    var durationUnit by rememberSaveable {
        mutableStateOf(FocusModePolicy.DurationUnit.MINUTES)
    }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var grayscaleEnabled by rememberSaveable { mutableStateOf(false) }
    var showAppPicker by rememberSaveable { mutableStateOf(false) }
    var showPermissionReview by rememberSaveable { mutableStateOf(false) }
    var pendingFinalStart by rememberSaveable { mutableStateOf(false) }
    var showConfirmation by remember { mutableStateOf(false) }
    var termsAccepted by remember { mutableStateOf(false) }
    var isStarting by remember { mutableStateOf(false) }
    var startOutcome by remember { mutableStateOf<FocusModeManager.StartOutcome?>(null) }
    var permissionRevision by remember { mutableIntStateOf(0) }

    LaunchedEffect(durationUnit) {
        if (durationUnit == FocusModePolicy.DurationUnit.DAYS) {
            durationUnit = FocusModePolicy.DurationUnit.HOURS
            startOutcome = null
        }
    }

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

    val accessibilityActive = remember(permissionRevision, activeSession) {
        manager.isAccessibilityServiceEnabled()
    }
    val notificationAccessActive = remember(permissionRevision, activeSession) {
        manager.isNotificationAccessEnabled()
    }
    val mandatoryPackages = remember(context.applicationContext, permissionRevision) {
        FocusModeAppCatalog.mandatoryPackages(context.applicationContext)
    }

    val durationMillis = FocusModePolicy.resolveDurationMillis(
        unit = durationUnit,
        amount = durationText.toIntOrNull()
    )

    LaunchedEffect(
        permissionRevision,
        pendingFinalStart,
        accessibilityActive,
        notificationAccessActive
    ) {
        if (!pendingFinalStart ||
            !lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        ) {
            return@LaunchedEffect
        }
        if (!accessibilityActive || !notificationAccessActive) {
            showPermissionReview = true
        } else {
            pendingFinalStart = false
            showPermissionReview = false
            termsAccepted = false
            showConfirmation = true
        }
    }

    if (showConfirmation && durationMillis != null) {
        FocusModeConsentDialog(
            durationMillis = durationMillis,
            grayscaleEnabled = grayscaleEnabled,
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
                        val result = manager.start(
                            durationMillis = durationMillis,
                            selectedPackages = selectedPackages,
                            grayscaleEnabled = grayscaleEnabled
                        )
                        isStarting = false
                        startOutcome = result.outcome
                        when (result.outcome) {
                            FocusModeManager.StartOutcome.STARTED -> {
                                showConfirmation = false
                                termsAccepted = false
                                onStartLockTask()
                            }
                            FocusModeManager.StartOutcome.ACCESSIBILITY_REQUIRED,
                            FocusModeManager.StartOutcome.NOTIFICATION_ACCESS_REQUIRED -> {
                                showConfirmation = false
                                termsAccepted = false
                                pendingFinalStart = true
                                showPermissionReview = true
                            }
                            else -> Unit
                        }
                    }
                }
            }
        )
    }

    if (showAppPicker && activeSession == null) {
        AccessibleAppPickerDialog(
            apps = apps,
            mandatoryPackages = mandatoryPackages,
            selectedPackages = selectedPackages,
            searchQuery = searchQuery,
            onSearchQueryChange = { searchQuery = it },
            onTogglePackage = { packageName ->
                val updatedSelection = if (packageName in selectedPackages) {
                    selectedPackages - packageName
                } else {
                    selectedPackages + packageName
                }
                selectedPackages = updatedSelection
                manager.saveDraftPackages(updatedSelection)
            },
            onDismiss = {
                searchQuery = ""
                showAppPicker = false
            }
        )
    }

    if (showPermissionReview && activeSession == null) {
        FocusModeFinalPermissionsDialog(
            accessibilityActive = accessibilityActive,
            notificationAccessActive = notificationAccessActive,
            onDismiss = {
                pendingFinalStart = false
                showPermissionReview = false
            },
            onResolveNext = {
                showPermissionReview = false
                if (!accessibilityActive) {
                    openAccessibilitySettings(context)
                } else if (!notificationAccessActive) {
                    openNotificationAccess(context)
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
            grayscaleEnabled = grayscaleEnabled,
            onGrayscaleEnabledChange = { grayscaleEnabled = it },
            onAddApps = { showAppPicker = true },
            isStarting = isStarting,
            startOutcome = startOutcome,
            onStart = {
                when {
                    durationMillis == null -> startOutcome =
                        FocusModeManager.StartOutcome.INVALID_DURATION
                    !accessibilityActive || !notificationAccessActive -> {
                        startOutcome = null
                        pendingFinalStart = true
                        showPermissionReview = true
                    }
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
    durationText: String,
    onDurationTextChange: (String) -> Unit,
    durationUnit: FocusModePolicy.DurationUnit,
    onDurationUnitChange: (FocusModePolicy.DurationUnit) -> Unit,
    durationValid: Boolean,
    grayscaleEnabled: Boolean,
    onGrayscaleEnabledChange: (Boolean) -> Unit,
    onAddApps: () -> Unit,
    isStarting: Boolean,
    startOutcome: FocusModeManager.StartOutcome?,
    onStart: () -> Unit
) {
    val selectedApps = remember(apps, mandatoryPackages, selectedPackages) {
        apps.filter {
            it.packageName in selectedPackages && it.packageName !in mandatoryPackages
        }
    }
    val fixed45Selected = durationUnit == FocusModePolicy.DurationUnit.MINUTES &&
        durationText == "45"
    val fixed2hSelected = durationUnit == FocusModePolicy.DurationUnit.HOURS &&
        durationText == "2"
    val fixed5hSelected = durationUnit == FocusModePolicy.DurationUnit.HOURS &&
        durationText == "5"
    var customDurationOpen by rememberSaveable {
        mutableStateOf(!(fixed45Selected || fixed2hSelected || fixed5hSelected))
    }

    val durationLabel = when {
        fixed45Selected && !customDurationOpen ->
            stringResource(R.string.focus_mode_static_duration_45)
        fixed2hSelected && !customDurationOpen ->
            stringResource(R.string.focus_mode_static_duration_2h)
        fixed5hSelected && !customDurationOpen ->
            stringResource(R.string.focus_mode_static_duration_5h)
        else -> stringResource(
            R.string.focus_mode_static_custom_duration_value,
            durationText.ifBlank { "0" }
        )
    }
    val allowedSummary = when (selectedApps.size) {
        0 -> stringResource(R.string.focus_mode_static_no_extra_apps)
        1 -> stringResource(R.string.focus_mode_static_one_extra_app)
        else -> stringResource(
            R.string.focus_mode_static_many_extra_apps,
            selectedApps.size
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkCard),
            border = BorderStroke(1.dp, AccentCyan.copy(alpha = 0.35f)),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Text(
                    text = stringResource(R.string.focus_mode_static_purpose_title),
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(R.string.focus_mode_static_purpose_body),
                    color = TextHint,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
            }
        }

        Text(
            text = stringResource(R.string.focus_mode_static_duration_section),
            color = TextHint,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(top = 2.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            FocusDurationChip(
                label = stringResource(R.string.focus_mode_static_duration_45),
                selected = fixed45Selected && !customDurationOpen,
                modifier = Modifier.weight(1f),
                onClick = {
                    customDurationOpen = false
                    onDurationUnitChange(FocusModePolicy.DurationUnit.MINUTES)
                    onDurationTextChange("45")
                }
            )
            FocusDurationChip(
                label = stringResource(R.string.focus_mode_static_duration_2h),
                selected = fixed2hSelected && !customDurationOpen,
                modifier = Modifier.weight(1f),
                onClick = {
                    customDurationOpen = false
                    onDurationUnitChange(FocusModePolicy.DurationUnit.HOURS)
                    onDurationTextChange("2")
                }
            )
            FocusDurationChip(
                label = stringResource(R.string.focus_mode_static_duration_5h),
                selected = fixed5hSelected && !customDurationOpen,
                modifier = Modifier.weight(1f),
                onClick = {
                    customDurationOpen = false
                    onDurationUnitChange(FocusModePolicy.DurationUnit.HOURS)
                    onDurationTextChange("5")
                }
            )
            FocusDurationChip(
                label = stringResource(R.string.focus_mode_static_other),
                selected = customDurationOpen,
                modifier = Modifier.weight(1f),
                onClick = {
                    val wasFixed = fixed45Selected || fixed2hSelected || fixed5hSelected
                    customDurationOpen = true
                    onDurationUnitChange(FocusModePolicy.DurationUnit.MINUTES)
                    if (wasFixed) onDurationTextChange("60")
                }
            )
        }

        if (customDurationOpen) {
            Row(
                modifier = Modifier.fillMaxWidth().height(52.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = durationText,
                    onValueChange = onDurationTextChange,
                    modifier = Modifier.width(112.dp),
                    singleLine = true,
                    isError = durationText.isNotBlank() && !durationValid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                Text(
                    text = stringResource(R.string.focus_mode_static_custom_minutes),
                    color = TextHint,
                    fontSize = 13.sp
                )
                if (durationText.isNotBlank() && !durationValid) {
                    Text(
                        text = stringResource(R.string.focus_mode_duration_invalid),
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 10.sp,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = grayscaleEnabled,
                    role = Role.Switch,
                    onValueChange = onGrayscaleEnabledChange
                ),
            colors = CardDefaults.cardColors(containerColor = DarkCard),
            border = BorderStroke(1.dp, CardBorder),
            shape = RoundedCornerShape(14.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.fg_focus_grayscale),
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Text(
                        text = stringResource(R.string.focus_mode_static_grayscale_desc),
                        color = TextHint,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                Switch(checked = grayscaleEnabled, onCheckedChange = null)
            }
        }

        Text(
            text = stringResource(R.string.focus_mode_static_allowed_section),
            color = TextHint,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(top = 2.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FocusPrototypeAppTile(
                label = stringResource(R.string.focus_mode_static_phone),
                tag = stringResource(R.string.focus_mode_static_always),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    Icons.Default.Phone,
                    contentDescription = null,
                    tint = TextHint,
                    modifier = Modifier.size(32.dp)
                )
            }
            FocusPrototypeAppTile(
                label = stringResource(R.string.focus_mode_static_sms),
                tag = stringResource(R.string.focus_mode_static_always),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    Icons.Default.Message,
                    contentDescription = null,
                    tint = TextPrimary,
                    modifier = Modifier.size(32.dp)
                )
            }
            FocusPrototypeSelectedAppsTile(
                selectedApps = selectedApps,
                isLoading = isLoadingApps,
                onClick = onAddApps,
                modifier = Modifier.weight(1f)
            )
        }

        if (startOutcome != null) {
            FocusModeStartError(startOutcome)
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = onStart,
            enabled = !isStarting && durationValid && !isLoadingApps,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
            shape = RoundedCornerShape(14.dp)
        ) {
            if (isStarting) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(20.dp)
                )
            } else {
                Text(
                    stringResource(R.string.focus_mode_start),
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }
        }

        Text(
            text = stringResource(
                R.string.focus_mode_static_dock_summary,
                durationLabel,
                allowedSummary
            ),
            color = TextHint,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun FocusDurationChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier
            .height(44.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) AccentCyan else DarkCard
        ),
        border = BorderStroke(
            1.dp,
            if (selected) AccentCyan else CardBorder
        ),
        shape = RoundedCornerShape(24.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = label,
                color = if (selected) MaterialTheme.colorScheme.onPrimary else TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun FocusPrototypeAppTile(
    label: String,
    tag: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    icon: @Composable () -> Unit
) {
    Card(
        modifier = modifier
            .height(88.dp)
            .then(
                if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
            ),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        border = BorderStroke(1.dp, CardBorder),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 7.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            icon()
            Spacer(Modifier.height(4.dp))
            Text(
                text = label,
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (tag.isNotBlank()) {
                Text(
                    text = tag,
                    color = TextHint,
                    fontSize = 9.5.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun FocusPrototypeSelectedAppsTile(
    selectedApps: List<FocusModeSelectableApp>,
    isLoading: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val label = when {
        isLoading -> stringResource(R.string.focus_mode_static_apps)
        selectedApps.isEmpty() -> stringResource(R.string.focus_mode_add_apps)
        selectedApps.size == 1 -> selectedApps.first().appName
        else -> stringResource(
            R.string.focus_mode_static_apps_count,
            selectedApps.size
        )
    }
    val tag = when {
        isLoading -> stringResource(R.string.focus_mode_static_loading)
        selectedApps.isEmpty() -> stringResource(R.string.focus_mode_static_tap_choose)
        selectedApps.size == 1 -> stringResource(R.string.focus_mode_static_selected)
        else -> stringResource(R.string.focus_mode_static_tap_edit)
    }

    FocusPrototypeAppTile(
        label = label,
        tag = tag,
        modifier = modifier,
        onClick = if (isLoading) null else onClick
    ) {
        when {
            isLoading -> CircularProgressIndicator(
                color = AccentCyan,
                strokeWidth = 2.dp,
                modifier = Modifier.size(28.dp)
            )
            selectedApps.isEmpty() -> Icon(
                Icons.Default.Add,
                contentDescription = null,
                tint = AccentCyan,
                modifier = Modifier.size(32.dp)
            )
            selectedApps.size == 1 -> InstalledAppIcon(
                packageName = selectedApps.first().packageName,
                appName = selectedApps.first().appName
            )
            else -> Icon(
                Icons.Default.Apps,
                contentDescription = null,
                tint = AccentCyan,
                modifier = Modifier.size(32.dp)
            )
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
        val visiblePackages = FocusModePolicy.visibleAllowedPackages(
            launchablePackages = apps.map { it.packageName },
            allowedPackages = session.allowedPackages,
            mandatoryPackages = mandatoryPackages
        )
        apps.filter { it.packageName in visiblePackages }
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
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkCard),
                border = BorderStroke(1.dp, AccentCyan.copy(alpha = 0.45f)),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Default.LockClock,
                        contentDescription = null,
                        tint = AccentCyan,
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.focus_mode_active_title),
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                    Text(
                        formatRemaining(session.remainingMillis(nowMillis)),
                        color = AccentCyan,
                        fontWeight = FontWeight.Bold,
                        fontSize = 30.sp,
                        modifier = Modifier.padding(top = 5.dp)
                    )
                    Text(
                        stringResource(R.string.focus_mode_remaining),
                        color = TextHint,
                        fontSize = 13.sp
                    )
                }
            }
        }

        item {
            FocusInfoCard(
                title = stringResource(R.string.focus_mode_kiosk_title),
                description = stringResource(R.string.focus_mode_kiosk_description)
            )
        }

        if (session.grayscaleEnabled) {
            item {
                FocusInfoCard(
                    title = stringResource(R.string.focus_mode_grayscale_active_title),
                    description = stringResource(
                        R.string.focus_mode_grayscale_active_description
                    )
                )
            }
        }

        item {
            Text(
                stringResource(R.string.focus_mode_available_apps),
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp
            )
            Text(
                stringResource(R.string.focus_mode_active_apps_locked),
                color = TextHint,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 3.dp)
            )
        }

        item {
            FocusAppGrid(
                apps = allowedApps,
                includeEssentials = true,
                onOpenPhone = {
                    launchFocusIntent(context, FocusModeAppCatalog.phoneIntent())
                },
                onOpenSms = {
                    launchFocusIntent(context, FocusModeAppCatalog.smsIntent())
                },
                onOpenApp = { packageName ->
                    manager.createOpenAppIntent(packageName)?.let {
                        launchFocusIntent(context, it)
                    }
                }
            )

            if (allowedApps.isEmpty()) {
                Text(
                    stringResource(R.string.focus_mode_only_essentials),
                    color = TextHint,
                    fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    textAlign = TextAlign.Center
                )
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
private fun FocusModeFinalPermissionsDialog(
    accessibilityActive: Boolean,
    notificationAccessActive: Boolean,
    onDismiss: () -> Unit,
    onResolveNext: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.focus_mode_final_permissions_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.focus_mode_final_permissions_description))
                if (!accessibilityActive) {
                    Text(stringResource(R.string.focus_mode_final_accessibility_missing))
                }
                if (!notificationAccessActive) {
                    Text(stringResource(R.string.focus_mode_final_notification_missing))
                }
            }
        },
        confirmButton = {
            Button(onClick = onResolveNext) {
                Text(
                    stringResource(
                        if (!accessibilityActive) {
                            R.string.focus_mode_open_accessibility
                        } else {
                            R.string.focus_mode_open_notification_access
                        }
                    )
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun AccessibleAppPickerDialog(
    apps: List<FocusModeSelectableApp>,
    mandatoryPackages: Set<String>,
    selectedPackages: Set<String>,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onTogglePackage: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val selectableApps = remember(apps, mandatoryPackages, searchQuery) {
        apps.filterNot { it.packageName in mandatoryPackages }
            .filter {
                searchQuery.isBlank() ||
                    it.appName.contains(searchQuery, ignoreCase = true) ||
                    it.packageName.contains(searchQuery, ignoreCase = true)
            }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.focus_mode_picker_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(R.string.focus_mode_picker_description),
                    color = TextHint,
                    fontSize = 13.sp
                )
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.focus_mode_search_apps)) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true
                )
                if (selectableApps.isEmpty()) {
                    Text(
                        stringResource(R.string.focus_mode_no_apps),
                        color = TextHint,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(16.dp)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 430.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
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
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text(stringResource(R.string.focus_mode_picker_done))
            }
        }
    )
}

private enum class FocusGridEntryType {
    PHONE,
    SMS,
    INSTALLED_APP
}

private data class FocusGridEntry(
    val label: String,
    val type: FocusGridEntryType,
    val packageName: String? = null
)

@Composable
private fun FocusAppGrid(
    apps: List<FocusModeSelectableApp>,
    includeEssentials: Boolean,
    onOpenPhone: (() -> Unit)? = null,
    onOpenSms: (() -> Unit)? = null,
    onOpenApp: ((String) -> Unit)? = null
) {
    val phoneLabel = stringResource(R.string.focus_mode_phone_emergency)
    val smsLabel = stringResource(R.string.focus_mode_open_sms)
    val entries = buildList {
        if (includeEssentials) {
            add(
                FocusGridEntry(
                    label = phoneLabel,
                    type = FocusGridEntryType.PHONE
                )
            )
            add(
                FocusGridEntry(
                    label = smsLabel,
                    type = FocusGridEntryType.SMS
                )
            )
        }
        apps.forEach { app ->
            add(
                FocusGridEntry(
                    label = app.appName,
                    type = FocusGridEntryType.INSTALLED_APP,
                    packageName = app.packageName
                )
            )
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        entries.chunked(3).forEach { rowEntries ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowEntries.forEach { entry ->
                    val onClick = when (entry.type) {
                        FocusGridEntryType.PHONE -> onOpenPhone
                        FocusGridEntryType.SMS -> onOpenSms
                        FocusGridEntryType.INSTALLED_APP -> {
                            entry.packageName?.let { packageName ->
                                onOpenApp?.let { open -> { open(packageName) } }
                            }
                        }
                    }
                    FocusAppTile(
                        entry = entry,
                        onClick = onClick,
                        modifier = Modifier.weight(1f)
                    )
                }
                repeat(3 - rowEntries.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun FocusAppTile(
    entry: FocusGridEntry,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .height(92.dp)
            .then(
                if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
            ),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        border = BorderStroke(1.dp, CardBorder),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            when (entry.type) {
                FocusGridEntryType.PHONE -> Icon(
                    Icons.Default.Phone,
                    contentDescription = null,
                    tint = AccentCyan,
                    modifier = Modifier.size(36.dp)
                )
                FocusGridEntryType.SMS -> Icon(
                    Icons.Default.Message,
                    contentDescription = null,
                    tint = AccentCyan,
                    modifier = Modifier.size(36.dp)
                )
                FocusGridEntryType.INSTALLED_APP -> InstalledAppIcon(
                    packageName = requireNotNull(entry.packageName),
                    appName = entry.label
                )
            }
            Spacer(Modifier.height(5.dp))
            Text(
                text = entry.label,
                color = TextPrimary,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun InstalledAppIcon(packageName: String, appName: String) {
    val context = LocalContext.current
    var icon by remember(packageName) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(packageName) {
        icon = withContext(Dispatchers.IO) {
            runCatching {
                context.packageManager.getApplicationIcon(packageName)
                    .toBitmap(width = 96, height = 96)
                    .asImageBitmap()
            }.getOrNull()
        }
    }

    if (icon != null) {
        Image(
            bitmap = requireNotNull(icon),
            contentDescription = appName,
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
        )
    } else {
        Icon(
            Icons.Default.Apps,
            contentDescription = appName,
            tint = AccentCyan,
            modifier = Modifier.size(36.dp)
        )
    }
}

@Composable
private fun FocusModeConsentDialog(
    durationMillis: Long,
    grayscaleEnabled: Boolean,
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
                Text(
                    stringResource(
                        if (grayscaleEnabled) {
                            R.string.focus_mode_terms_grayscale_on
                        } else {
                            R.string.focus_mode_terms_grayscale_off
                        }
                    )
                )
                Text(stringResource(R.string.focus_mode_terms_power_limits))
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
private fun FocusModeStartError(outcome: FocusModeManager.StartOutcome?) {
    val message = when (outcome) {
        FocusModeManager.StartOutcome.INVALID_DURATION ->
            stringResource(R.string.focus_mode_duration_invalid)
        FocusModeManager.StartOutcome.ACCESSIBILITY_REQUIRED ->
            stringResource(R.string.focus_mode_accessibility_required)
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

private fun openAccessibilitySettings(context: Context) {
    val opened = runCatching {
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
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
