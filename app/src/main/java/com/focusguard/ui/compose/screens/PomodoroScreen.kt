package com.focusguard.ui.compose.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Timer
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.app.NotificationManager
import android.os.Build
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.res.stringResource
import com.focusguard.R
import com.focusguard.manager.PomodoroManager
import com.focusguard.security.AuthManager
import com.focusguard.utils.FocusGuardLogger
import com.focusguard.ui.compose.theme.*
import kotlinx.coroutines.launch

@Composable
fun PomodoroScreen(
    pomodoroManager: PomodoroManager,
    authManager: AuthManager,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? androidx.fragment.app.FragmentActivity
    val scope = rememberCoroutineScope()

    val currentSession by pomodoroManager.currentSession.collectAsState()
    val timeLeftMillis by pomodoroManager.timeLeftMillis.collectAsState()
    var isBlockingEnabled by remember { mutableStateOf(false) }
    var showBlockingWarning by remember { mutableStateOf(false) }
    var showSummary by remember { mutableStateOf(false) }
    var customMinutesText by remember { mutableStateOf("") }
    var countdown by remember { mutableIntStateOf(4) }

    LaunchedEffect(Unit) {
        pomodoroManager.onSessionFinished.collect {
            showSummary = true
        }
    }

    DisposableEffect(activity) {
        onDispose {
            activity?.window?.let { window ->
                WindowInsetsControllerCompat(window, window.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    if (showBlockingWarning) {
        LaunchedEffect(showBlockingWarning) {
            countdown = 4
            while (countdown > 0) {
                kotlinx.coroutines.delay(1000)
                countdown--
            }
        }

        AlertDialog(
            onDismissRequest = { showBlockingWarning = false },
            containerColor = DarkSurface,
            title = { Text(stringResource(R.string.pomodoro_enable_block_title), color = AccentCyan, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    stringResource(R.string.pomodoro_enable_block_desc),
                    color = TextSecondary,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        activity?.let {
                            authManager.showBiometricPrompt(
                                activity = it,
                                onSuccess = {
                                    isBlockingEnabled = true
                                    showBlockingWarning = false
                                },
                                onError = { error: String ->
                                    Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
                                    showBlockingWarning = false
                                }
                            )
                        } ?: run {
                            isBlockingEnabled = true
                            showBlockingWarning = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                    enabled = countdown == 0
                ) {
                    Text(
                        if (countdown > 0) stringResource(R.string.pomodoro_confirm_btn_countdown, countdown)
                        else stringResource(R.string.pomodoro_confirm_btn),
                        color = DarkBg
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showBlockingWarning = false }) {
                    Text(stringResource(R.string.pomodoro_cancel_btn), color = TextSecondary)
                }
            }
        )
    }

    if (showSummary) {
        AlertDialog(
            onDismissRequest = { showSummary = false },
            containerColor = DarkSurface,
            icon = { Icon(Icons.Default.Timer, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(48.dp)) },
            title = { Text(stringResource(R.string.pomodoro_summary_title), color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.pomodoro_summary_desc), color = TextSecondary, textAlign = TextAlign.Center) },
            confirmButton = {
                Button(
                    onClick = { showSummary = false },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentCyan)
                ) {
                    Text(stringResource(R.string.pomodoro_summary_confirm), color = DarkBg)
                }
            }
        )
    }

    val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
    val isActive = currentSession?.isActive == true

    val onStart: (Int) -> Unit = { mins ->
        if (mins > 0) {
            if (isBlockingEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && notificationManager?.isNotificationPolicyAccessGranted == false) {
                val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                context.startActivity(intent)
            } else {
                scope.launch { pomodoroManager.startSession(mins, isBlockingEnabled = isBlockingEnabled) }
            }
        }
    }

    LaunchedEffect(isActive) {
        FocusGuardLogger.log("PomodoroScreen", "Estado Imersivo alterado: $isActive")
        activity?.window?.let { window ->
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            if (isActive) {
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    BackHandler(enabled = isActive && currentSession?.isBlockingEnabled == true) {
        FocusGuardLogger.log("PomodoroScreen", "Tentativa de voltar negada: Pomodoro ativo com bloqueio.")
    }

    LaunchedEffect(isActive) {
        if (isActive && currentSession?.isBlockingEnabled == true) {
            try {
                activity?.startLockTask()
            } catch (e: Exception) {
                FocusGuardLogger.logError("PomodoroScreen", "Falha ao ativar startLockTask", e)
            }
        } else {
            try {
                activity?.stopLockTask()
            } catch (e: Exception) {
                FocusGuardLogger.logError("PomodoroScreen", "Falha ao desativar stopLockTask", e)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 8.dp)
        ) {
            Text(
                text = if (currentSession?.isBreak == true) stringResource(R.string.pomodoro_status_break) else stringResource(R.string.pomodoro_status_focus),
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                color = if (currentSession?.isBreak == true) AccentPurple else AccentCyan,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = if (isActive) {
                    if (currentSession?.isBlockingEnabled == true) stringResource(R.string.pomodoro_status_immersive) else stringResource(R.string.pomodoro_status_running)
                } else stringResource(R.string.pomodoro_status_idle),
                fontSize = 14.sp,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )

            if (!isActive) {
                Spacer(modifier = Modifier.height(14.dp))
                BlockingToggleCard(
                    isBlockingEnabled = isBlockingEnabled,
                    onToggle = { checked ->
                        if (checked) showBlockingWarning = true else isBlockingEnabled = false
                    }
                )
            }

            Spacer(modifier = Modifier.height(if (isActive) 40.dp else 16.dp))

            Box(contentAlignment = Alignment.Center) {
                val progress = if (isActive && currentSession != null) {
                    timeLeftMillis.toFloat() / currentSession!!.durationMillis.toFloat()
                } else 1f

                CircularTimerProgress(
                    progress = progress,
                    color = if (currentSession?.isBreak == true) AccentPurple else AccentCyan
                )

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val minutes = (timeLeftMillis / 1000) / 60
                    val seconds = (timeLeftMillis / 1000) % 60
                    Text(
                        text = if (isActive) String.format("%02d:%02d", minutes, seconds) else "25:00",
                        fontSize = 64.sp,
                        fontWeight = FontWeight.Black,
                        color = TextPrimary
                    )

                    if (isActive) {
                        Icon(
                            imageVector = Icons.Default.Timer,
                            contentDescription = stringResource(R.string.content_timer_active),
                            tint = if (currentSession?.isBreak == true) AccentPurple else AccentCyan,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            if (!isActive) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    PomodoroOption(label = stringResource(R.string.pomodoro_option_15), onClick = { onStart(15) })
                    PomodoroOption(label = stringResource(R.string.pomodoro_option_25), onClick = { onStart(25) })
                    PomodoroOption(label = stringResource(R.string.pomodoro_option_45), onClick = { onStart(45) })
                }

                Spacer(modifier = Modifier.height(14.dp))

                CustomPomodoroTimeInput(
                    value = customMinutesText,
                    onValueChange = { newValue ->
                        if (newValue.length <= 3 && newValue.all { it.isDigit() }) {
                            customMinutesText = newValue
                        }
                    },
                    onStart = { customMinutesText.toIntOrNull()?.let(onStart) }
                )

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedButton(
                    onClick = onBack,
                    modifier = Modifier.width(200.dp).height(44.dp),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder)
                ) {
                    Text(stringResource(R.string.pomodoro_exit_btn), color = TextSecondary)
                }
            } else {
                Card(
                    colors = CardDefaults.cardColors(containerColor = DarkCard.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        if (currentSession?.isBlockingEnabled == true)
                            stringResource(R.string.pomodoro_lock_warning_blocked)
                        else
                            stringResource(R.string.pomodoro_lock_warning_free),
                        color = TextHint,
                        textAlign = TextAlign.Center,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(16.dp)
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (currentSession?.isBlockingEnabled == true) {
                        Button(
                            onClick = {
                                val intent = Intent(Intent.ACTION_DIAL)
                                context.startActivity(intent)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Icon(Icons.Default.Phone, contentDescription = stringResource(R.string.content_emergency_call))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.pomodoro_phone_btn))
                        }
                    } else {
                        Button(
                            onClick = { scope.launch { pomodoroManager.stopSession() } },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.7f)),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Text(stringResource(R.string.pomodoro_cancel_timer_btn))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BlockingToggleCard(
    isBlockingEnabled: Boolean,
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
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.pomodoro_enable_block_switch), color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.pomodoro_enable_block_subtitle), color = TextHint, fontSize = 11.sp)
        }
        Spacer(modifier = Modifier.width(16.dp))
        Switch(
            checked = isBlockingEnabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = AccentCyan,
                checkedTrackColor = AccentCyan.copy(alpha = 0.5f)
            )
        )
    }
}

@Composable
fun CustomPomodoroTimeInput(
    value: String,
    onValueChange: (String) -> Unit,
    onStart: () -> Unit
) {
    val minutes = value.toIntOrNull()
    val isValid = minutes != null && minutes > 0

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkCard.copy(alpha = 0.8f)),
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(stringResource(R.string.pomodoro_custom_time_title), color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.weight(1f).height(56.dp),
                    singleLine = true,
                    label = { Text(stringResource(R.string.pomodoro_minutes_label), color = TextHint) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentCyan,
                        unfocusedBorderColor = CardBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        cursorColor = AccentCyan
                    ),
                    shape = RoundedCornerShape(14.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Button(
                    onClick = onStart,
                    enabled = isValid,
                    modifier = Modifier.height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentCyan, disabledContainerColor = DarkCardElevated),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(stringResource(R.string.pomodoro_start_btn), color = if (isValid) DarkBg else TextHint, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun CircularTimerProgress(progress: Float, color: Color) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(1000, easing = LinearEasing),
        label = "TimerProgress"
    )

    Canvas(modifier = Modifier.size(260.dp)) {
        drawCircle(
            color = DarkCard,
            style = Stroke(width = 14.dp.toPx())
        )
        drawArc(
            brush = Brush.sweepGradient(listOf(color.copy(alpha = 0.3f), color)),
            startAngle = -90f,
            sweepAngle = 360 * animatedProgress,
            useCenter = false,
            style = Stroke(width = 14.dp.toPx(), cap = StrokeCap.Round)
        )
    }
}

@Composable
fun PomodoroOption(label: String, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier.size(88.dp, 46.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = DarkCard,
            contentColor = AccentCyan
        ),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
    ) {
        Text(label, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1)
    }
}
