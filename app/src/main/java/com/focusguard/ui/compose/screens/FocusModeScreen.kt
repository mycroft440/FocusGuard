package com.focusguard.ui.compose.screens

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Lock
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.focusguard.R
import com.focusguard.focusmode.FocusModeAppCatalog
import com.focusguard.focusmode.FocusModeManager
import com.focusguard.focusmode.FocusModePolicy
import com.focusguard.focusmode.FocusModeSelectableApp
import com.focusguard.focusmode.FocusModeSession
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private val FocusHtmlBg = Color(0xFF090B0E)
private val FocusHtmlSurface = Color(0xFF12161B)
private val FocusHtmlSurfaceHi = Color(0xFF1A2028)
private val FocusHtmlLine = Color(0xFF232B35)
private val FocusHtmlLineSoft = Color(0xFF1A212A)
private val FocusHtmlText = Color(0xFFEAEFF4)
private val FocusHtmlDim = Color(0xFF8A96A3)
private val FocusHtmlDimmer = Color(0xFF59636F)
private val FocusHtmlAccent = Color(0xFF7FD6EC)
private val FocusHtmlAccentSoft = Color(0x217FD6EC)

private const val FOCUS_HTML_MAX_INDEX = 96

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
    var durationMinutes by rememberSaveable { mutableIntStateOf(40) }
    var grayscaleEnabled by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var showAppPicker by rememberSaveable { mutableStateOf(false) }
    var showPermissionReview by rememberSaveable { mutableStateOf(false) }
    var pendingFinalStart by rememberSaveable { mutableStateOf(false) }
    var showConfirmation by remember { mutableStateOf(false) }
    var termsAccepted by remember { mutableStateOf(false) }
    var isStarting by remember { mutableStateOf(false) }
    var startOutcome by remember { mutableStateOf<FocusModeManager.StartOutcome?>(null) }
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
        FocusModePolicy.DurationUnit.MINUTES,
        durationMinutes
    )

    LaunchedEffect(
        permissionRevision,
        pendingFinalStart,
        accessibilityActive,
        notificationAccessActive
    ) {
        if (!pendingFinalStart ||
            !lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        ) return@LaunchedEffect

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
                    val updated = if (packageName in selectedPackages) {
                        selectedPackages - packageName
                    } else {
                        selectedPackages + packageName
                    }
                    selectedPackages = updated
                    manager.saveDraftPackages(updated)
                } else if (packageName !in sessionForPicker.allowedPackages) {
                    scope.launch { manager.addAllowedPackages(setOf(packageName)) }
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
                if (!accessibilityActive) openAccessibilitySettings(context)
                else if (!notificationAccessActive) openNotificationAccess(context)
            }
        )
    }

    val session = activeSession
    if (session != null) {
        FocusModeFixedActiveContent(
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
        FocusModeHtmlSetupContent(
            apps = apps,
            mandatoryPackages = mandatoryPackages,
            isLoadingApps = isLoadingApps,
            selectedPackages = selectedPackages,
            durationMinutes = durationMinutes,
            onDurationMinutesChange = {
                durationMinutes = it.coerceIn(1, 480)
                startOutcome = null
            },
            grayscaleEnabled = grayscaleEnabled,
            onGrayscaleEnabledChange = { grayscaleEnabled = it },
            onAddApps = { showAppPicker = true },
            isStarting = isStarting,
            startOutcome = startOutcome,
            onStart = {
                when {
                    durationMillis == null -> {
                        startOutcome = FocusModeManager.StartOutcome.INVALID_DURATION
                    }
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
private fun FocusModeHtmlSetupContent(
    apps: List<FocusModeSelectableApp>,
    mandatoryPackages: Set<String>,
    isLoadingApps: Boolean,
    selectedPackages: Set<String>,
    durationMinutes: Int,
    onDurationMinutesChange: (Int) -> Unit,
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
    var infoOpen by rememberSaveable { mutableStateOf(false) }
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            nowMillis = System.currentTimeMillis()
        }
    }

    val startTime = remember(nowMillis) { formatClock(nowMillis) }
    val endTime = remember(nowMillis, durationMinutes) {
        formatClock(nowMillis + durationMinutes * 60_000L)
    }
    val durationLabel = focusDurationLabel(durationMinutes)
    val appCountLabel = when (selectedApps.size) {
        0 -> stringResource(R.string.focus_html_apps_count, 0)
        1 -> stringResource(R.string.focus_html_one_app_count)
        else -> stringResource(R.string.focus_html_apps_count, selectedApps.size)
    }
    val summaryApps = when (selectedApps.size) {
        0 -> stringResource(R.string.focus_html_zero_apps)
        1 -> stringResource(R.string.focus_html_one_app)
        else -> stringResource(R.string.focus_html_many_apps, selectedApps.size)
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(FocusHtmlBg)
    ) {
        val compact = maxHeight < 610.dp
        val tiny = maxHeight < 540.dp
        val sidePadding = if (compact) 14.dp else 20.dp
        val sectionGap = if (tiny) 5.dp else if (compact) 7.dp else 10.dp
        val appTileHeight = if (tiny) 64.dp else if (compact) 72.dp else 80.dp
        val heroNumberSize = if (tiny) 40.sp else if (compact) 48.sp else 58.sp
        val actionHeight = if (tiny) 42.dp else 48.dp

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = sidePadding, vertical = if (compact) 4.dp else 8.dp),
            verticalArrangement = Arrangement.spacedBy(sectionGap)
        ) {
            FocusHtmlInfoBlock(
                expanded = infoOpen,
                compact = compact,
                onToggle = { infoOpen = !infoOpen }
            )

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 6.dp)
            ) {
                Text(
                    text = stringResource(R.string.focus_html_duration),
                    color = FocusHtmlDimmer,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.8.sp
                )
                Row(verticalAlignment = Alignment.Bottom) {
                    val display = focusDurationDisplay(durationMinutes)
                    Text(
                        text = display.first,
                        color = FocusHtmlText,
                        fontSize = heroNumberSize,
                        lineHeight = heroNumberSize,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = (-1.2).sp
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        text = display.second,
                        color = FocusHtmlDim,
                        fontSize = if (compact) 16.sp else 19.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(bottom = if (compact) 4.dp else 6.dp)
                    )
                }
                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(FocusHtmlAccentSoft)
                        .padding(horizontal = 13.dp, vertical = if (compact) 4.dp else 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(startTime, color = FocusHtmlAccent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text("→", color = FocusHtmlAccent.copy(alpha = 0.55f), fontSize = 12.sp)
                    Text(endTime, color = FocusHtmlAccent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            FocusHtmlDurationSlider(
                minutes = durationMinutes,
                compact = compact,
                onMinutesChange = onDurationMinutesChange
            )

            FocusHtmlPresets(
                selectedMinutes = durationMinutes,
                compact = compact,
                onSelect = onDurationMinutesChange
            )

            FocusHtmlGrayscaleSetting(
                enabled = grayscaleEnabled,
                compact = compact,
                onEnabledChange = onGrayscaleEnabledChange
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.focus_html_allowed),
                    color = FocusHtmlDimmer,
                    fontSize = if (tiny) 9.sp else 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.3.sp
                )
                Text(
                    text = appCountLabel,
                    color = FocusHtmlDimmer,
                    fontSize = if (tiny) 9.sp else 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp
                )
            }

            FocusHtmlAppsGrid(
                selectedApps = selectedApps,
                isLoading = isLoadingApps,
                tileHeight = appTileHeight,
                onAddApps = onAddApps
            )

            if (startOutcome != null) {
                FocusModeStartError(startOutcome)
            }

            Spacer(Modifier.weight(1f))

            Text(
                text = stringResource(
                    R.string.focus_html_summary,
                    durationLabel,
                    summaryApps,
                    endTime
                ),
                color = FocusHtmlDimmer,
                fontSize = if (compact) 10.5.sp else 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Button(
                onClick = onStart,
                enabled = !isStarting && !isLoadingApps,
                modifier = Modifier.fillMaxWidth().height(actionHeight),
                colors = ButtonDefaults.buttonColors(
                    containerColor = FocusHtmlAccent,
                    contentColor = Color(0xFF04222C),
                    disabledContainerColor = FocusHtmlSurfaceHi,
                    disabledContentColor = FocusHtmlDimmer
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                if (isStarting) {
                    CircularProgressIndicator(
                        color = Color(0xFF04222C),
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(20.dp)
                    )
                } else {
                    Text(
                        text = stringResource(R.string.focus_html_review_start),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = if (compact) 14.sp else 16.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun FocusHtmlInfoBlock(
    expanded: Boolean,
    compact: Boolean,
    onToggle: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.focus_html_intro),
                color = FocusHtmlDim,
                fontSize = if (compact) 11.sp else 13.sp,
                lineHeight = if (compact) 14.sp else 17.sp,
                modifier = Modifier.weight(1f),
                maxLines = 2
            )
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(onClick = onToggle)
                    .background(Color.Transparent)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = stringResource(R.string.focus_html_how_it_works),
                    color = FocusHtmlDim,
                    fontSize = if (compact) 10.sp else 11.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Icon(
                    Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    tint = FocusHtmlDim,
                    modifier = Modifier.size(15.dp)
                )
            }
        }
        AnimatedVisibility(visible = expanded) {
            Text(
                text = stringResource(R.string.focus_html_how_it_works_body),
                color = FocusHtmlDimmer,
                fontSize = if (compact) 10.sp else 12.sp,
                lineHeight = if (compact) 13.sp else 16.sp,
                modifier = Modifier.padding(top = 6.dp),
                maxLines = if (compact) 3 else 4,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun FocusHtmlDurationSlider(
    minutes: Int,
    compact: Boolean,
    onMinutesChange: (Int) -> Unit
) {
    val index = focusMinutesToIndex(minutes)
    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = index.toFloat(),
            onValueChange = { raw ->
                onMinutesChange(focusIndexToMinutes(raw.roundToInt().coerceIn(0, FOCUS_HTML_MAX_INDEX)))
            },
            valueRange = 0f..FOCUS_HTML_MAX_INDEX.toFloat(),
            steps = FOCUS_HTML_MAX_INDEX - 1,
            modifier = Modifier.fillMaxWidth().height(if (compact) 30.dp else 38.dp),
            colors = SliderDefaults.colors(
                thumbColor = FocusHtmlAccent,
                activeTrackColor = FocusHtmlAccent,
                inactiveTrackColor = FocusHtmlSurfaceHi,
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent
            )
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(stringResource(R.string.focus_html_tick_1m), color = FocusHtmlDimmer, fontSize = 10.sp)
            Text(stringResource(R.string.focus_html_tick_4h), color = FocusHtmlDimmer, fontSize = 10.sp)
            Text(stringResource(R.string.focus_html_tick_8h), color = FocusHtmlDimmer, fontSize = 10.sp)
        }
    }
}

@Composable
private fun FocusHtmlPresets(
    selectedMinutes: Int,
    compact: Boolean,
    onSelect: (Int) -> Unit
) {
    val presets = listOf(
        25 to stringResource(R.string.focus_html_preset_25),
        40 to stringResource(R.string.focus_html_preset_40),
        60 to stringResource(R.string.focus_html_preset_1h),
        120 to stringResource(R.string.focus_html_preset_2h)
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        presets.forEach { (minutes, label) ->
            val selected = selectedMinutes == minutes
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(if (compact) 34.dp else 40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (selected) FocusHtmlAccentSoft else FocusHtmlSurface)
                    .clickable { onSelect(minutes) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    color = if (selected) FocusHtmlAccent else FocusHtmlDim,
                    fontSize = if (compact) 11.sp else 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun FocusHtmlGrayscaleSetting(
    enabled: Boolean,
    compact: Boolean,
    onEnabledChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(FocusHtmlSurface)
            .toggleable(
                value = enabled,
                role = Role.Switch,
                onValueChange = onEnabledChange
            )
            .padding(
                horizontal = if (compact) 13.dp else 17.dp,
                vertical = if (compact) 9.dp else 13.dp
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.focus_html_grayscale),
                color = FocusHtmlText,
                fontSize = if (compact) 13.sp else 15.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.focus_html_grayscale_desc),
                color = FocusHtmlDimmer,
                fontSize = if (compact) 10.sp else 12.5.sp,
                lineHeight = if (compact) 13.sp else 16.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF06222B),
                checkedTrackColor = FocusHtmlAccent,
                checkedBorderColor = FocusHtmlAccent,
                uncheckedThumbColor = FocusHtmlDim,
                uncheckedTrackColor = Color(0xFF2A323C),
                uncheckedBorderColor = Color(0xFF39434F)
            )
        )
    }
}

@Composable
private fun FocusHtmlAppsGrid(
    selectedApps: List<FocusModeSelectableApp>,
    isLoading: Boolean,
    tileHeight: androidx.compose.ui.unit.Dp,
    onAddApps: () -> Unit
) {
    val preview = selectedApps.firstOrNull()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        FocusHtmlAppTile(
            title = stringResource(R.string.focus_html_phone),
            subtitle = stringResource(R.string.focus_html_always_allowed),
            modifier = Modifier.weight(1f),
            height = tileHeight,
            icon = {
                Icon(Icons.Default.Phone, contentDescription = null, tint = FocusHtmlDim, modifier = Modifier.size(20.dp))
            }
        )
        FocusHtmlAppTile(
            title = stringResource(R.string.focus_html_sms),
            subtitle = stringResource(R.string.focus_html_always_allowed),
            modifier = Modifier.weight(1f),
            height = tileHeight,
            icon = {
                Icon(Icons.Default.Message, contentDescription = null, tint = FocusHtmlDim, modifier = Modifier.size(20.dp))
            }
        )
    }
    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        FocusHtmlAppTile(
            title = when {
                isLoading -> stringResource(R.string.focus_mode_static_loading)
                preview != null && selectedApps.size == 1 -> preview.appName
                selectedApps.size > 1 -> stringResource(R.string.focus_html_selected_apps, selectedApps.size)
                else -> stringResource(R.string.focus_html_add_app)
            },
            subtitle = when {
                isLoading -> ""
                selectedApps.isNotEmpty() -> stringResource(R.string.focus_html_you_allowed)
                else -> stringResource(R.string.focus_html_choose_essential)
            },
            modifier = Modifier.weight(1f),
            height = tileHeight,
            onClick = if (isLoading) null else onAddApps,
            icon = {
                when {
                    isLoading -> CircularProgressIndicator(
                        color = FocusHtmlAccent,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(20.dp)
                    )
                    preview != null && selectedApps.size == 1 -> InstalledAppIcon(
                        packageName = preview.packageName,
                        appName = preview.appName,
                        size = 28.dp
                    )
                    selectedApps.size > 1 -> Icon(
                        Icons.Default.Apps,
                        contentDescription = null,
                        tint = FocusHtmlAccent,
                        modifier = Modifier.size(20.dp)
                    )
                    else -> Icon(
                        Icons.Default.Add,
                        contentDescription = null,
                        tint = FocusHtmlAccent,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        )
        FocusHtmlAppTile(
            title = stringResource(R.string.focus_html_add_app),
            subtitle = stringResource(R.string.focus_html_choose_essential),
            modifier = Modifier.weight(1f),
            height = tileHeight,
            dashed = true,
            onClick = if (isLoading) null else onAddApps,
            icon = {
                Icon(Icons.Default.Add, contentDescription = null, tint = FocusHtmlDim, modifier = Modifier.size(20.dp))
            }
        )
    }
}

@Composable
private fun FocusHtmlAppTile(
    title: String,
    subtitle: String,
    modifier: Modifier,
    height: androidx.compose.ui.unit.Dp,
    dashed: Boolean = false,
    onClick: (() -> Unit)? = null,
    icon: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(18.dp)
    val dashedModifier = if (dashed) {
        Modifier.drawBehind {
            drawRoundRect(
                color = FocusHtmlLine,
                cornerRadius = CornerRadius(18.dp.toPx()),
                style = Stroke(
                    width = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(
                        floatArrayOf(5.dp.toPx(), 4.dp.toPx())
                    )
                )
            )
        }
    } else Modifier

    Row(
        modifier = modifier
            .height(height)
            .clip(shape)
            .background(if (dashed) Color.Transparent else FocusHtmlSurface)
            .then(dashedModifier)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(if (dashed) Color.Transparent else FocusHtmlSurfaceHi),
            contentAlignment = Alignment.Center
        ) {
            icon()
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = if (dashed) FocusHtmlDim else FocusHtmlText,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    color = FocusHtmlDimmer,
                    fontSize = 9.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun FocusModeFixedActiveContent(
    session: FocusModeSession,
    apps: List<FocusModeSelectableApp>,
    mandatoryPackages: Set<String>,
    manager: FocusModeManager,
    onAddApps: () -> Unit
) {
    val context = LocalContext.current
    var nowMillis by remember(session.endTimeMillis) { mutableStateOf(System.currentTimeMillis()) }
    val allowedApps = remember(apps, session.allowedPackages, mandatoryPackages) {
        val visible = FocusModePolicy.visibleAllowedPackages(
            launchablePackages = apps.map { it.packageName },
            allowedPackages = session.allowedPackages,
            mandatoryPackages = mandatoryPackages
        )
        apps.filter { it.packageName in visible }
    }

    BackHandler(enabled = true) { }

    LaunchedEffect(session.endTimeMillis) {
        while (nowMillis < session.endTimeMillis) {
            delay(1_000L)
            nowMillis = System.currentTimeMillis()
        }
        manager.finishExpiredSession()
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(FocusHtmlBg)
    ) {
        val compact = maxHeight < 600.dp
        val side = if (compact) 14.dp else 20.dp
        val allowedPreview = allowedApps.take(2)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = side, vertical = if (compact) 8.dp else 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp)
        ) {
            Text(
                text = stringResource(R.string.focus_html_active),
                color = FocusHtmlAccent,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.8.sp
            )
            Text(
                text = formatRemaining(session.remainingMillis(nowMillis)),
                color = FocusHtmlText,
                fontSize = if (compact) 42.sp else 54.sp,
                lineHeight = if (compact) 46.sp else 58.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = stringResource(R.string.focus_html_remaining),
                color = FocusHtmlDim,
                fontSize = 12.sp
            )
            Text(
                text = stringResource(R.string.focus_html_fixed_notice),
                color = FocusHtmlDimmer,
                fontSize = 11.sp,
                textAlign = TextAlign.Center
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    stringResource(R.string.focus_html_allowed),
                    color = FocusHtmlDimmer,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.2.sp
                )
                Text(
                    if (allowedApps.size == 1) stringResource(R.string.focus_html_one_app_count)
                    else stringResource(R.string.focus_html_apps_count, allowedApps.size),
                    color = FocusHtmlDimmer,
                    fontSize = 10.sp
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FocusHtmlAppTile(
                    title = stringResource(R.string.focus_html_phone),
                    subtitle = stringResource(R.string.focus_html_always_allowed),
                    modifier = Modifier.weight(1f),
                    height = if (compact) 72.dp else 82.dp,
                    onClick = { launchFocusIntent(context, FocusModeAppCatalog.phoneIntent()) },
                    icon = {
                        Icon(Icons.Default.Phone, null, tint = FocusHtmlDim, modifier = Modifier.size(20.dp))
                    }
                )
                FocusHtmlAppTile(
                    title = stringResource(R.string.focus_html_sms),
                    subtitle = stringResource(R.string.focus_html_always_allowed),
                    modifier = Modifier.weight(1f),
                    height = if (compact) 72.dp else 82.dp,
                    onClick = { launchFocusIntent(context, FocusModeAppCatalog.smsIntent()) },
                    icon = {
                        Icon(Icons.Default.Message, null, tint = FocusHtmlDim, modifier = Modifier.size(20.dp))
                    }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                allowedPreview.forEach { app ->
                    FocusHtmlAppTile(
                        title = app.appName,
                        subtitle = stringResource(R.string.focus_html_you_allowed),
                        modifier = Modifier.weight(1f),
                        height = if (compact) 72.dp else 82.dp,
                        onClick = {
                            manager.createOpenAppIntent(app.packageName)?.let {
                                launchFocusIntent(context, it)
                            }
                        },
                        icon = {
                            InstalledAppIcon(app.packageName, app.appName, 28.dp)
                        }
                    )
                }
                if (allowedPreview.size < 2) {
                    FocusHtmlAppTile(
                        title = stringResource(R.string.focus_html_add_app),
                        subtitle = stringResource(R.string.focus_html_choose_essential),
                        modifier = Modifier.weight(1f),
                        height = if (compact) 72.dp else 82.dp,
                        dashed = true,
                        onClick = onAddApps,
                        icon = {
                            Icon(Icons.Default.Add, null, tint = FocusHtmlDim, modifier = Modifier.size(20.dp))
                        }
                    )
                }
                if (allowedPreview.isEmpty()) Spacer(Modifier.weight(1f))
            }

            if (allowedApps.size > 2) {
                Text(
                    text = stringResource(R.string.focus_html_selected_apps, allowedApps.size),
                    color = FocusHtmlAccent,
                    fontSize = 11.sp,
                    modifier = Modifier.clickable(onClick = onAddApps)
                )
            }

            if (session.nonSuspendablePackages.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.65f))
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        stringResource(
                            R.string.focus_mode_system_apps_notice,
                            session.nonSuspendablePackages.size
                        ),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        fontSize = 10.5.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(Modifier.weight(1f))
            Text(
                stringResource(R.string.focus_mode_no_early_stop),
                color = FocusHtmlDimmer,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun AccessibleAppPickerDialog(
    apps: List<FocusModeSelectableApp>,
    mandatoryPackages: Set<String>,
    selectedPackages: Set<String>,
    searchQuery: String,
    addOnly: Boolean,
    onSearchQueryChange: (String) -> Unit,
    onTogglePackage: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val selectableApps = remember(apps, mandatoryPackages, selectedPackages, searchQuery, addOnly) {
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
                        color = FocusHtmlDim,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(16.dp)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 430.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(selectableApps, key = { it.packageName }) { app ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onTogglePackage(app.packageName) }
                                    .padding(horizontal = 8.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                InstalledAppIcon(app.packageName, app.appName, 38.dp)
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        app.appName,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        app.packageName,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 10.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (!addOnly) {
                                    Checkbox(
                                        checked = app.packageName in selectedPackages,
                                        onCheckedChange = { onTogglePackage(app.packageName) }
                                    )
                                } else {
                                    Icon(Icons.Default.Add, contentDescription = null)
                                }
                            }
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
                        if (!accessibilityActive) R.string.focus_mode_open_accessibility
                        else R.string.focus_mode_open_notification_access
                    )
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
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
                Text(stringResource(R.string.focus_mode_terms_duration, formatRemaining(durationMillis)))
                Text(stringResource(R.string.focus_mode_terms_effects))
                Text(stringResource(R.string.focus_mode_terms_essentials))
                Text(
                    stringResource(
                        if (grayscaleEnabled) R.string.focus_mode_terms_grayscale_on
                        else R.string.focus_mode_terms_grayscale_off
                    )
                )
                Text(stringResource(R.string.focus_mode_terms_power_limits))
                Text(stringResource(R.string.focus_mode_terms_exit))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !isStarting) { onAcceptedChange(!accepted) },
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
            Button(onClick = onConfirm, enabled = accepted && !isStarting) {
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
private fun InstalledAppIcon(
    packageName: String,
    appName: String,
    size: androidx.compose.ui.unit.Dp
) {
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
            modifier = Modifier.size(size).clip(RoundedCornerShape(9.dp))
        )
    } else {
        Icon(
            Icons.Default.Apps,
            contentDescription = appName,
            tint = FocusHtmlAccent,
            modifier = Modifier.size(size)
        )
    }
}

@Composable
private fun FocusModeStartError(outcome: FocusModeManager.StartOutcome?) {
    val message = when (outcome) {
        FocusModeManager.StartOutcome.INVALID_DURATION -> stringResource(R.string.focus_mode_duration_invalid)
        FocusModeManager.StartOutcome.ACCESSIBILITY_REQUIRED -> stringResource(R.string.focus_mode_accessibility_required)
        FocusModeManager.StartOutcome.NOTIFICATION_ACCESS_REQUIRED -> stringResource(R.string.focus_mode_notification_access_required)
        FocusModeManager.StartOutcome.STRICT_POMODORO_ACTIVE -> stringResource(R.string.focus_mode_pomodoro_conflict)
        FocusModeManager.StartOutcome.ENFORCEMENT_FAILED -> stringResource(R.string.focus_mode_start_failed)
        FocusModeManager.StartOutcome.STARTED,
        null -> null
    }
    if (message != null) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.error,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
            maxLines = 2
        )
    }
}

private fun focusIndexToMinutes(index: Int): Int = if (index <= 0) 1 else index * 5

private fun focusMinutesToIndex(minutes: Int): Int = when {
    minutes <= 1 -> 0
    else -> (minutes / 5f).roundToInt().coerceIn(1, FOCUS_HTML_MAX_INDEX)
}

private fun focusDurationDisplay(minutes: Int): Pair<String, String> {
    if (minutes < 60) return minutes.toString() to "min"
    val hours = minutes / 60
    val remainder = minutes % 60
    return if (remainder == 0) {
        hours.toString() to if (hours == 1) "hora" else "horas"
    } else {
        String.format(Locale.getDefault(), "%d:%02d", hours, remainder) to "h"
    }
}

private fun focusDurationLabel(minutes: Int): String {
    if (minutes < 60) return "$minutes min"
    val hours = minutes / 60
    val remainder = minutes % 60
    return if (remainder == 0) "${hours} h" else String.format(Locale.getDefault(), "%d:%02d h", hours, remainder)
}

private fun formatClock(timeMillis: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timeMillis))

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
    if (!opened) runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
}

private fun openAccessibilitySettings(context: Context) {
    val opened = runCatching {
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        true
    }.getOrDefault(false)
    if (!opened) runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
}

private fun launchFocusIntent(context: Context, intent: Intent) {
    runCatching { context.startActivity(intent) }
}
