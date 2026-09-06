package com.focusguard.ui.compose.screens

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Lock
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.focusguard.R
import com.focusguard.focusmode.FocusDurationDialMath
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
import com.focusguard.ui.compose.theme.TextSecondary
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

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
    var durationText by rememberSaveable { mutableStateOf("40") }
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

    if (showAppPicker) {
        val sessionForPicker = activeSession
        AccessibleAppPickerDialog(
            apps = apps,
            mandatoryPackages = mandatoryPackages,
            selectedPackages = sessionForPicker?.allowedPackages ?: selectedPackages,
            searchQuery = searchQuery,
            addOnly = sessionForPicker != null,
            onSearchQueryChange = { searchQuery = it },
            onTogglePackage = { packageName ->
                if (sessionForPicker == null) {
                    val updatedSelection = if (packageName in selectedPackages) {
                        selectedPackages - packageName
                    } else {
                        selectedPackages + packageName
                    }
                    selectedPackages = updatedSelection
                    manager.saveDraftPackages(updatedSelection)
                } else if (packageName !in sessionForPicker.allowedPackages) {
                    scope.launch {
                        manager.addAllowedPackages(setOf(packageName))
                    }
                }
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
            manager = manager,
            onAddApps = {
                searchQuery = ""
                showAppPicker = true
            }
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
    val initialMinutes = when (durationUnit) {
        FocusModePolicy.DurationUnit.MINUTES -> durationText.toIntOrNull() ?: 40
        FocusModePolicy.DurationUnit.HOURS -> (durationText.toIntOrNull() ?: 1) * 60
        FocusModePolicy.DurationUnit.DAYS -> (durationText.toIntOrNull() ?: 0) * 24 * 60
    }.coerceIn(1, FOCUS_DURATION_MAX_MINUTES)
    var dialMinutes by rememberSaveable { mutableIntStateOf(initialMinutes) }
    var showHowItWorks by rememberSaveable { mutableStateOf(false) }

    val hoursUnit = stringResource(R.string.focus_mode_static_hours_short)
    val minutesUnit = stringResource(R.string.focus_mode_static_minutes_short)
    val durationLabel = when {
        dialMinutes < 60 -> "$dialMinutes $minutesUnit"
        dialMinutes % 60 == 0 -> "${dialMinutes / 60} $hoursUnit"
        else -> "${dialMinutes / 60} $hoursUnit ${dialMinutes % 60} $minutesUnit"
    }
    val allowedSummary = when (selectedApps.size) {
        0 -> stringResource(R.string.focus_mode_static_no_extra_apps)
        1 -> stringResource(R.string.focus_mode_static_one_extra_app)
        else -> stringResource(R.string.focus_mode_static_many_extra_apps, selectedApps.size)
    }
    val howTitle = stringResource(R.string.focus_mode_how_it_works)
    val void = Color(0xFF0A0C10)
    val surface = Color(0xFF14171D)
    val surface2 = Color(0xFF1B1F27)
    val stroke = Color(0xFF262B34)
    val tertiaryText = Color(0xFF5B6270)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF12151C), void, void)
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp, vertical = 2.dp)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        text = stringResource(R.string.focus_mode_compact_purpose),
                        color = TextSecondary,
                        fontSize = 13.sp,
                        lineHeight = 17.sp,
                        modifier = Modifier.weight(1f).padding(end = 8.dp)
                    )
                    TextButton(
                        onClick = { showHowItWorks = true },
                        contentPadding = PaddingValues(horizontal = 3.dp, vertical = 0.dp)
                    ) {
                        Text(
                            text = howTitle,
                            color = AccentCyan,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Icon(
                            Icons.Default.ArrowDropDown,
                            contentDescription = null,
                            tint = AccentCyan,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }

                Text(
                    text = stringResource(R.string.focus_mode_static_duration_section),
                    color = tertiaryText,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )

                FocusDurationDial(
                    minutes = dialMinutes,
                    onMinutesChange = { next ->
                        if (next != dialMinutes) {
                            dialMinutes = next
                            onDurationUnitChange(FocusModePolicy.DurationUnit.MINUTES)
                            onDurationTextChange(next.toString())
                        }
                    },
                    trackColor = surface2,
                    tickColor = stroke,
                    tertiaryText = tertiaryText,
                    minutesUnit = minutesUnit,
                    hoursUnit = hoursUnit
                )

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = grayscaleEnabled,
                            role = Role.Switch,
                            onValueChange = onGrayscaleEnabledChange
                        ),
                    colors = CardDefaults.cardColors(containerColor = surface),
                    border = BorderStroke(1.dp, stroke),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.fg_focus_grayscale),
                                color = TextPrimary,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp
                            )
                            Text(
                                text = stringResource(R.string.focus_mode_grayscale_hint),
                                color = tertiaryText,
                                fontSize = 11.sp,
                                lineHeight = 14.sp,
                                modifier = Modifier.padding(top = 1.dp)
                            )
                        }
                        Switch(
                            checked = grayscaleEnabled,
                            onCheckedChange = null
                        )
                    }
                }

                Text(
                    text = stringResource(R.string.focus_mode_static_allowed_section),
                    color = tertiaryText,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.3.sp
                )

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = surface),
                    border = BorderStroke(1.dp, stroke),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        FocusDrawerTile(
                            label = stringResource(R.string.focus_mode_static_phone),
                            caption = stringResource(R.string.focus_mode_static_always),
                            locked = true,
                            modifier = Modifier.weight(1f),
                            surface2 = surface2,
                            tertiaryText = tertiaryText,
                            stroke = stroke
                        ) {
                            Icon(
                                Icons.Default.Phone,
                                contentDescription = null,
                                tint = TextSecondary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        FocusDrawerTile(
                            label = stringResource(R.string.focus_mode_static_sms),
                            caption = stringResource(R.string.focus_mode_static_always),
                            locked = true,
                            modifier = Modifier.weight(1f),
                            surface2 = surface2,
                            tertiaryText = tertiaryText,
                            stroke = stroke
                        ) {
                            Icon(
                                Icons.Default.Message,
                                contentDescription = null,
                                tint = TextSecondary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        FocusSelectedDrawerTile(
                            selectedApps = selectedApps,
                            isLoading = isLoadingApps,
                            onClick = onAddApps,
                            modifier = Modifier.weight(1f),
                            surface2 = surface2,
                            tertiaryText = tertiaryText,
                            stroke = stroke
                        )
                        FocusDrawerTile(
                            label = stringResource(R.string.focus_mode_add_apps),
                            caption = "",
                            onClick = if (isLoadingApps) null else onAddApps,
                            modifier = Modifier.weight(1f),
                            surface2 = Color.Transparent,
                            tertiaryText = tertiaryText,
                            stroke = stroke,
                            dashedStyle = true
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = null,
                                tint = tertiaryText,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }

                if (startOutcome != null) {
                    FocusModeStartError(startOutcome)
                }
                Spacer(modifier = Modifier.height(2.dp))
            }

            Text(
                text = stringResource(
                    R.string.focus_mode_static_dock_summary,
                    durationLabel,
                    allowedSummary
                ),
                color = tertiaryText,
                fontSize = 10.sp,
                letterSpacing = 0.4.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 6.dp)
            )

            Button(
                onClick = onStart,
                enabled = !isStarting && durationValid && !isLoadingApps,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                shape = RoundedCornerShape(14.dp)
            ) {
                if (isStarting) {
                    CircularProgressIndicator(
                        color = Color(0xFF04201B),
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp)
                    )
                } else {
                    Text(
                        stringResource(R.string.focus_mode_review_start),
                        color = Color(0xFF04201B),
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 15.sp
                    )
                }
            }
        }

        if (showHowItWorks) {
            Dialog(
                onDismissRequest = { showHowItWorks = false },
                properties = DialogProperties(usePlatformDefaultWidth = false)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.82f))
                        .clickable { showHowItWorks = false }
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = surface),
                        border = BorderStroke(1.dp, AccentCyan.copy(alpha = 0.38f)),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(9.dp)
                        ) {
                            Text(
                                text = howTitle,
                                color = TextPrimary,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = stringResource(R.string.focus_mode_static_purpose_body),
                                color = TextSecondary,
                                fontSize = 13.sp,
                                lineHeight = 19.sp
                            )
                            Text(
                                text = stringResource(R.string.focus_mode_tap_anywhere_to_close),
                                color = tertiaryText,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FocusDurationDial(
    minutes: Int,
    onMinutesChange: (Int) -> Unit,
    trackColor: Color,
    tickColor: Color,
    tertiaryText: Color,
    minutesUnit: String,
    hoursUnit: String
) {
    val progress = ((minutes - 1f) / (FOCUS_DURATION_MAX_MINUTES - 1f)).coerceIn(0f, 1f)
    val displayNumber = FocusDurationDialMath.displayValue(minutes)
    val displayUnit = if (minutes < 60) {
        minutesUnit.uppercase(Locale.getDefault())
    } else {
        "${hoursUnit.uppercase(Locale.getDefault())}:${minutesUnit.uppercase(Locale.getDefault())}"
    }
    val durationA11yLabel = stringResource(R.string.focus_mode_static_duration_section)
    val durationA11yValue = if (minutes < 60) {
        "$minutes $minutesUnit"
    } else {
        "${minutes / 60} $hoursUnit ${minutes % 60} $minutesUnit"
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier.size(188.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .semantics {
                        contentDescription = durationA11yLabel
                        stateDescription = durationA11yValue
                        progressBarRangeInfo = ProgressBarRangeInfo(
                            current = minutes.toFloat(),
                            range = 1f..FOCUS_DURATION_MAX_MINUTES.toFloat(),
                            steps = FOCUS_DURATION_MAX_MINUTES - 2
                        )
                        setProgress { target ->
                            onMinutesChange(
                                target.roundToInt().coerceIn(1, FOCUS_DURATION_MAX_MINUTES)
                            )
                            true
                        }
                    }
                    .pointerInput(minutes) {
                        fun update(position: Offset) {
                            val cx = size.width / 2f
                            val cy = size.height / 2f
                            val degrees = Math.toDegrees(
                                atan2(
                                    (position.y - cy).toDouble(),
                                    (position.x - cx).toDouble()
                                )
                            ).toFloat()
                            onMinutesChange(FocusDurationDialMath.minutesForAngle(degrees))
                        }
                        detectDragGestures(
                            onDragStart = { update(it) },
                            onDrag = { change, _ ->
                                update(change.position)
                                change.consume()
                            }
                        )
                    }
            ) {
                val strokeWidth = 8.dp.toPx()
                val radius = (size.minDimension - strokeWidth) / 2f
                val center = Offset(size.width / 2f, size.height / 2f)

                for (index in 0..18) {
                    val tickAngle = Math.toRadians((135f + (270f * index / 18f)).toDouble())
                    val outer = Offset(
                        center.x + cos(tickAngle).toFloat() * (radius + 7.dp.toPx()),
                        center.y + sin(tickAngle).toFloat() * (radius + 7.dp.toPx())
                    )
                    val inner = Offset(
                        center.x + cos(tickAngle).toFloat() * (radius + 2.dp.toPx()),
                        center.y + sin(tickAngle).toFloat() * (radius + 2.dp.toPx())
                    )
                    drawLine(
                        color = tickColor,
                        start = inner,
                        end = outer,
                        strokeWidth = 1.25.dp.toPx()
                    )
                }

                drawArc(
                    color = trackColor,
                    startAngle = 135f,
                    sweepAngle = 270f,
                    useCenter = false,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
                if (progress > 0f) {
                    drawArc(
                        color = AccentCyan,
                        startAngle = 135f,
                        sweepAngle = 270f * progress,
                        useCenter = false,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                    )
                }

                val handleAngle = Math.toRadians((135f + 270f * progress).toDouble())
                val handle = Offset(
                    center.x + cos(handleAngle).toFloat() * radius,
                    center.y + sin(handleAngle).toFloat() * radius
                )
                drawCircle(color = Color(0xFF0A0C10), radius = 13.dp.toPx(), center = handle)
                drawCircle(color = AccentCyan.copy(alpha = 0.20f), radius = 10.dp.toPx(), center = handle)
                drawCircle(color = AccentCyan, radius = 7.dp.toPx(), center = handle)
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = displayNumber,
                    color = TextPrimary,
                    fontSize = 40.sp,
                    lineHeight = 42.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = displayUnit,
                    color = TextSecondary,
                    fontSize = 11.sp,
                    letterSpacing = 1.2.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        Row(
            modifier = Modifier.width(188.dp).padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("1 $minutesUnit", color = tertiaryText, fontSize = 10.sp)
            Text("8 $hoursUnit", color = tertiaryText, fontSize = 10.sp)
        }
    }
}

@Composable
private fun FocusDrawerTile(
    label: String,
    caption: String,
    locked: Boolean = false,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    surface2: Color,
    tertiaryText: Color,
    stroke: Color,
    dashedStyle: Boolean = false,
    icon: @Composable () -> Unit
) {
    Column(
        modifier = modifier.then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(surface2)
                    .then(
                        if (dashedStyle) {
                            Modifier.drawBehind {
                                drawRoundRect(
                                    color = stroke,
                                    cornerRadius = CornerRadius(13.dp.toPx()),
                                    style = Stroke(
                                        width = 1.5.dp.toPx(),
                                        pathEffect = PathEffect.dashPathEffect(
                                            floatArrayOf(6.dp.toPx(), 5.dp.toPx())
                                        )
                                    )
                                )
                            }
                        } else {
                            Modifier
                        }
                    ),
                contentAlignment = Alignment.Center
            ) { icon() }
            if (locked || selected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(if (selected) AccentCyan else tertiaryText)
                        .border(2.dp, Color(0xFF14171D), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (selected) Icons.Default.Check else Icons.Default.Lock,
                        contentDescription = null,
                        tint = if (selected) Color(0xFF04201B) else Color(0xFF14171D),
                        modifier = Modifier.size(if (selected) 11.dp else 9.dp)
                    )
                }
            }
        }
        Text(
            text = label,
            color = if (dashedStyle) tertiaryText else TextPrimary,
            fontWeight = FontWeight.SemiBold,
            fontSize = 10.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(top = 5.dp)
        )
        Text(
            text = caption.ifBlank { " " },
            color = if (selected) AccentCyan else tertiaryText,
            fontSize = 8.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(top = 1.dp)
        )
    }
}

@Composable
private fun FocusSelectedDrawerTile(
    selectedApps: List<FocusModeSelectableApp>,
    isLoading: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
    surface2: Color,
    tertiaryText: Color,
    stroke: Color
) {
    val label = when {
        isLoading -> stringResource(R.string.focus_mode_static_apps)
        selectedApps.isEmpty() -> stringResource(R.string.focus_mode_static_apps)
        selectedApps.size == 1 -> selectedApps.first().appName
        else -> stringResource(R.string.focus_mode_static_apps_count, selectedApps.size)
    }
    val caption = when {
        isLoading -> stringResource(R.string.focus_mode_static_loading)
        selectedApps.isEmpty() -> stringResource(R.string.focus_mode_static_tap_choose)
        else -> stringResource(R.string.focus_mode_static_selected)
    }
    FocusDrawerTile(
        label = label,
        caption = caption,
        selected = selectedApps.isNotEmpty(),
        onClick = if (isLoading) null else onClick,
        modifier = modifier,
        surface2 = surface2,
        tertiaryText = tertiaryText,
        stroke = stroke
    ) {
        when {
            isLoading -> CircularProgressIndicator(
                color = AccentCyan,
                strokeWidth = 2.dp,
                modifier = Modifier.size(24.dp)
            )
            selectedApps.size == 1 -> InstalledAppIcon(
                packageName = selectedApps.first().packageName,
                appName = selectedApps.first().appName
            )
            else -> Icon(
                Icons.Default.Apps,
                contentDescription = null,
                tint = if (selectedApps.isEmpty()) tertiaryText else AccentCyan,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
private fun FocusModeActiveContent(
    session: FocusModeSession,
    apps: List<FocusModeSelectableApp>,
    mandatoryPackages: Set<String>,
    manager: FocusModeManager,
    onAddApps: () -> Unit
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

            OutlinedButton(
                onClick = onAddApps,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                border = BorderStroke(1.dp, AccentCyan.copy(alpha = 0.55f)),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = null,
                    tint = AccentCyan,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    stringResource(R.string.focus_mode_add_apps),
                    color = AccentCyan,
                    fontWeight = FontWeight.Bold
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
    addOnly: Boolean = false,
    onSearchQueryChange: (String) -> Unit,
    onTogglePackage: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val selectableApps = remember(
        apps,
        mandatoryPackages,
        selectedPackages,
        searchQuery,
        addOnly
    ) {
        apps.filterNot { it.packageName in mandatoryPackages }
            .filterNot { addOnly && it.packageName in selectedPackages }
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
                if (!addOnly) {
                    Text(
                        stringResource(R.string.focus_mode_picker_description),
                        color = TextHint,
                        fontSize = 13.sp
                    )
                }
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

private const val FOCUS_DURATION_MAX_MINUTES = 8 * 60
