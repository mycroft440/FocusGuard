package com.focusguard.ui

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.admin.DeviceOwnerManager
import com.focusguard.manager.PomodoroManager
import com.focusguard.manager.StrictPomodoroLock
import com.focusguard.service.PomodoroForegroundService
import com.focusguard.ui.compose.theme.AccentCyan
import com.focusguard.ui.compose.theme.CardBorder
import com.focusguard.ui.compose.theme.DarkBg
import com.focusguard.ui.compose.theme.DarkCard
import com.focusguard.ui.compose.theme.FocusGuardTheme
import com.focusguard.ui.compose.theme.TextHint
import com.focusguard.ui.compose.theme.TextPrimary
import com.focusguard.ui.compose.theme.TextSecondary
import com.focusguard.utils.FocusGuardLogger
import kotlinx.coroutines.delay

/**
 * Full-screen lock activity for strict Pomodoro mode.
 * Hardened against all bypass attempts:
 * - Back button disabled
 * - Home button intercepted via Lock Task Mode
 * - Recent apps blocked
 * - Auto-relaunches on pause/stop via AlarmManager
 * - Survives process kills via watchdog service
 */
class PomodoroLockActivity : ComponentActivity() {
    private lateinit var deviceOwnerManager: DeviceOwnerManager
    private lateinit var pomodoroManager: PomodoroManager

    companion object {
        private const val RELAUNCH_ALARM_CODE = 4001
        private const val RELAUNCH_DELAY_MS = 800L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        deviceOwnerManager = DeviceOwnerManager.getInstance(applicationContext)
        pomodoroManager = PomodoroManager.getInstance(applicationContext)

        // Fazer a activity aparecer sobre tudo - inclusive tela de bloqueio
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        enableImmersiveMode()

        // Bloquear botão voltar
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                enforceStrictLock()
            }
        })

        setContent {
            FocusGuardTheme {
                StrictPomodoroLockScreen(
                    isDeviceOwner = deviceOwnerManager.isDeviceOwnerActive(),
                    onEmergencyCall = { openEmergencyDialer() },
                    onExpired = { finishStrictLock() },
                    onEnforce = { enforceStrictLock() }
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        cancelRelaunchAlarm()
        enforceStrictLock()
    }

    override fun onResume() {
        super.onResume()
        cancelRelaunchAlarm()
        enforceStrictLock()
    }

    override fun onPause() {
        super.onPause()
        // Se ainda ativo, agendar re-lançamento imediato como failsafe
        if (StrictPomodoroLock.isActive(applicationContext)) {
            scheduleRelaunch()
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        enforceStrictLock()
    }

    override fun onStop() {
        super.onStop()
        if (StrictPomodoroLock.isActive(applicationContext)) {
            // Agendar re-lançamento como failsafe
            scheduleRelaunch()
            // Também lançar diretamente
            try {
                startActivity(
                    Intent(applicationContext, PomodoroLockActivity::class.java)
                        .addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP
                        )
                )
            } catch (_: Exception) {}
        }
    }

        override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (StrictPomodoroLock.isActive(applicationContext)) {
            enableImmersiveMode()
            if (!hasFocus) {
                enforceStrictLock()
            }
        }
    }

    // Bloquear teclas de hardware (volume, etc. que poderiam acionar assistente)
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (StrictPomodoroLock.isActive(applicationContext)) {
            return when (keyCode) {
                KeyEvent.KEYCODE_BACK,
                KeyEvent.KEYCODE_HOME,
                KeyEvent.KEYCODE_APP_SWITCH,
                KeyEvent.KEYCODE_MENU -> {
                    enforceStrictLock()
                    true
                }
                else -> super.onKeyDown(keyCode, event)
            }
        }
        return super.onKeyDown(keyCode, event)
    }


    // Impedir finishAffinity durante bloqueio
    override fun finishAffinity() {
        if (StrictPomodoroLock.isActive(applicationContext)) {
            FocusGuardLogger.log("PomodoroLock", "finishAffinity bloqueado durante Pomodoro rigoroso")
            return
        }
        super.finishAffinity()
    }

    // Impedir finish durante bloqueio (exceto quando chamado internamente)
    private var allowFinish = false

    override fun finish() {
        if (!allowFinish && StrictPomodoroLock.isActive(applicationContext)) {
            FocusGuardLogger.log("PomodoroLock", "finish() bloqueado durante Pomodoro rigoroso")
            return
        }
        super.finish()
    }

        private fun enableImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun enforceStrictLock() {
        if (!StrictPomodoroLock.isActive(applicationContext)) {
            finishStrictLock()
            return
        }
        deviceOwnerManager.prepareStrictPomodoroLockTaskPackages()
        runCatching { startLockTask() }
    }

    private fun finishStrictLock() {
        runCatching { stopLockTask() }
        deviceOwnerManager.clearStrictPomodoroLockTaskPackages()
        cancelRelaunchAlarm()
        if (!pomodoroManager.isPomodoroActive()) {
            allowFinish = true
            finish()
        }
    }

    private fun openEmergencyDialer() {
        runCatching { startActivity(Intent(Intent.ACTION_DIAL)) }
    }

    /**
     * Agenda um alarme para relançar esta activity em caso de remoção forçada.
     */
    private fun scheduleRelaunch() {
        try {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(applicationContext, PomodoroLockActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
            }
            val pendingIntent = PendingIntent.getActivity(
                applicationContext,
                RELAUNCH_ALARM_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val triggerAt = System.currentTimeMillis() + RELAUNCH_DELAY_MS
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            }
        } catch (e: SecurityException) {
            FocusGuardLogger.logError("PomodoroLock", "Permissao de relancamento negada (Device Admin/Owner?)", e)
        } catch (e: Exception) {
            FocusGuardLogger.logError("PomodoroLock", "Falha critica ao agendar relancamento", e)
        }
    }

    private fun cancelRelaunchAlarm() {
        try {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(applicationContext, PomodoroLockActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(
                applicationContext,
                RELAUNCH_ALARM_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(pendingIntent)
        } catch (_: Exception) {}
    }
}

@Composable
private fun StrictPomodoroLockScreen(
    isDeviceOwner: Boolean,
    onEmergencyCall: () -> Unit,
    onExpired: () -> Unit,
    onEnforce: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var remainingMillis by remember { mutableLongStateOf(StrictPomodoroLock.remainingMillis(context)) }

    LaunchedEffect(Unit) {
        while (true) {
            remainingMillis = StrictPomodoroLock.remainingMillis(context)
            if (remainingMillis <= 0L) {
                onExpired()
                break
            }
            onEnforce()
            delay(1000)
        }
    }

    val totalSeconds = remainingMillis / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L

    Box(
        modifier = Modifier.fillMaxSize().background(DarkBg).padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Surface(
                color = DarkCard,
                shape = RoundedCornerShape(28.dp),
                border = BorderStroke(1.dp, CardBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Timer, contentDescription = null, tint = AccentCyan)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Bloqueio rigoroso ativo", color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(String.format("%02d:%02d", minutes, seconds), color = AccentCyan, fontSize = 52.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = if (isDeviceOwner) "Modo kiosk real ativo. Você só poderá sair quando o tempo acabar." else "Atenção: sem Device Owner, o Android ainda pode permitir desafixar a tela.",
                        color = if (isDeviceOwner) TextSecondary else TextHint,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
            Spacer(modifier = Modifier.height(28.dp))
            Button(
                onClick = onEmergencyCall,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                shape = RoundedCornerShape(18.dp)
            ) {
                Icon(Icons.Default.Phone, contentDescription = null, tint = DarkBg)
                Spacer(modifier = Modifier.padding(4.dp))
                Text("Abrir telefone para emergência", color = DarkBg, fontWeight = FontWeight.Bold)
            }
        }
    }
}