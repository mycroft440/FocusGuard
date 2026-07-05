package com.focusguard.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.focusguard.ui.compose.theme.AccentCyan
import com.focusguard.ui.compose.theme.DarkBg
import com.focusguard.ui.compose.theme.DangerRed
import com.focusguard.ui.compose.theme.FocusGuardTheme
import com.focusguard.ui.compose.theme.TextPrimary
import com.focusguard.ui.compose.theme.TextSecondary
import com.focusguard.utils.AccessibilityStateMonitor
import com.focusguard.utils.FocusGuardLogger
import kotlinx.coroutines.delay

/**
 * Tela cheia de bloqueio exibida quando o BlockingAccessibilityService
 * é desativado por fora da UI interceptada (ex: via adb, App Info →
 * Force Stop, ou Safe Mode sem Device Owner).
 *
 * Não pode ser fechada pelo usuário (back desabilitado). Só fecha
 * automaticamente quando o serviço volta a ficar ativo — detectado
 * via poll a cada 1 segundo.
 *
 * Funcionalmente similar a PomodoroLockActivity mas para o caso de
 * bypass de accessibility em vez de Pomodoro ativo.
 */
class AccessibilityDisabledActivity : ComponentActivity() {

    companion object {
        private const val TAG = "A11yDisabledActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Tela cheia, sobre tudo, mostra quando bloqueado
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        enableImmersiveMode()

        // Bloquear botão voltar
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Não faz nada — back bloqueado
            }
        })

        setContent {
            FocusGuardTheme {
                AccessibilityDisabledScreen(
                    onReactivate = { openAccessibilitySettings() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        FocusGuardLogger.log(TAG, "onResume — checando se serviço foi reativado")
    }

    private fun enableImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun openAccessibilitySettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }.onFailure {
            FocusGuardLogger.logError(TAG, "Falha ao abrir Accessibility Settings", it)
        }
    }
}

@Composable
private fun AccessibilityDisabledScreen(onReactivate: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var serviceEnabled by remember { mutableStateOf(AccessibilityStateMonitor.isAccessibilityServiceEnabled(context)) }

    // Poll a cada 1 segundo — se serviço voltar a ficar ativo, fecha a tela
    LaunchedEffect(Unit) {
        while (true) {
            serviceEnabled = AccessibilityStateMonitor.isAccessibilityServiceEnabled(context)
            if (serviceEnabled) {
                FocusGuardLogger.log("A11yDisabledActivity", "Serviço reativado — fechando tela de bloqueio")
                delay(500) // Pequena delay para garantir estabilidade
                (context as? androidx.activity.ComponentActivity)?.finish()
                break
            }
            delay(1000)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Ícone de alerta
            Surface(
                color = DangerRed.copy(alpha = 0.15f),
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.size(96.dp),
                border = androidx.compose.foundation.BorderStroke(2.dp, DangerRed)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = null,
                        tint = DangerRed,
                        modifier = Modifier.size(48.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Proteção Desativada",
                color = DangerRed,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "O serviço de acessibilidade do FocusGuard foi desativado. " +
                    "Isto pode acontecer se você o desligou manualmente, forçou a parada " +
                    "do aplicativo ou usou ferramentas externas (adb).\n\n" +
                    "Para usar seu dispositivo novamente, reative o serviço.",
                color = TextSecondary,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                lineHeight = 20.sp
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Status indicator
            if (serviceEnabled) {
                CircularProgressIndicator(color = AccentCyan, modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.height(8.dp))
                Text("Serviço reativado! Fechando...", color = AccentCyan, fontSize = 13.sp)
            } else {
                Text(
                    "● Aguardando reativação...",
                    color = DangerRed.copy(alpha = 0.7f),
                    fontSize = 12.sp
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            Button(
                onClick = onReactivate,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(
                    painter = androidx.compose.ui.res.painterResource(com.focusguard.R.drawable.ic_shield),
                    contentDescription = null,
                    tint = DarkBg,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Abrir configurações", color = DarkBg, fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Toque em FocusGuard na lista e ative o serviço.",
                color = TextSecondary.copy(alpha = 0.6f),
                fontSize = 12.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}
