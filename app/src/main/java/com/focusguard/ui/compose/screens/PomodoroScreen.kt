package com.focusguard.ui.compose.screens

import android.os.CountDownTimer
import android.widget.Toast
import androidx.compose.animation.*
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
import com.focusguard.manager.BlockingSessionManager
import com.focusguard.ui.compose.theme.*
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

@Composable
fun PomodoroScreen() {
    val context = LocalContext.current
    val sessionManager = remember { BlockingSessionManager.getInstance(context) }
    
    var isRunning by remember { mutableStateOf(false) }
    var remainingTimeMs by remember { mutableLongStateOf(25 * 60 * 1000L) }
    var totalTimeMs by remember { mutableLongStateOf(25 * 60 * 1000L) }
    
    // Check if a Pomodoro session is already active
    LaunchedEffect(Unit) {
        val activePomodoros = sessionManager.activeSessionsFlow.first().filter { it.sessionType == "POMODORO" }
        if (activePomodoros.isNotEmpty()) {
            val session = activePomodoros.first()
            val endTime = session.endTime ?: 0L
            val now = System.currentTimeMillis()
            if (endTime > now) {
                isRunning = true
                totalTimeMs = endTime - session.startTime
                remainingTimeMs = endTime - now
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "Modo Pomodoro",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )
        
        Text(
            "Foco absoluto. Nada além de ligações.",
            fontSize = 14.sp,
            color = TextSecondary,
            modifier = Modifier.padding(top = 8.dp, bottom = 48.dp)
        )

        // Circular Timer
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(280.dp)
        ) {
            Canvas(modifier = Modifier.size(280.dp)) {
                drawCircle(
                    color = DarkSurface,
                    style = Stroke(width = 12.dp.toPx())
                )
                
                val sweepAngle = (remainingTimeMs.toFloat() / totalTimeMs.toFloat()) * 360f
                drawArc(
                    brush = Brush.linearGradient(listOf(AccentCyan, AccentPurple)),
                    startAngle = -90f,
                    sweepAngle = sweepAngle,
                    useCenter = false,
                    style = Stroke(width = 12.dp.toPx(), cap = StrokeCap.Round)
                )
            }
            
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val minutes = TimeUnit.MILLISECONDS.toMinutes(remainingTimeMs)
                val seconds = TimeUnit.MILLISECONDS.toSeconds(remainingTimeMs) % 60
                
                Text(
                    text = String.format("%02d:%02d", minutes, seconds),
                    fontSize = 54.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = if (isRunning) "EM FOCO" else "PRONTO",
                    fontSize = 14.sp,
                    color = if (isRunning) AccentCyan else TextHint,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        Spacer(modifier = Modifier.height(64.dp))

        if (!isRunning) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                TimeOption(label = "15m", isSelected = totalTimeMs == 15 * 60 * 1000L) {
                    totalTimeMs = 15 * 60 * 1000L
                    remainingTimeMs = totalTimeMs
                }
                TimeOption(label = "25m", isSelected = totalTimeMs == 25 * 60 * 1000L) {
                    totalTimeMs = 25 * 60 * 1000L
                    remainingTimeMs = totalTimeMs
                }
                TimeOption(label = "45m", isSelected = totalTimeMs == 45 * 60 * 1000L) {
                    totalTimeMs = 45 * 60 * 1000L
                    remainingTimeMs = totalTimeMs
                }
                TimeOption(label = "60m", isSelected = totalTimeMs == 60 * 60 * 1000L) {
                    totalTimeMs = 60 * 60 * 1000L
                    remainingTimeMs = totalTimeMs
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = {
                    isRunning = true
                    // Start session in manager
                    sessionManager.startPomodoroSession(totalTimeMs)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Default.PlayArrow, null, tint = DarkBg)
                Spacer(Modifier.width(8.dp))
                Text("Iniciar Pomodoro", color = DarkBg, fontWeight = FontWeight.Bold)
            }
        } else {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurface.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Timer, null, tint = DangerRed)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "Bloqueio Irreversível Ativo.\nO tempo deve esgotar para desbloquear.",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Text(
            "Deslize para navegar",
            fontSize = 12.sp,
            color = TextHint.copy(alpha = 0.5f)
        )
    }

    // Timer update effect
    LaunchedEffect(isRunning) {
        if (isRunning) {
            while (remainingTimeMs > 0) {
                kotlinx.coroutines.delay(1000)
                remainingTimeMs -= 1000
            }
            isRunning = false
            Toast.makeText(context, "Pomodoro Concluído!", Toast.LENGTH_LONG).show()
        }
    }
}

@Composable
fun TimeOption(label: String, isSelected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) AccentCyan.copy(alpha = 0.15f) else DarkSurface,
        border = if (isSelected) androidx.compose.foundation.BorderStroke(1.dp, AccentCyan) else null,
        modifier = Modifier.size(60.dp, 40.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                label,
                color = if (isSelected) AccentCyan else TextSecondary,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                fontSize = 14.sp
            )
        }
    }
}
