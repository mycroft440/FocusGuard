package com.focusguard.ui.compose.screens

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import com.focusguard.manager.PomodoroManager
import com.focusguard.security.AuthManager
import com.focusguard.ui.compose.theme.AccentCyan
import com.focusguard.ui.compose.theme.DarkBg
import com.focusguard.ui.compose.theme.DarkCard
import com.focusguard.ui.compose.theme.DarkCardElevated
import com.focusguard.ui.compose.theme.DarkSurface
import com.focusguard.ui.compose.theme.TextHint
import com.focusguard.ui.compose.theme.TextPrimary
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
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val deviceOwnerManager = remember { DeviceOwnerManager.getInstance(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var isBlockingEnabled by remember { mutableStateOf(false) }
    var showStrictBlockWarning by remember { mutableStateOf(false) }
    var selectedMinutes by remember { mutableIntStateOf(25) }

    val currentSession by pomodoroManager.currentSession.collectAsState()
    val timeLeftMillis by pomodoroManager.timeLeftMillis.collectAsState()
    val isRunning = currentSession != null
    val isStrictBlockingActive = currentSession?.isActive == true && currentSession?.isBlockingEnabled == true && currentSession?.endTime ?: 0L > System.currentTimeMillis()
    val remainingMinutes = (timeLeftMillis / 60000).toInt()
    val remainingSeconds = ((timeLeftMillis % 60000) / 1000).toInt()
    val sessionDurationMillis = currentSession?.durationMillis ?: 0L
    val progress = if (isRunning && sessionDurationMillis > 0L) {
        timeLeftMillis.toFloat() / sessionDurationMillis
    } else {
        1f
    }

    BackHandler(enabled = isStrictBlockingActive) {
        // Bloqueio rigoroso: botão voltar não faz nada até o tempo acabar.
    }

    LaunchedEffect(isStrictBlockingActive) {
        if (isStrictBlockingActive) {
            deviceOwnerManager.prepareStrictPomodoroLockTaskPackages()
            runCatching { activity?.startLockTask() }
        } else {
            runCatching { activity?.stopLockTask() }
            deviceOwnerManager.clearStrictPomodoroLockTaskPackages()
        }
    }

    Scaffold(containerColor = DarkBg) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (isStrictBlockingActive) "Bloqueio Rigoroso ativo" else if (isRunning) stringResource(R.string.pomodoro_status_focus) else stringResource(R.string.pomodoro_status_ready),
                    color = AccentCyan,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center
                )

                if (isStrictBlockingActive) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Você só poderá usar o telefone para ligações até o tempo acabar.",
                        color = TextHint,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                StopwatchTimer(
                    minutes = if (isRunning) remainingMinutes else selectedMinutes,
                    seconds = if (isRunning) remainingSeconds else 0,
                    progress = progress,
                    isActive = isRunning,
                    onMinutesChange = { selectedMinutes = it },
                    modifier = Modifier.size(280.dp)
                )

                Spacer(modifier = Modifier.height(14.dp))

                DigitalClockDisplay(
                    minutes = if (isRunning) remainingMinutes else selectedMinutes,
                    seconds = if (isRunning) remainingSeconds else 0
                )

                Spacer(modifier = Modifier.height(18.dp))

                if (showStrictBlockWarning) {
                    AlertDialog(
                        onDismissRequest = { showStrictBlockWarning = false },
                        title = { Text(stringResource(R.string.aviso_de_bloqueio_rigoroso)) },
                        text = { Text(stringResource(R.string.o_bloqueio_rigoroso_ira_impedir_que_voce)) },
                        confirmButton = {
                            Button(onClick = {
                                showStrictBlockWarning = false
                                isBlockingEnabled = true
                            }) { Text(stringResource(R.string.ativar)) }
                        },
                        dismissButton = {
                            TextButton(onClick = { showStrictBlockWarning = false }) { Text(stringResource(R.string.pomodoro_cancel_btn)) }
                        }
                    )
                }

                if (!isRunning) {
                    BlockingToggleCard(
                        isBlockingEnabled = isBlockingEnabled,
                        onToggle = { enabled ->
                            if (enabled) showStrictBlockWarning = true else isBlockingEnabled = false
                        }
                    )

                    Spacer(modifier = Modifier.height(18.dp))

                    Button(
                        onClick = {
                            scope.launch {
                                pomodoroManager.startSession(selectedMinutes, isBlockingEnabled = isBlockingEnabled)
                            }
                        },
                        modifier = Modifier.width(220.dp).height(44.dp),
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 0.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentCyan)
                    ) {
                        Text(stringResource(R.string.pomodoro_start_btn), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                } else {
                    Spacer(modifier = Modifier.height(24.dp))

                    if (currentSession?.isBlockingEnabled == true) {
                        Button(
                            onClick = {
                                runCatching {
                                    val intent = Intent(Intent.ACTION_DIAL).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(intent)
                                }
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
fun StopwatchTimer(
    minutes: Int,
    seconds: Int,
    progress: Float,
    isActive: Boolean,
    onMinutesChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .pointerInput(isActive) {
                if (!isActive) {
                    detectDragGestures { change, _ ->
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val x = change.position.x - center.x
                        val y = change.position.y - center.y
                        var angle = atan2(y, x) * (180 / PI).toFloat() + 90f
                        if (angle < 0) angle += 360f

                        val newMinutes = (angle / 3f).roundToInt().coerceIn(1, 120)
                        onMinutesChange(newMinutes)
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
        drawCircle(color = AccentCyan.copy(alpha = 0.16f), radius = outerRadius, center = center, style = Stroke(width = 10.dp.toPx()))
        drawArc(
            color = AccentCyan,
            startAngle = -90f,
            sweepAngle = 360f * progress.coerceIn(0f, 1f),
            useCenter = false,
            style = Stroke(width = 10.dp.toPx(), cap = StrokeCap.Round),
            topLeft = Offset(center.x - outerRadius, center.y - outerRadius),
            size = Size(outerRadius * 2f, outerRadius * 2f)
        )
        drawCircle(color = AccentCyan.copy(alpha = 0.38f), radius = dialRadius, center = center, style = Stroke(width = 2.dp.toPx()))

        for (minute in 0 until 120) {
            val angleRad = ((minute * 3f - 90f) * PI / 180f).toFloat()
            val isMajor = minute % 10 == 0
            val startRadius = if (isMajor) tickMajorInner else tickMinorInner
            val strokeWidth = if (isMajor) 3.5.dp.toPx() else 1.6.dp.toPx()
            val start = Offset(center.x + cos(angleRad) * startRadius, center.y + sin(angleRad) * startRadius)
            val end = Offset(center.x + cos(angleRad) * tickOuter, center.y + sin(angleRad) * tickOuter)
            drawLine(
                color = if (isMajor) TextPrimary else TextHint,
                start = start,
                end = end,
                strokeWidth = strokeWidth,
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

        val currentMinutes = if (isActive) minutes.toFloat() + (seconds.toFloat() / 60f) else minutes.toFloat()
        val handAngle = ((currentMinutes * 3f) - 90f) * PI / 180f
        val handLength = radius * 0.46f
        val handEnd = Offset(
            center.x + cos(handAngle).toFloat() * handLength,
            center.y + sin(handAngle).toFloat() * handLength
        )

        val baseWidth = 5.dp.toPx()
        val tipWidth = 1.dp.toPx()
        val anglePerp = handAngle + PI / 2.0
        val p1 = Offset(center.x + cos(anglePerp).toFloat() * (baseWidth / 2f), center.y + sin(anglePerp).toFloat() * (baseWidth / 2f))
        val p2 = Offset(center.x - cos(anglePerp).toFloat() * (baseWidth / 2f), center.y - sin(anglePerp).toFloat() * (baseWidth / 2f))
        val p3 = Offset(handEnd.x - cos(anglePerp).toFloat() * (tipWidth / 2f), handEnd.y - sin(anglePerp).toFloat() * (tipWidth / 2f))
        val p4 = Offset(handEnd.x + cos(anglePerp).toFloat() * (tipWidth / 2f), handEnd.y + sin(anglePerp).toFloat() * (tipWidth / 2f))

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

        val hollowRadius = 5.dp.toPx()
        val tipCenter = Offset(
            center.x + cos(handAngle).toFloat() * (handLength + hollowRadius + 4.dp.toPx()),
            center.y + sin(handAngle).toFloat() * (handLength + hollowRadius + 4.dp.toPx())
        )

        drawCircle(color = AccentCyan, radius = hollowRadius, center = tipCenter, style = Stroke(width = 2.dp.toPx()))
        drawCircle(color = AccentCyan, radius = 1.dp.toPx(), center = tipCenter)
        drawCircle(color = AccentCyan, radius = 7.dp.toPx(), center = center)
        drawCircle(color = DarkBg, radius = 3.dp.toPx(), center = center)
    }
}

@Composable
fun DigitalClockDisplay(
    minutes: Int,
    seconds: Int
) {
    Surface(
        color = DarkCard,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, AccentCyan.copy(alpha = 0.35f)),
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = String.format("%02d", minutes),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = AccentCyan,
                fontFamily = MaterialTheme.typography.displayLarge.fontFamily
            )
            Text(
                text = ":",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = AccentCyan,
                modifier = Modifier.padding(horizontal = 2.dp)
            )
            Text(
                text = String.format("%02d", seconds),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = AccentCyan
            )
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
