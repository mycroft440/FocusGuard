package com.focusguard.ui.compose.screens

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.R
import com.focusguard.admin.DeviceOwnerManager
import com.focusguard.focusmode.FocusModePolicy
import com.focusguard.focusmode.FocusModeStore
import com.focusguard.manager.PomodoroManager
import com.focusguard.pomodoro.PomodoroPhase
import com.focusguard.pomodoro.PomodoroPlanConfig
import com.focusguard.pomodoro.PomodoroPlanStore
import com.focusguard.pomodoro.PomodoroProfile
import com.focusguard.pomodoro.PomodoroUiSignal
import com.focusguard.security.AuthManager
import com.focusguard.security.ProtectionPermissionGate
import com.focusguard.ui.compose.theme.AccentCyan
import com.focusguard.ui.compose.theme.CardBorder
import com.focusguard.ui.compose.theme.DarkBg
import com.focusguard.ui.compose.theme.DarkCard
import com.focusguard.ui.compose.theme.DarkCardElevated
import com.focusguard.ui.compose.theme.DarkSurface
import com.focusguard.ui.compose.theme.TextHint
import com.focusguard.ui.compose.theme.TextPrimary
import com.focusguard.ui.compose.theme.TextSecondary
import com.focusguard.widget.PomodoroWidgetProvider
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

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
    val deviceOwnerManager = remember { DeviceOwnerManager.getInstance(context.applicationContext) }
    val planStore = remember(context) { PomodoroPlanStore(context) }
    val scope = rememberCoroutineScope()

    var config by remember { mutableStateOf(planStore.loadConfig()) }
    var profiles by remember { mutableStateOf(planStore.allProfiles()) }
    var selectedMinutes by remember { mutableIntStateOf(config.focusMinutes.coerceIn(1, 120)) }
    var isBlockingEnabled by remember { mutableStateOf(config.strictBlocking) }
    var showConfig by remember { mutableStateOf(false) }
    var showStrictBlockWarning by remember { mutableStateOf(false) }
    var showFocusModeConflict by remember { mutableStateOf(false) }
    val focusModeActive = compactLayout || FocusModeStore.isActive(context)

    val currentSession by pomodoroManager.currentSession.collectAsState()
    val cycleState by pomodoroManager.cycleState.collectAsState()
    val timeLeftMillis by pomodoroManager.timeLeftMillis.collectAsState()
    val isRunning = currentSession?.isActive == true
    val isStrictBlockingActive = currentSession?.isActive == true &&
        currentSession?.isBlockingEnabled == true &&
        (currentSession?.endTime ?: 0L) > System.currentTimeMillis()
    val remainingMinutes = (timeLeftMillis / 60_000L).toInt()
    val remainingSeconds = ((timeLeftMillis % 60_000L) / 1_000L).toInt()
    val sessionDurationMillis = currentSession?.durationMillis ?: 0L
    val progress = if (isRunning && sessionDurationMillis > 0L) {
        timeLeftMillis.toFloat() / sessionDurationMillis
    } else {
        1f
    }

    fun applyConfig(newConfig: PomodoroPlanConfig) {
        config = pomodoroManager.saveConfig(newConfig)
        selectedMinutes = config.focusMinutes.coerceIn(1, 120)
        isBlockingEnabled = config.strictBlocking && !focusModeActive
        profiles = planStore.allProfiles()
        PomodoroWidgetProvider.updateAll(context)
    }

    LaunchedEffect(Unit) {
        PomodoroUiSignal.configRequests.collect {
            if (!isRunning) showConfig = true
        }
    }

    LaunchedEffect(focusModeActive) {
        if (focusModeActive && isBlockingEnabled) {
            isBlockingEnabled = false
            config = pomodoroManager.saveConfig(config.copy(strictBlocking = false))
            showStrictBlockWarning = false
        }
    }

    BackHandler(enabled = isStrictBlockingActive) {
        // O bloqueio rigoroso só termina quando o intervalo de foco expira.
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

    if (showConfig && !isRunning) {
        PomodoroConfigDialog(
            onDismiss = {
                showConfig = false
                config = planStore.loadConfig()
                selectedMinutes = config.focusMinutes.coerceIn(1, 120)
                isBlockingEnabled = config.strictBlocking && !focusModeActive
                profiles = planStore.allProfiles()
            },
            onConfigChanged = { updated ->
                config = updated
                selectedMinutes = updated.focusMinutes.coerceIn(1, 120)
                isBlockingEnabled = updated.strictBlocking && !focusModeActive
                profiles = planStore.allProfiles()
            }
        )
    }

    Scaffold(containerColor = DarkBg) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(
                    horizontal = if (compactLayout) 8.dp else 16.dp,
                    vertical = 12.dp
                ),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val phase = cycleState?.phase ?: PomodoroPhase.FOCUS
            val statusText = when {
                isStrictBlockingActive -> stringResource(R.string.pomodoro_phase_focus)
                isRunning && phase == PomodoroPhase.SHORT_BREAK ->
                    stringResource(R.string.pomodoro_phase_short_break)
                isRunning && phase == PomodoroPhase.LONG_BREAK ->
                    stringResource(R.string.pomodoro_phase_long_break)
                isRunning -> stringResource(R.string.pomodoro_phase_focus)
                else -> stringResource(R.string.pomodoro_status_ready)
            }
            Text(
                text = statusText,
                color = AccentCyan,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center
            )

            cycleState?.takeIf { it.active }?.let { runtime ->
                Spacer(Modifier.height(5.dp))
                Text(
                    text = if (runtime.config.targetSessions == 0) {
                        stringResource(
                            R.string.pomodoro_cycle_progress_infinite,
                            runtime.completedFocusSessions
                        )
                    } else {
                        val currentNumber = when (runtime.phase) {
                            PomodoroPhase.FOCUS -> runtime.completedFocusSessions + 1
                            else -> runtime.completedFocusSessions
                        }.coerceAtMost(runtime.config.targetSessions)
                        stringResource(
                            R.string.pomodoro_cycle_progress,
                            currentNumber,
                            runtime.config.targetSessions
                        )
                    },
                    color = TextHint,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                )
            }

            if (isStrictBlockingActive) {
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.pomodoro_strict_calls_only_notice),
                    color = TextHint,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(Modifier.height(if (compactLayout) 12.dp else 18.dp))

            StopwatchTimer(
                minutes = if (isRunning) remainingMinutes else selectedMinutes,
                seconds = if (isRunning) remainingSeconds else 0,
                progress = progress,
                isActive = isRunning,
                onMinutesChange = { minutes ->
                    selectedMinutes = minutes
                    config = pomodoroManager.saveConfig(config.copy(focusMinutes = minutes))
                    PomodoroWidgetProvider.updateAll(context)
                },
                modifier = Modifier.size(if (compactLayout) 208.dp else 260.dp)
            )

            Spacer(Modifier.height(10.dp))
            DigitalClockDisplay(
                minutes = if (isRunning) remainingMinutes else selectedMinutes,
                seconds = if (isRunning) remainingSeconds else 0
            )

            Spacer(Modifier.height(14.dp))

            if (!isRunning) {
                PomodoroPlanSummary(config = config.copy(focusMinutes = selectedMinutes))
                Spacer(Modifier.height(12.dp))
                QuickProfiles(
                    profiles = profiles,
                    onApply = { profile -> applyConfig(profile.config) }
                )
                Spacer(Modifier.height(12.dp))

                BlockingToggleCard(
                    isBlockingEnabled = isBlockingEnabled,
                    enabled = !focusModeActive,
                    onToggle = { enabled ->
                        if (!enabled) {
                            isBlockingEnabled = false
                            config = pomodoroManager.saveConfig(config.copy(strictBlocking = false))
                        } else if (FocusModeStore.isActive(context)) {
                            showFocusModeConflict = true
                        } else if (ProtectionPermissionGate.read(context).isReady) {
                            showStrictBlockWarning = true
                        } else {
                            onPermissionsRequired()
                        }
                    }
                )

                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = {
                        val plan = config.copy(
                            focusMinutes = selectedMinutes,
                            strictBlocking = isBlockingEnabled
                        )
                        val notificationAccessMissing =
                            (plan.silenceNotifications && !pomodoroManager.hasNotificationPolicyAccess()) ||
                                (plan.hideNotifications && !pomodoroManager.hasNotificationListenerAccess())
                        when {
                            notificationAccessMissing -> showConfig = true
                            isBlockingEnabled && FocusModeStore.isActive(context) -> {
                                showFocusModeConflict = true
                            }
                            isBlockingEnabled && !ProtectionPermissionGate.read(context).isReady -> {
                                onPermissionsRequired()
                            }
                            else -> scope.launch {
                                runCatching { pomodoroManager.startPlan(plan) }
                                    .onFailure {
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.pomodoro_start_failed),
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                            }
                        }
                    },
                    modifier = Modifier.width(220.dp).height(46.dp),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 18.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentCyan)
                ) {
                    Text(
                        stringResource(R.string.pomodoro_start_btn),
                        color = DarkBg,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            } else {
                Spacer(Modifier.height(12.dp))
                if (isStrictBlockingActive) {
                    Button(
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(
                            Icons.Default.Phone,
                            contentDescription = stringResource(R.string.content_emergency_call),
                            tint = DarkBg
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.pomodoro_phone_btn), color = DarkBg)
                    }
                } else {
                    Button(
                        onClick = { scope.launch { pomodoroManager.stopSession() } },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Red.copy(alpha = 0.7f)
                        ),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text(stringResource(R.string.pomodoro_cancel_timer_btn))
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
        }
    }

    if (showStrictBlockWarning) {
        AlertDialog(
            onDismissRequest = { showStrictBlockWarning = false },
            title = { Text(stringResource(R.string.aviso_de_bloqueio_rigoroso)) },
            text = { Text(stringResource(R.string.o_bloqueio_rigoroso_ira_impedir_que_voce)) },
            confirmButton = {
                Button(onClick = {
                    showStrictBlockWarning = false
                    if (FocusModeStore.isActive(context)) {
                        isBlockingEnabled = false
                        showFocusModeConflict = true
                    } else {
                        isBlockingEnabled = true
                        config = pomodoroManager.saveConfig(config.copy(strictBlocking = true))
                    }
                }) { Text(stringResource(R.string.ativar)) }
            },
            dismissButton = {
                TextButton(onClick = { showStrictBlockWarning = false }) {
                    Text(stringResource(R.string.pomodoro_cancel_btn))
                }
            }
        )
    }

    if (showFocusModeConflict) {
        AlertDialog(
            onDismissRequest = { showFocusModeConflict = false },
            title = { Text(stringResource(R.string.focus_mode_conflict_title)) },
            text = { Text(stringResource(R.string.focus_mode_pomodoro_conflict)) },
            confirmButton = {
                TextButton(onClick = { showFocusModeConflict = false }) {
                    Text(stringResource(R.string.status_close))
                }
            }
        )
    }
}

@Composable
private fun PomodoroPlanSummary(config: PomodoroPlanConfig) {
    val longBreak = "%02d:%02d".format(config.longBreakMinutes / 60, config.longBreakMinutes % 60)
    val sessions = if (config.targetSessions == 0) {
        stringResource(R.string.pomodoro_plan_sessions_infinite)
    } else {
        stringResource(R.string.pomodoro_plan_sessions_finite, config.targetSessions)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        border = BorderStroke(1.dp, CardBorder),
        shape = RoundedCornerShape(14.dp)
    ) {
        Text(
            stringResource(
                R.string.pomodoro_plan_summary,
                config.focusMinutes,
                config.shortBreakMinutes,
                longBreak,
                config.longBreakEvery,
                sessions
            ),
            color = TextSecondary,
            fontSize = 11.sp,
            modifier = Modifier.padding(12.dp),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun QuickProfiles(
    profiles: List<PomodoroProfile>,
    onApply: (PomodoroProfile) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.pomodoro_quick_profiles_title),
            color = TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            profiles.forEach { profile ->
                val name = when (profile.id) {
                    "builtin-classic" -> stringResource(R.string.pomodoro_profile_classic)
                    "builtin-deep" -> stringResource(R.string.pomodoro_profile_deep)
                    "builtin-sprint" -> stringResource(R.string.pomodoro_profile_sprint)
                    else -> profile.name
                }
                OutlinedButton(onClick = { onApply(profile) }) {
                    Text(name, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
fun StopwatchTimer(
    minutes: Int,
    seconds: Int,
    progress: Float,
    isActive: Boolean,
    onMinutesChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier.pointerInput(isActive) {
            if (!isActive) {
                detectDragGestures { change, _ ->
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val x = change.position.x - center.x
                    val y = change.position.y - center.y
                    var angle = atan2(y, x) * (180 / PI).toFloat() + 90f
                    if (angle < 0) angle += 360f
                    onMinutesChange((angle / 3f).roundToInt().coerceIn(1, 120))
                }
            }
        }
    ) {
        val radius = size.minDimension / 2f
        val center = Offset(size.width / 2f, size.height / 2f)
        val outerRadius = radius * 0.96f
        val dialRadius = radius * 0.84f
        val tickOuter = radius * 0.78f
        val tickMajorInner = radius * 0.68f
        val tickMinorInner = radius * 0.73f

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(DarkCardElevated, DarkCard, DarkBg),
                center = center,
                radius = outerRadius
            ),
            radius = outerRadius,
            center = center
        )
        drawCircle(color = DarkSurface, radius = dialRadius, center = center)
        drawCircle(
            color = AccentCyan.copy(alpha = 0.16f),
            radius = outerRadius,
            center = center,
            style = Stroke(width = 10.dp.toPx())
        )
        drawArc(
            color = AccentCyan,
            startAngle = -90f,
            sweepAngle = 360f * progress.coerceIn(0f, 1f),
            useCenter = false,
            style = Stroke(width = 10.dp.toPx(), cap = StrokeCap.Round),
            topLeft = Offset(center.x - outerRadius, center.y - outerRadius),
            size = Size(outerRadius * 2f, outerRadius * 2f)
        )
        drawCircle(
            color = AccentCyan.copy(alpha = 0.38f),
            radius = dialRadius,
            center = center,
            style = Stroke(width = 2.dp.toPx())
        )

        for (minute in 0 until 120) {
            val angleRad = ((minute * 3f - 90f) * PI / 180f).toFloat()
            val major = minute % 10 == 0
            val startRadius = if (major) tickMajorInner else tickMinorInner
            val start = Offset(
                center.x + cos(angleRad) * startRadius,
                center.y + sin(angleRad) * startRadius
            )
            val end = Offset(
                center.x + cos(angleRad) * tickOuter,
                center.y + sin(angleRad) * tickOuter
            )
            drawLine(
                color = if (major) TextPrimary else TextHint,
                start = start,
                end = end,
                strokeWidth = if (major) 3.5.dp.toPx() else 1.6.dp.toPx(),
                cap = StrokeCap.Round
            )
        }

        val textPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            textAlign = android.graphics.Paint.Align.CENTER
            textSize = 20.sp.toPx()
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }
        for (minute in 0 until 120 step 10) {
            val label = if (minute == 0) "120" else minute.toString()
            val angleRad = ((minute * 3f - 90f) * PI / 180f).toFloat()
            val textRadius = radius * 0.57f
            drawContext.canvas.nativeCanvas.drawText(
                label,
                center.x + cos(angleRad) * textRadius,
                center.y + sin(angleRad) * textRadius + textPaint.textSize / 3f,
                textPaint
            )
        }

        val currentMinutes = if (isActive) {
            minutes.toFloat() + seconds.toFloat() / 60f
        } else {
            minutes.toFloat()
        }
        val handAngle = ((currentMinutes * 3f) - 90f) * PI / 180f
        val handLength = radius * 0.46f
        val handEnd = Offset(
            center.x + cos(handAngle).toFloat() * handLength,
            center.y + sin(handAngle).toFloat() * handLength
        )
        val baseWidth = 5.dp.toPx()
        val tipWidth = 1.dp.toPx()
        val perpendicular = handAngle + PI / 2.0
        val p1 = Offset(
            center.x + cos(perpendicular).toFloat() * baseWidth / 2f,
            center.y + sin(perpendicular).toFloat() * baseWidth / 2f
        )
        val p2 = Offset(
            center.x - cos(perpendicular).toFloat() * baseWidth / 2f,
            center.y - sin(perpendicular).toFloat() * baseWidth / 2f
        )
        val p3 = Offset(
            handEnd.x - cos(perpendicular).toFloat() * tipWidth / 2f,
            handEnd.y - sin(perpendicular).toFloat() * tipWidth / 2f
        )
        val p4 = Offset(
            handEnd.x + cos(perpendicular).toFloat() * tipWidth / 2f,
            handEnd.y + sin(perpendicular).toFloat() * tipWidth / 2f
        )
        val handPath = Path().apply {
            moveTo(p1.x, p1.y)
            lineTo(p2.x, p2.y)
            lineTo(p3.x, p3.y)
            lineTo(p4.x, p4.y)
            close()
        }
        drawPath(handPath, color = Color.Black.copy(alpha = 0.2f))
        drawPath(
            path = handPath,
            brush = Brush.linearGradient(
                colors = listOf(AccentCyan, AccentCyan.copy(alpha = 0.7f)),
                start = center,
                end = handEnd
            )
        )
        drawCircle(color = AccentCyan, radius = 7.dp.toPx(), center = center)
        drawCircle(color = DarkBg, radius = 3.dp.toPx(), center = center)
    }
}

@Composable
fun DigitalClockDisplay(minutes: Int, seconds: Int) {
    Surface(
        color = DarkCard,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, AccentCyan.copy(alpha = 0.35f)),
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Text(
            text = "%02d:%02d".format(minutes, seconds),
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = AccentCyan,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
        )
    }
}

@Composable
fun BlockingToggleCard(
    isBlockingEnabled: Boolean,
    enabled: Boolean = true,
    onToggle: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(DarkCard)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.pomodoro_enable_block_switch),
                color = if (enabled) TextPrimary else TextHint,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                stringResource(R.string.pomodoro_enable_block_subtitle),
                color = TextHint,
                fontSize = 11.sp
            )
        }
        Spacer(Modifier.width(16.dp))
        Switch(
            checked = isBlockingEnabled,
            onCheckedChange = onToggle,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = AccentCyan,
                checkedTrackColor = AccentCyan.copy(alpha = 0.5f)
            )
        )
    }
}
