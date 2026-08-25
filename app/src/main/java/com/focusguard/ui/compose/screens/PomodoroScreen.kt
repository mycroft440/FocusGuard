package com.focusguard.ui.compose.screens

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
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
import com.focusguard.focusmode.FocusModePolicy
import com.focusguard.focusmode.FocusModeStore
import com.focusguard.manager.PomodoroManager
import com.focusguard.pomodoro.PomodoroAlarmController
import com.focusguard.pomodoro.PomodoroNotificationController
import com.focusguard.pomodoro.PomodoroPhase
import com.focusguard.pomodoro.PomodoroPlanConfig
import com.focusguard.pomodoro.PomodoroPlanStore
import com.focusguard.pomodoro.PomodoroProfile
import com.focusguard.pomodoro.PomodoroUiSignal
import com.focusguard.security.AuthManager
import com.focusguard.security.ProtectionPermissionGate
import com.focusguard.service.FocusModeNotificationService
import com.focusguard.ui.compose.theme.AccentCyan
import com.focusguard.ui.compose.theme.DangerRed
import com.focusguard.ui.compose.theme.DarkBg
import com.focusguard.ui.compose.theme.DarkCard
import com.focusguard.ui.compose.theme.SuccessGreen
import com.focusguard.ui.compose.theme.TextHint
import com.focusguard.ui.compose.theme.TextPrimary
import com.focusguard.ui.compose.theme.TextSecondary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private val PomodoroPageBg = Color(0xFF05080B)
private val PomodoroSurface = Color(0xFF141B23)
private val PomodoroSurfaceMuted = Color(0xFF10161D)
private val PomodoroStroke = Color(0xFF222C36)
private val PomodoroText = Color(0xFFEDF2F7)
private val PomodoroTextDim = Color(0xFF93A1AD)
private val PomodoroTextFaint = Color(0xFF64717D)
private val PomodoroAccent = Color(0xFF5CCFE6)
private val PomodoroAccentInk = Color(0xFF04222A)
private val PomodoroAccentTint = Color(0x1F5CCFE6)
private val PomodoroAccentLine = Color(0x475CCFE6)
private val PomodoroFocus = Color(0xFFE9BA5C)
private val PomodoroFocusTint = Color(0x1FE9BA5C)
private val PomodoroFocusLine = Color(0x42E9BA5C)

@Suppress("UNUSED_PARAMETER")
@Composable
fun PomodoroScreen(
    pomodoroManager: PomodoroManager,
    authManager: AuthManager,
    onPermissionsRequired: () -> Unit,
    onBack: () -> Unit,
    compactLayout: Boolean = false
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val planStore = remember(context) { PomodoroPlanStore(context) }
    val notificationController = remember(context) { PomodoroNotificationController(context) }
    val deviceOwnerManager = remember(context) {
        DeviceOwnerManager.getInstance(context.applicationContext)
    }

    val currentSession by pomodoroManager.currentSession.collectAsState()
    val cycleState by pomodoroManager.cycleState.collectAsState()
    val timeLeftMillis by pomodoroManager.timeLeftMillis.collectAsState()
    val isRunning = currentSession?.isActive == true && cycleState?.active == true
    val isStrictBlockingActive = currentSession?.isBlockingEnabled == true &&
        currentSession?.endTime?.let { it > System.currentTimeMillis() } == true
    val focusModeActive = compactLayout || FocusModeStore.isActive(context)

    var config by remember { mutableStateOf(planStore.loadConfig()) }
    var showConfig by rememberSaveable { mutableStateOf(false) }
    var profileRevision by remember { mutableIntStateOf(0) }
    var showSaveProfile by remember { mutableStateOf(false) }
    var profileName by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsSuccess by remember { mutableStateOf(false) }
    var permissionRevision by remember { mutableIntStateOf(0) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permissionRevision++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        PomodoroUiSignal.configRequests.collect {
            showConfig = true
        }
    }

    LaunchedEffect(isStrictBlockingActive) {
        if (isStrictBlockingActive) {
            deviceOwnerManager.prepareStrictPomodoroLockTaskPackages()
            runCatching { activity?.startLockTask() }
        } else if (FocusModePolicy.canPomodoroReleaseKiosk(FocusModeStore.isActive(context))) {
            runCatching { activity?.stopLockTask() }
            deviceOwnerManager.clearStrictPomodoroLockTaskPackages()
        }
    }

    BackHandler(enabled = isStrictBlockingActive) {
        // No foco rigoroso, voltar não encerra nem contorna o período.
    }

    val hasDndAccess = remember(permissionRevision) {
        notificationController.hasPolicyAccess()
    }
    val hasNotificationAccess = remember(permissionRevision) {
        notificationController.hasNotificationListenerAccess(
            FocusModeNotificationService::class.java
        )
    }
    val profiles = remember(profileRevision) { planStore.allProfiles() }

    fun setMessage(text: String?, success: Boolean = false) {
        message = text
        messageIsSuccess = success
    }

    fun saveConfig(updated: PomodoroPlanConfig) {
        config = planStore.saveConfig(updated.normalized())
        setMessage(null)
    }

    fun startConfiguredPlan() {
        when {
            config.strictBlocking && focusModeActive -> {
                setMessage(context.getString(R.string.fg_pomodoro_disable_focus_for_strict))
            }
            config.strictBlocking && !ProtectionPermissionGate.read(context).isReady -> {
                onPermissionsRequired()
            }
            config.silenceNotifications && !hasDndAccess -> {
                setMessage(context.getString(R.string.fg_pomodoro_authorize_dnd_start))
                runCatching { context.startActivity(notificationController.policyAccessIntent()) }
            }
            config.hideNotifications && !hasNotificationAccess -> {
                setMessage(
                    context.getString(R.string.fg_pomodoro_authorize_notification_access_start)
                )
                runCatching { context.startActivity(notificationController.notificationListenerIntent()) }
            }
            else -> scope.launch {
                try {
                    pomodoroManager.startPlan(config)
                    showConfig = false
                    setMessage(null)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    setMessage(context.getString(R.string.fg_pomodoro_start_failed))
                }
            }
        }
    }

    if (showSaveProfile) {
        AlertDialog(
            onDismissRequest = { showSaveProfile = false },
            title = { Text(stringResource(R.string.fg_pomodoro_save_profile_title)) },
            text = {
                OutlinedTextField(
                    value = profileName,
                    onValueChange = {
                        profileName = it.take(PomodoroPlanStore.MAX_PROFILE_NAME_LENGTH)
                    },
                    label = { Text(stringResource(R.string.fg_pomodoro_profile_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val saved = planStore.saveProfile(profileName, config)
                        if (saved != null) {
                            profileRevision++
                            profileName = ""
                            showSaveProfile = false
                            setMessage(
                                context.getString(R.string.fg_pomodoro_profile_saved),
                                success = true
                            )
                        } else {
                            setMessage(
                                context.getString(R.string.fg_pomodoro_profile_save_failed)
                            )
                        }
                    },
                    enabled = profileName.isNotBlank()
                ) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveProfile = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    Scaffold(containerColor = PomodoroPageBg) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(
                    horizontal = if (compactLayout) 10.dp else 16.dp,
                    vertical = if (compactLayout) 8.dp else 14.dp
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (isRunning) {
                ActivePomodoroPanel(
                    phase = cycleState?.phase ?: PomodoroPhase.FOCUS,
                    completedSessions = cycleState?.completedFocusSessions ?: 0,
                    targetSessions = cycleState?.config?.targetSessions ?: 0,
                    timeLeftMillis = timeLeftMillis,
                    durationMillis = currentSession?.durationMillis ?: 1L,
                    isStrict = isStrictBlockingActive,
                    onStop = { scope.launch { pomodoroManager.stopSession() } },
                    onPhone = {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_DIAL)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }
                )
            } else {
                ReadyPomodoroHeader(strictBlocking = config.strictBlocking)

                PomodoroReferenceClock(
                    minutes = config.focusMinutes.coerceIn(1, 180),
                    maxMinutes = 180,
                    activeProgress = null,
                    remainingMillis = config.focusMinutes.coerceAtLeast(1) * 60_000L,
                    onMinutesChange = { saveConfig(config.copy(focusMinutes = it)) },
                    modifier = Modifier.size(if (compactLayout) 180.dp else 205.dp)
                )

                CurrentPlanSummary(config)

                ProfileStrip(
                    profiles = profiles,
                    currentConfig = config,
                    onUse = { profile -> saveConfig(profile.config) },
                    onDelete = { profile ->
                        if (planStore.deleteProfile(profile.id)) profileRevision++
                    }
                )

                PomodoroPrimaryActions(
                    showConfig = showConfig,
                    onToggleConfig = { showConfig = !showConfig },
                    onStart = ::startConfiguredPlan
                )

                if (showConfig) {
                    PomodoroConfigurationPanel(
                        config = config,
                        hasDndAccess = hasDndAccess,
                        hasNotificationAccess = hasNotificationAccess,
                        onConfigChange = ::saveConfig,
                        onRequestDnd = {
                            runCatching {
                                context.startActivity(notificationController.policyAccessIntent())
                            }
                        },
                        onRequestNotificationAccess = {
                            runCatching {
                                context.startActivity(
                                    notificationController.notificationListenerIntent()
                                )
                            }
                        },
                        onPreviewSound = {
                            scope.launch {
                                PomodoroAlarmController.preview(context, config.soundIndex)
                            }
                        },
                        onSaveProfile = { showSaveProfile = true }
                    )
                }
            }

            message?.let {
                Text(
                    text = it,
                    color = if (messageIsSuccess) SuccessGreen else DangerRed,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun ReadyPomodoroHeader(strictBlocking: Boolean) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .background(PomodoroAccentTint, CircleShape)
                .padding(horizontal = 13.dp, vertical = 7.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(PomodoroAccent, CircleShape)
            )
            Text(
                text = stringResource(R.string.fg_pomodoro_ready),
                color = PomodoroAccent,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.1.sp
            )
        }
        Text(
            text = stringResource(
                if (strictBlocking) {
                    R.string.pomodoro_enable_block_subtitle
                } else {
                    R.string.focus_subtitle
                }
            ),
            color = PomodoroTextDim,
            fontSize = 13.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun PomodoroPrimaryActions(
    showConfig: Boolean,
    onToggleConfig: () -> Unit,
    onStart: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedButton(
            onClick = onToggleConfig,
            modifier = Modifier
                .weight(1f)
                .height(52.dp),
            shape = CircleShape,
            border = BorderStroke(1.dp, PomodoroStroke),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = PomodoroTextDim)
        ) {
            Icon(Icons.Default.Settings, contentDescription = null)
            Spacer(Modifier.width(7.dp))
            Text(
                text = stringResource(
                    if (showConfig) {
                        R.string.fg_pomodoro_hide_config
                    } else {
                        R.string.fg_pomodoro_configure
                    }
                ),
                fontWeight = FontWeight.SemiBold
            )
        }
        Button(
            onClick = onStart,
            modifier = Modifier
                .weight(1.25f)
                .height(52.dp),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = PomodoroAccent)
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = PomodoroAccentInk)
            Spacer(Modifier.width(7.dp))
            Text(
                stringResource(R.string.fg_pomodoro_start),
                color = PomodoroAccentInk,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun ActivePomodoroPanel(
    phase: PomodoroPhase,
    completedSessions: Int,
    targetSessions: Int,
    timeLeftMillis: Long,
    durationMillis: Long,
    isStrict: Boolean,
    onStop: () -> Unit,
    onPhone: () -> Unit
) {
    val totalSeconds = (timeLeftMillis / 1_000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val progress = if (durationMillis > 0L) {
        (timeLeftMillis.toFloat() / durationMillis.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val phaseLabel = stringResource(
        when (phase) {
            PomodoroPhase.FOCUS -> R.string.fg_pomodoro_phase_focus
            PomodoroPhase.SHORT_BREAK -> R.string.fg_pomodoro_phase_short_break
            PomodoroPhase.LONG_BREAK -> R.string.fg_pomodoro_phase_long_break
        }
    )
    val phaseColor = if (phase == PomodoroPhase.FOCUS) PomodoroFocus else PomodoroAccent
    val phaseTint = if (phase == PomodoroPhase.FOCUS) PomodoroFocusTint else PomodoroAccentTint
    val phaseLine = if (phase == PomodoroPhase.FOCUS) PomodoroFocusLine else PomodoroAccentLine

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .background(phaseTint, CircleShape)
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        Box(Modifier.size(7.dp).background(phaseColor, CircleShape))
        Text(
            phaseLabel,
            color = phaseColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
    }

    PomodoroReferenceClock(
        minutes = minutes.toInt().coerceAtLeast(1),
        maxMinutes = 60,
        activeProgress = progress,
        remainingMillis = timeLeftMillis,
        onMinutesChange = {},
        modifier = Modifier.size(205.dp)
    )

    val sessionText = if (targetSessions == 0) {
        stringResource(
            R.string.fg_pomodoro_sessions_completed_unlimited,
            completedSessions
        )
    } else {
        stringResource(
            R.string.fg_pomodoro_sessions_completed_target,
            completedSessions,
            targetSessions
        )
    }
    Text(
        text = sessionText,
        color = PomodoroTextDim,
        fontSize = 13.sp,
        modifier = Modifier
            .background(PomodoroSurfaceMuted, CircleShape)
            .padding(horizontal = 13.dp, vertical = 7.dp)
    )

    if (isStrict) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = PomodoroFocusTint),
            border = BorderStroke(1.dp, phaseLine)
        ) {
            Text(
                stringResource(R.string.fg_pomodoro_strict_active_hint),
                color = PomodoroFocus,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            )
        }
        Button(
            onClick = onPhone,
            modifier = Modifier.height(50.dp),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = PomodoroAccent)
        ) {
            Icon(Icons.Default.Phone, contentDescription = null, tint = PomodoroAccentInk)
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.fg_phone),
                color = PomodoroAccentInk,
                fontWeight = FontWeight.SemiBold
            )
        }
    } else {
        Button(
            onClick = onStop,
            modifier = Modifier.height(50.dp),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = DangerRed.copy(alpha = 0.8f))
        ) {
            Text(stringResource(R.string.fg_pomodoro_stop), fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun CurrentPlanSummary(config: PomodoroPlanConfig) {
    SectionLabel(stringResource(R.string.fg_pomodoro_current_cycle))
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = PomodoroSurface),
        border = BorderStroke(1.dp, PomodoroStroke)
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 3.dp)) {
            PomodoroSummaryRow(
                label = stringResource(R.string.fg_pomodoro_focus_time),
                value = formatMinutes(config.focusMinutes)
            )
            PomodoroSummaryRow(
                label = stringResource(R.string.fg_pomodoro_break_time),
                value = formatMinutes(config.shortBreakMinutes)
            )
            PomodoroSummaryRow(
                label = stringResource(R.string.fg_pomodoro_longer_break),
                value = formatMinutes(config.longBreakMinutes)
            )
            PomodoroSummaryRow(
                label = stringResource(R.string.fg_pomodoro_long_break_every),
                value = stringResource(
                    R.string.fg_pomodoro_sessions_value,
                    config.longBreakEvery
                )
            )
            val target = if (config.targetSessions == 0) {
                stringResource(R.string.fg_pomodoro_until_i_stop)
            } else {
                config.targetSessions.toString()
            }
            PomodoroSummaryRow(
                label = stringResource(R.string.fg_pomodoro_session_count),
                value = target,
                showDivider = false
            )
        }
    }
}

@Composable
private fun PomodoroSummaryRow(
    label: String,
    value: String,
    showDivider: Boolean = true
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                color = PomodoroTextDim,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = value,
                color = PomodoroText,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        if (showDivider) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color.White.copy(alpha = 0.045f))
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        color = PomodoroTextFaint,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.25.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 2.dp, top = 7.dp, bottom = 1.dp)
    )
}

@Composable
private fun ProfileStrip(
    profiles: List<PomodoroProfile>,
    currentConfig: PomodoroPlanConfig,
    onUse: (PomodoroProfile) -> Unit,
    onDelete: (PomodoroProfile) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SectionLabel(stringResource(R.string.fg_pomodoro_profiles))
        Spacer(Modifier.height(2.dp))
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val profileGap = 8.dp
            val profileWidth = (maxWidth - profileGap * 2f) / 3f
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(profileGap)
            ) {
                profiles.forEach { profile ->
                    val selected = profile.config.normalized() == currentConfig.normalized()
                    Card(
                        modifier = Modifier
                            .width(profileWidth)
                            .clickable { onUse(profile) },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (selected) PomodoroAccentTint else PomodoroSurfaceMuted
                        ),
                        border = BorderStroke(
                            1.dp,
                            if (selected) PomodoroAccentLine else PomodoroStroke
                        )
                    ) {
                        Column(Modifier.padding(horizontal = 9.dp, vertical = 9.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    profile.name,
                                    modifier = Modifier.weight(1f),
                                    color = if (selected) PomodoroAccent else PomodoroText,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 11.5.sp,
                                    maxLines = 2
                                )
                                if (!profile.builtIn) {
                                    IconButton(
                                        onClick = { onDelete(profile) },
                                        modifier = Modifier.size(22.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = stringResource(
                                                R.string.fg_pomodoro_delete_profile_cd
                                            ),
                                            tint = PomodoroTextFaint,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(2.dp))
                            Text(
                                stringResource(
                                    R.string.fg_pomodoro_profile_summary,
                                    profile.config.focusMinutes,
                                    profile.config.shortBreakMinutes,
                                    profile.config.longBreakMinutes
                                ),
                                color = if (selected) {
                                    PomodoroAccent.copy(alpha = 0.72f)
                                } else {
                                    PomodoroTextFaint
                                },
                                fontSize = 9.5.sp,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PomodoroConfigurationPanel(
    config: PomodoroPlanConfig,
    hasDndAccess: Boolean,
    hasNotificationAccess: Boolean,
    onConfigChange: (PomodoroPlanConfig) -> Unit,
    onRequestDnd: () -> Unit,
    onRequestNotificationAccess: () -> Unit,
    onPreviewSound: () -> Unit,
    onSaveProfile: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = PomodoroSurface),
        border = BorderStroke(1.dp, PomodoroStroke)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                stringResource(R.string.fg_pomodoro_config_title),
                color = PomodoroText,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )

            MiniDurationControl(
                label = stringResource(R.string.fg_pomodoro_focus_time),
                minutes = config.focusMinutes,
                maxMinutes = 180,
                hoursMode = false,
                onChange = { onConfigChange(config.copy(focusMinutes = it)) }
            )
            MiniDurationControl(
                label = stringResource(R.string.fg_pomodoro_break_time),
                minutes = config.shortBreakMinutes,
                maxMinutes = 120,
                hoursMode = false,
                onChange = { onConfigChange(config.copy(shortBreakMinutes = it)) }
            )
            MiniDurationControl(
                label = stringResource(R.string.fg_pomodoro_longer_break),
                minutes = config.longBreakMinutes,
                maxMinutes = 720,
                hoursMode = true,
                onChange = { onConfigChange(config.copy(longBreakMinutes = it)) }
            )

            NumberSelector(
                label = stringResource(R.string.fg_pomodoro_long_break_every),
                value = config.longBreakEvery,
                values = (1..20).toList(),
                valueLabel = { stringResource(R.string.fg_pomodoro_sessions_value, it) },
                onChange = { onConfigChange(config.copy(longBreakEvery = it)) }
            )
            NumberSelector(
                label = stringResource(R.string.fg_pomodoro_session_count),
                value = config.targetSessions,
                values = (0..100).toList(),
                valueLabel = {
                    if (it == 0) {
                        stringResource(R.string.fg_pomodoro_until_i_stop)
                    } else {
                        it.toString()
                    }
                },
                onChange = { onConfigChange(config.copy(targetSessions = it)) }
            )

            ToggleRow(
                label = stringResource(R.string.fg_pomodoro_strict_focus),
                checked = config.strictBlocking,
                onCheckedChange = { onConfigChange(config.copy(strictBlocking = it)) }
            )

            Text(
                stringResource(R.string.fg_alarm),
                color = PomodoroAccent,
                fontWeight = FontWeight.Bold
            )
            ToggleRow(
                label = stringResource(R.string.fg_sound),
                checked = config.soundEnabled,
                onCheckedChange = { onConfigChange(config.copy(soundEnabled = it)) }
            )
            ToggleRow(
                label = stringResource(R.string.fg_vibration),
                checked = config.vibrationEnabled,
                onCheckedChange = { onConfigChange(config.copy(vibrationEnabled = it)) }
            )
            SoundSelector(
                selectedIndex = config.soundIndex,
                onChange = { onConfigChange(config.copy(soundIndex = it)) },
                onPreview = onPreviewSound
            )
            NumberSelector(
                label = stringResource(R.string.fg_pomodoro_alarm_duration),
                value = config.alarmDurationSeconds,
                values = listOf(1, 2, 3, 5, 8, 10, 15, 20, 30, 45, 60),
                valueLabel = { stringResource(R.string.fg_seconds_short, it) },
                onChange = { onConfigChange(config.copy(alarmDurationSeconds = it)) }
            )

            Text(
                stringResource(R.string.fg_notifications),
                color = PomodoroAccent,
                fontWeight = FontWeight.Bold
            )
            ToggleRow(
                label = stringResource(R.string.fg_pomodoro_silence_notifications),
                icon = {
                    Icon(
                        Icons.Default.NotificationsOff,
                        contentDescription = null,
                        tint = PomodoroAccent
                    )
                },
                checked = config.silenceNotifications,
                onCheckedChange = { enabled ->
                    onConfigChange(config.copy(silenceNotifications = enabled))
                    if (enabled && !hasDndAccess) onRequestDnd()
                }
            )
            if (config.silenceNotifications && !hasDndAccess) {
                PermissionHint(
                    stringResource(R.string.fg_pomodoro_dnd_pending),
                    onRequestDnd
                )
            }
            ToggleRow(
                label = stringResource(R.string.fg_pomodoro_hide_notifications_temp),
                icon = {
                    Icon(
                        Icons.Default.VisibilityOff,
                        contentDescription = null,
                        tint = PomodoroAccent
                    )
                },
                checked = config.hideNotifications,
                onCheckedChange = { enabled ->
                    onConfigChange(config.copy(hideNotifications = enabled))
                    if (enabled && !hasNotificationAccess) onRequestNotificationAccess()
                }
            )
            if (config.hideNotifications && !hasNotificationAccess) {
                PermissionHint(
                    stringResource(R.string.fg_pomodoro_notification_access_pending),
                    onRequestNotificationAccess
                )
            }

            OutlinedButton(
                onClick = onSaveProfile,
                modifier = Modifier.fillMaxWidth(),
                shape = CircleShape,
                border = BorderStroke(1.dp, PomodoroStroke),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = PomodoroTextDim)
            ) {
                Icon(Icons.Default.Save, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.fg_pomodoro_save_as_profile))
            }
        }
    }
}

@Composable
private fun PermissionHint(text: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, modifier = Modifier.weight(1f), color = DangerRed, fontSize = 11.sp)
        TextButton(onClick = onClick) {
            Text(stringResource(R.string.fg_allow))
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    icon: (@Composable () -> Unit)? = null,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon?.invoke()
        if (icon != null) Spacer(Modifier.width(8.dp))
        Text(label, modifier = Modifier.weight(1f), color = PomodoroText, fontSize = 13.sp)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SoundSelector(
    selectedIndex: Int,
    onChange: (Int) -> Unit,
    onPreview: () -> Unit
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(modifier = Modifier.weight(1f)) {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(
                        R.string.fg_pomodoro_sound_label,
                        PomodoroAlarmController.soundName(context, selectedIndex)
                    )
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.heightIn(max = 320.dp)
            ) {
                PomodoroAlarmController.sounds.forEach { sound ->
                    DropdownMenuItem(
                        text = { Text(stringResource(sound.nameRes)) },
                        onClick = {
                            onChange(sound.id)
                            expanded = false
                        }
                    )
                }
            }
        }
        IconButton(onClick = onPreview) {
            Icon(
                Icons.Default.VolumeUp,
                contentDescription = stringResource(R.string.fg_pomodoro_preview_sound_cd),
                tint = PomodoroAccent
            )
        }
    }
}

@Composable
private fun NumberSelector(
    label: String,
    value: Int,
    values: List<Int>,
    valueLabel: @Composable (Int) -> String,
    onChange: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f), color = PomodoroText, fontSize = 13.sp)
        Box {
            OutlinedButton(onClick = { expanded = true }) {
                Text(valueLabel(value))
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.heightIn(max = 300.dp)
            ) {
                values.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(valueLabel(option)) },
                        onClick = {
                            onChange(option)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun MiniDurationControl(
    label: String,
    minutes: Int,
    maxMinutes: Int,
    hoursMode: Boolean,
    onChange: (Int) -> Unit
) {
    var minutesText by rememberSaveable(minutes, hoursMode) {
        mutableStateOf(if (hoursMode) (minutes % 60).toString() else minutes.toString())
    }
    var hoursText by rememberSaveable(minutes, hoursMode) {
        mutableStateOf(if (hoursMode) (minutes / 60).toString() else "0")
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        MiniClockDial(
            minutes = minutes,
            maxMinutes = maxMinutes,
            onMinutesChange = { newValue ->
                val safe = newValue.coerceIn(1, maxMinutes)
                if (hoursMode) {
                    hoursText = (safe / 60).toString()
                    minutesText = (safe % 60).toString()
                } else {
                    minutesText = safe.toString()
                }
                onChange(safe)
            },
            modifier = Modifier.size(86.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = PomodoroText, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(5.dp))
            if (hoursMode) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CompactNumberField(
                        value = hoursText,
                        suffix = "h",
                        modifier = Modifier.weight(1f),
                        onValueChange = { raw ->
                            hoursText = raw
                            val hours = raw.toIntOrNull() ?: 0
                            val mins = minutesText.toIntOrNull() ?: 0
                            onChange((hours * 60 + mins).coerceIn(1, maxMinutes))
                        }
                    )
                    CompactNumberField(
                        value = minutesText,
                        suffix = "min",
                        modifier = Modifier.weight(1f),
                        onValueChange = { raw ->
                            minutesText = raw
                            val hours = hoursText.toIntOrNull() ?: 0
                            val mins = (raw.toIntOrNull() ?: 0).coerceIn(0, 59)
                            onChange((hours * 60 + mins).coerceIn(1, maxMinutes))
                        }
                    )
                }
            } else {
                CompactNumberField(
                    value = minutesText,
                    suffix = "min",
                    modifier = Modifier.fillMaxWidth(),
                    onValueChange = { raw ->
                        minutesText = raw
                        raw.toIntOrNull()?.let { onChange(it.coerceIn(1, maxMinutes)) }
                    }
                )
            }
        }
    }
}

@Composable
private fun CompactNumberField(
    value: String,
    suffix: String,
    modifier: Modifier,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = { raw ->
            val filtered = raw.filter(Char::isDigit).take(4)
            onValueChange(filtered)
        },
        modifier = modifier,
        singleLine = true,
        trailingIcon = { Text(suffix, color = PomodoroTextFaint, fontSize = 11.sp) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
    )
}

@Composable
private fun MiniClockDial(
    minutes: Int,
    maxMinutes: Int,
    onMinutesChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier.pointerInput(maxMinutes) {
            detectDragGestures(
                onDragStart = { position ->
                    onMinutesChange(
                        minutesFromPosition(position, size.width, size.height, maxMinutes)
                    )
                },
                onDrag = { change, _ ->
                    onMinutesChange(
                        minutesFromPosition(change.position, size.width, size.height, maxMinutes)
                    )
                }
            )
        }
    ) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = size.minDimension * 0.46f
        drawCircle(DarkBg, radius, center)
        drawCircle(
            AccentCyan.copy(alpha = 0.45f),
            radius,
            center,
            style = Stroke(2.dp.toPx())
        )
        repeat(12) { tick ->
            val angle = (tick * 30f - 90f) * PI / 180f
            val start = Offset(
                center.x + cos(angle).toFloat() * radius * 0.78f,
                center.y + sin(angle).toFloat() * radius * 0.78f
            )
            val end = Offset(
                center.x + cos(angle).toFloat() * radius * 0.92f,
                center.y + sin(angle).toFloat() * radius * 0.92f
            )
            drawLine(TextHint, start, end, 1.5.dp.toPx(), cap = StrokeCap.Round)
        }
        val fraction = minutes.coerceIn(1, maxMinutes).toFloat() / maxMinutes.toFloat()
        val angle = (fraction * 360f - 90f) * PI / 180f
        val handEnd = Offset(
            center.x + cos(angle).toFloat() * radius * 0.68f,
            center.y + sin(angle).toFloat() * radius * 0.68f
        )
        drawLine(AccentCyan, center, handEnd, 3.dp.toPx(), cap = StrokeCap.Round)
        drawCircle(AccentCyan, 4.dp.toPx(), center)
    }
}

@Composable
fun PomodoroDurationDial(
    minutes: Int,
    maxMinutes: Int,
    activeProgress: Float?,
    onMinutesChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val interactive = activeProgress == null
    Canvas(
        modifier = modifier.pointerInput(interactive, maxMinutes) {
            if (!interactive) return@pointerInput
            detectDragGestures(
                onDragStart = { position ->
                    onMinutesChange(
                        minutesFromPosition(position, size.width, size.height, maxMinutes)
                    )
                },
                onDrag = { change, _ ->
                    onMinutesChange(
                        minutesFromPosition(change.position, size.width, size.height, maxMinutes)
                    )
                }
            )
        }
    ) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = size.minDimension * 0.46f
        drawCircle(DarkCard, radius, center)
        drawCircle(
            AccentCyan.copy(alpha = 0.25f),
            radius,
            center,
            style = Stroke(10.dp.toPx())
        )
        val progress = activeProgress ?: (minutes.toFloat() / maxMinutes.toFloat())
        drawArc(
            color = AccentCyan,
            startAngle = -90f,
            sweepAngle = 360f * progress.coerceIn(0f, 1f),
            useCenter = false,
            topLeft = Offset(center.x - radius, center.y - radius),
            size = androidx.compose.ui.geometry.Size(radius * 2f, radius * 2f),
            style = Stroke(10.dp.toPx(), cap = StrokeCap.Round)
        )
        repeat(12) { tick ->
            val angle = (tick * 30f - 90f) * PI / 180f
            val start = Offset(
                center.x + cos(angle).toFloat() * radius * 0.76f,
                center.y + sin(angle).toFloat() * radius * 0.76f
            )
            val end = Offset(
                center.x + cos(angle).toFloat() * radius * 0.90f,
                center.y + sin(angle).toFloat() * radius * 0.90f
            )
            drawLine(TextHint, start, end, 2.dp.toPx(), cap = StrokeCap.Round)
        }
        if (interactive) {
            val fraction = minutes.coerceIn(1, maxMinutes).toFloat() / maxMinutes.toFloat()
            val handAngle = (fraction * 360f - 90f) * PI / 180f
            val end = Offset(
                center.x + cos(handAngle).toFloat() * radius * 0.66f,
                center.y + sin(handAngle).toFloat() * radius * 0.66f
            )
            drawLine(AccentCyan, center, end, 5.dp.toPx(), cap = StrokeCap.Round)
            drawCircle(AccentCyan, 6.dp.toPx(), center)
        }
    }
}

private fun minutesFromPosition(
    position: Offset,
    width: Int,
    height: Int,
    maxMinutes: Int
): Int {
    val centerX = width / 2f
    val centerY = height / 2f
    var angle = atan2(position.y - centerY, position.x - centerX) *
        (180f / PI.toFloat()) + 90f
    if (angle < 0f) angle += 360f
    val fraction = angle / 360f
    return (fraction * maxMinutes).roundToInt().coerceIn(1, maxMinutes)
}

private fun formatMinutes(minutes: Int): String {
    val hours = minutes / 60
    val rest = minutes % 60
    return when {
        hours <= 0 -> "$minutes min"
        rest == 0 -> "${hours}h"
        else -> "${hours}h ${rest}min"
    }
}
