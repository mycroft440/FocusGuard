package com.focusguard.ui

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.doOnPreDraw
import androidx.lifecycle.lifecycleScope
import com.focusguard.R
import com.focusguard.admin.DeviceOwnerManager
import com.focusguard.focusmode.FocusModePolicy
import com.focusguard.focusmode.FocusModeStore
import com.focusguard.manager.PomodoroManager
import com.focusguard.manager.StrictPomodoroLock
import com.focusguard.security.CurtainDestinationReadyCoordinator
import com.focusguard.service.BlockingAccessibilityService
import com.focusguard.ui.compose.theme.AccentCyan
import com.focusguard.ui.compose.theme.CardBorder
import com.focusguard.ui.compose.theme.DarkBg
import com.focusguard.ui.compose.theme.DarkCard
import com.focusguard.ui.compose.theme.FocusGuardTheme
import com.focusguard.ui.compose.theme.TextHint
import com.focusguard.ui.compose.theme.TextPrimary
import com.focusguard.ui.compose.theme.TextSecondary
import com.focusguard.utils.FocusGuardLogger
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@AndroidEntryPoint
class PomodoroLockActivity : ComponentActivity() {

    private lateinit var deviceOwnerManager: DeviceOwnerManager
    private lateinit var pomodoroManager: PomodoroManager
    private var allowFinish = false
    private var expirationHandled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        deviceOwnerManager = DeviceOwnerManager.getInstance(applicationContext)
        pomodoroManager = PomodoroManager.getInstance(applicationContext)

        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        enableImmersiveMode()

        // Back, including Android 16 predictive back, is handled through the
        // AndroidX dispatcher. KEYCODE_BACK is intentionally not intercepted
        // in onKeyDown because API 36 no longer dispatches it for back gestures.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                enforceStrictLock()
            }
        })

        setContent {
            FocusGuardTheme {
                StrictPomodoroLockScreen(
                    isDeviceOwner = deviceOwnerManager.isDeviceOwnerActive(),
                    onEmergencyCall = ::openEmergencyDialer,
                    onExpired = ::handleExpiration,
                    onEnforce = ::enforceStrictLock
                )
            }
        }
        acknowledgeWebsiteTransitionWhenDrawn(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acknowledgeWebsiteTransitionWhenDrawn(intent)
    }

    private fun acknowledgeWebsiteTransitionWhenDrawn(sourceIntent: Intent) {
        val generation = sourceIntent.getLongExtra(
            BlockingAccessibilityService.EXTRA_CURTAIN_GENERATION,
            0L
        )
        if (generation <= 0L) return
        window.decorView.doOnPreDraw {
            CurtainDestinationReadyCoordinator.notifyReady(generation)
        }
        window.decorView.invalidate()
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
        if (StrictPomodoroLock.isActive(applicationContext)) scheduleRelaunch()
        super.onPause()
    }

    override fun onStop() {
        if (StrictPomodoroLock.isActive(applicationContext)) scheduleRelaunch()
        super.onStop()
    }

    override fun onUserLeaveHint() {
        enforceStrictLock()
        super.onUserLeaveHint()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (StrictPomodoroLock.isActive(applicationContext)) {
            enableImmersiveMode()
            if (!hasFocus) enforceStrictLock()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (StrictPomodoroLock.isActive(applicationContext)) {
            when (keyCode) {
                KeyEvent.KEYCODE_HOME,
                KeyEvent.KEYCODE_APP_SWITCH,
                KeyEvent.KEYCODE_MENU -> {
                    enforceStrictLock()
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun finishAffinity() {
        if (!allowFinish && StrictPomodoroLock.isActive(applicationContext)) return
        super.finishAffinity()
    }

    override fun finish() {
        if (!allowFinish && StrictPomodoroLock.isActive(applicationContext)) return
        super.finish()
    }

    private fun handleExpiration() {
        if (expirationHandled) return
        expirationHandled = true
        lifecycleScope.launch {
            // O gerente é a única autoridade sobre a transição do ciclo. O
            // ticker dele detecta o fim deste intervalo e decide entre pausa
            // curta, pausa longa ou término do plano. Esta Activity apenas sai
            // do kiosk quando o prazo rigoroso expirou; chamar stopSession()
            // aqui encerraria indevidamente todas as próximas sessões.
            delay(150L)
            finishStrictLock()
        }
    }

    private fun enforceStrictLock() {
        if (!StrictPomodoroLock.isActive(applicationContext)) {
            finishStrictLock()
            return
        }
        deviceOwnerManager.prepareStrictPomodoroLockTaskPackages()
        runCatching { startLockTask() }
            .onFailure {
                FocusGuardLogger.logError("PomodoroLock", "Falha ao iniciar lock task", it)
            }
    }

    private fun finishStrictLock() {
        if (FocusModePolicy.canPomodoroReleaseKiosk(
                FocusModeStore.isActive(applicationContext)
            )
        ) {
            runCatching { stopLockTask() }
            deviceOwnerManager.clearStrictPomodoroLockTaskPackages()
        }
        cancelRelaunchAlarm()
        allowFinish = true
        finish()
    }

    private fun openEmergencyDialer() {
        runCatching {
            startActivity(Intent(Intent.ACTION_DIAL))
        }.onFailure {
            FocusGuardLogger.logError("PomodoroLock", "Falha ao abrir telefone", it)
        }
    }

    private fun enableImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun scheduleRelaunch() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pendingIntent = relaunchPendingIntent()
        val triggerAt = System.currentTimeMillis() + RELAUNCH_DELAY_MS

        try {
            val exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                alarmManager.canScheduleExactAlarms()
            when {
                exactAllowed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ->
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerAt,
                        pendingIntent
                    )
                exactAllowed ->
                    alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ->
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerAt,
                        pendingIntent
                    )
                else -> alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            }
        } catch (error: SecurityException) {
            FocusGuardLogger.logError(
                "PomodoroLock",
                "Alarme exato negado; usando alarme comum",
                error
            )
            runCatching {
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            }
        }
    }

    private fun cancelRelaunchAlarm() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        runCatching { alarmManager.cancel(relaunchPendingIntent()) }
    }

    private fun relaunchPendingIntent(): PendingIntent {
        val intent = Intent(applicationContext, PomodoroLockActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
        }
        return PendingIntent.getActivity(
            applicationContext,
            RELAUNCH_ALARM_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        private const val RELAUNCH_ALARM_CODE = 4001
        private const val RELAUNCH_DELAY_MS = 800L
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
    var remainingMillis by remember {
        mutableLongStateOf(StrictPomodoroLock.remainingMillis(context))
    }

    LaunchedEffect(Unit) {
        while (remainingMillis > 0L) {
            onEnforce()
            delay(1_000L)
            remainingMillis = StrictPomodoroLock.remainingMillis(context)
        }
        onExpired()
    }

    val totalSeconds = remainingMillis.coerceAtLeast(0L) / 1_000L
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
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Default.Timer, contentDescription = null, tint = AccentCyan)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Bloqueio rigoroso ativo",
                        color = TextPrimary,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        String.format(Locale.US, "%02d:%02d", minutes, seconds),
                        color = AccentCyan,
                        fontSize = 52.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        if (isDeviceOwner) {
                            stringResource(R.string.pomodoro_lock_kiosk_active)
                        } else {
                            stringResource(R.string.pomodoro_lock_no_device_owner)
                        },
                        color = if (isDeviceOwner) TextSecondary else TextHint,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
            Spacer(Modifier.height(28.dp))
            Button(
                onClick = onEmergencyCall,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                shape = RoundedCornerShape(18.dp)
            ) {
                Icon(Icons.Default.Phone, contentDescription = null, tint = DarkBg)
                Spacer(Modifier.padding(4.dp))
                Text(
                    stringResource(R.string.abrir_telefone_para_emergencia),
                    color = DarkBg,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
