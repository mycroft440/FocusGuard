package com.focusguard.ui.compose.screens

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Timer
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.focusguard.manager.PomodoroManager
import com.focusguard.ui.compose.theme.*
import kotlinx.coroutines.launch

@Composable
fun PomodoroScreen(
    pomodoroManager: PomodoroManager,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    
    val currentSession by pomodoroManager.currentSession.collectAsState()
    val timeLeftMillis by pomodoroManager.timeLeftMillis.collectAsState()
    
    val isActive = currentSession?.isActive == true
    
    // MODO IMERSIVO (TELA INTEIRA)
    LaunchedEffect(isActive) {
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

    // BLOQUEIO DO BOTÃƒO VOLTAR
    BackHandler(enabled = isActive) {
        // NÃ£o faz nada: impede a saÃda da tela
    }

    // KIOSK MODE (Lock Task) - Se Device Owner
    LaunchedEffect(isActive) {
        if (isActive) {
            try {
                activity?.startLockTask()
            } catch (_: Exception) {}
        } else {
            try {
                activity?.stopLockTask()
            } catch (_: Exception) {}
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
            modifier = Modifier.padding(24.dp)
        ) {
            Text(
                text = if (currentSession?.isBreak == true) "Descanso" else "Foco Total",
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = if (currentSession?.isBreak == true) AccentPurple else AccentCyan,
                textAlign = TextAlign.Center
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = if (isActive) "Bloqueio Imersivo Ativo" else "Selecione a duraÃ§Ã£o",
                fontSize = 14.sp,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )
            
            Spacer(modifier = Modifier.height(48.dp))
            
            // Timer Circular Premium
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
                        fontSize = 72.sp,
                        fontWeight = FontWeight.Black,
                        color = TextPrimary
                    )
                    
                    if (isActive) {
                        Icon(
                            Icons.Default.Timer, 
                            contentDescription = null, 
                            tint = if (currentSession?.isBreak == true) AccentPurple else AccentCyan,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(64.dp))
            
            if (!isActive) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    PomodoroOption(label = "15m", onClick = { scope.launch { pomodoroManager.startSession(15) } })
                    PomodoroOption(label = "25m", onClick = { scope.launch { pomodoroManager.startSession(25) } })
                    PomodoroOption(label = "45m", onClick = { scope.launch { pomodoroManager.startSession(45) } })
                }
                
                Spacer(modifier = Modifier.height(48.dp))
                
                OutlinedButton(
                    onClick = onBack,
                    modifier = Modifier.width(200.dp),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder)
                ) {
                    Text("Sair", color = TextSecondary)
                }
            } else {
                Card(
                    colors = CardDefaults.cardColors(containerColor = DarkCard.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        "O FocusGuard travou esta tela.\nSaÃda nÃ£o permitida atÃ© o fim do timer.",
                        color = TextHint,
                        textAlign = TextAlign.Center,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun CircularTimerProgress(progress: Float, color: Color) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(1000, easing = LinearEasing),
        label = "TimerProgress"
    )
    
    Canvas(modifier = Modifier.size(300.dp)) {
        // Track
        drawCircle(
            color = DarkCard,
            style = Stroke(width = 14.dp.toPx())
        )
        // Progress
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
        modifier = Modifier.size(80.dp, 50.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = DarkCard,
            contentColor = AccentCyan
        )
    ) {
        Text(label, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}
