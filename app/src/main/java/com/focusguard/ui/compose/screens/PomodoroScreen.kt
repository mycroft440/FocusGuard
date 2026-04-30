package com.focusguard.ui.compose.screens

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Timer
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.app.NotificationManager
import android.os.Build
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
    var countdown by remember { mutableIntStateOf(4) }

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
            title = { Text("Ativar Bloqueio Total?", color = AccentCyan, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Ao ativar esta opçâo, o FocusGuard irá bloquear completamente o uso do seu celular durante a sessâo de Pomodoro. Você só poderá acessar o discador de telefone para emergências.",
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
                            // Fallback caso nâo seja FragmentActivity (nâo deve ocorrer no app)
                            isBlockingEnabled = true
                            showBlockingWarning = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                    enabled = countdown == 0
                ) {
                    Text(if (countdown > 0) "Confirmar (${countdown}s)" else "Confirmar", color = DarkBg)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBlockingWarning = false }) {
                    Text("Cancelar", color = TextSecondary)
                }
            }
        )
    }
    
    val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
    
    val isActive = currentSession?.isActive == true
    
    // MODO IMERSIVO (TELA INTEIRA)
    LaunchedEffect(isActive) {
        FocusGuardLogger.log("PomodoroScreen", "Estado Imersivo alterado: $isActive")
        activity?.window?.let { window ->
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            if (isActive) {
                FocusGuardLogger.log("PomodoroScreen", "Escondendo barras de sistema.")
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                FocusGuardLogger.log("PomodoroScreen", "Mostrando barras de sistema.")
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // BLOQUEIO DO BOTÃƒO VOLTAR
    BackHandler(enabled = isActive && currentSession?.isBlockingEnabled == true) {
        FocusGuardLogger.log("PomodoroScreen", "Tentativa de voltar negada: Pomodoro ativo com bloqueio.")
    }

    // KIOSK MODE (Lock Task) - Se Device Owner
    LaunchedEffect(isActive) {
        if (isActive && currentSession?.isBlockingEnabled == true) {
            try {
                FocusGuardLogger.log("PomodoroScreen", "Ativando startLockTask.")
                activity?.startLockTask()
            } catch (e: Exception) {
                FocusGuardLogger.logError("PomodoroScreen", "Falha ao ativar startLockTask", e)
            }
        } else {
            try {
                FocusGuardLogger.log("PomodoroScreen", "Desativando stopLockTask.")
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
                text = if (isActive) {
                    if (currentSession?.isBlockingEnabled == true) "Bloqueio Imersivo Ativo" else "Timer em ExecuÃ§Ã£o"
                } else "Selecione a duraÃ§Ã£o",
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
                    val onStart: (Int) -> Unit = { mins ->
                        if (isBlockingEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && notificationManager?.isNotificationPolicyAccessGranted == false) {
                            val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                            context.startActivity(intent)
                        } else {
                            scope.launch { pomodoroManager.startSession(mins, isBlockingEnabled = isBlockingEnabled) }
                        }
                    }
                    PomodoroOption(label = "15m", onClick = { onStart(15) })
                    PomodoroOption(label = "25m", onClick = { onStart(25) })
                    PomodoroOption(label = "45m", onClick = { onStart(45) })
                }
                
                Spacer(modifier = Modifier.height(32.dp))
                
                // Switch de Bloqueio
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(DarkCard)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text("Ativar Bloqueio Total", color = TextPrimary, fontSize = 14.sp)
                    Spacer(modifier = Modifier.width(16.dp))
                    Switch(
                        checked = isBlockingEnabled,
                        onCheckedChange = { checked ->
                            if (checked) {
                                showBlockingWarning = true
                            } else {
                                isBlockingEnabled = false
                            }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = AccentCyan,
                            checkedTrackColor = AccentCyan.copy(alpha = 0.5f)
                        )
                    )
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
                        if (currentSession?.isBlockingEnabled == true) 
                            "O FocusGuard travou esta tela.\nSaÃda nÃ£o permitida atÃ© o fim do timer."
                        else 
                            "Timer em execuÃ§Ã£o.\nVocÃª pode sair, mas mantenha o foco!",
                        color = TextHint,
                        textAlign = TextAlign.Center,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(16.dp)
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // BotÃ£o de Telefone e Sair (se nÃ£o bloqueado)
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
                            Icon(Icons.Default.Phone, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Telefone")
                        }
                    } else {
                        Button(
                            onClick = { scope.launch { pomodoroManager.stopSession() } },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.7f)),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Text("Cancelar Timer")
                        }
                    }
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
