package com.focusguard.widget

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.R
import com.focusguard.focusmode.FocusModeStore
import com.focusguard.manager.PomodoroManager
import com.focusguard.pomodoro.PomodoroNotificationController
import com.focusguard.pomodoro.PomodoroPlanConfig
import com.focusguard.pomodoro.PomodoroPlanStore
import com.focusguard.security.ProtectionPermissionGate
import com.focusguard.service.FocusModeNotificationService
import com.focusguard.ui.PermissionsActivity
import com.focusguard.ui.compose.screens.PomodoroDurationDial
import com.focusguard.ui.compose.theme.AccentCyan
import com.focusguard.ui.compose.theme.CardBorder
import com.focusguard.ui.compose.theme.DangerRed
import com.focusguard.ui.compose.theme.DarkBg
import com.focusguard.ui.compose.theme.DarkCard
import com.focusguard.ui.compose.theme.FocusGuardTheme
import com.focusguard.ui.compose.theme.TextHint
import com.focusguard.ui.compose.theme.TextPrimary
import com.focusguard.ui.compose.theme.TextSecondary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class PomodoroWidgetDialActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FocusGuardTheme {
                WidgetDialContent(onClose = { finish() })
            }
        }
    }

    @Composable
    private fun WidgetDialContent(onClose: () -> Unit) {
        val context = this
        val scope = rememberCoroutineScope()
        val store = remember { PomodoroPlanStore(context) }
        val manager = remember { PomodoroManager.getInstance(context) }
        val notificationController = remember { PomodoroNotificationController(context) }
        var config by remember { mutableStateOf(store.loadConfig()) }
        var focusText by remember(config.focusMinutes) {
            mutableStateOf(config.focusMinutes.toString())
        }
        var error by remember { mutableStateOf<String?>(null) }

        fun persist(updated: PomodoroPlanConfig) {
            config = store.saveConfig(updated.normalized())
            focusText = config.focusMinutes.toString()
            PomodoroWidgetProvider.requestUpdate(context)
            error = null
        }

        fun start() {
            when {
                store.readRuntime()?.active == true -> {
                    error = context.getString(R.string.fg_pomodoro_already_running)
                    PomodoroWidgetProvider.requestUpdate(context)
                }
                config.strictBlocking && FocusModeStore.isActive(context) -> {
                    error = context.getString(R.string.fg_pomodoro_disable_focus_strict)
                }
                config.strictBlocking && !ProtectionPermissionGate.read(context).isReady -> {
                    startActivity(PermissionsActivity.createPendingProtectionIntent(context))
                }
                config.silenceNotifications && !notificationController.hasPolicyAccess() -> {
                    startActivity(notificationController.policyAccessIntent())
                    error = context.getString(R.string.fg_pomodoro_authorize_dnd_return)
                }
                config.hideNotifications &&
                    !notificationController.hasNotificationListenerAccess(
                        FocusModeNotificationService::class.java
                    ) -> {
                    startActivity(notificationController.notificationListenerIntent())
                    error = context.getString(
                        R.string.fg_pomodoro_authorize_notifications_return
                    )
                }
                else -> scope.launch {
                    try {
                        manager.startPlan(config)
                        PomodoroWidgetProvider.requestUpdate(context)
                        onClose()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        error = context.getString(R.string.fg_pomodoro_start_failed)
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(DarkBg)
                .verticalScroll(rememberScrollState())
                .padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.fg_pomodoro_dial_title),
                    modifier = Modifier.weight(1f),
                    color = TextPrimary,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onClose) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.fg_close),
                        tint = TextPrimary
                    )
                }
            }

            Text(
                stringResource(R.string.fg_pomodoro_dial_desc),
                color = TextSecondary,
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )

            PomodoroDurationDial(
                minutes = config.focusMinutes.coerceIn(1, 180),
                maxMinutes = 180,
                activeProgress = null,
                onMinutesChange = { persist(config.copy(focusMinutes = it)) },
                modifier = Modifier.size(260.dp)
            )

            OutlinedTextField(
                value = focusText,
                onValueChange = { raw ->
                    val filtered = raw.filter(Char::isDigit).take(4)
                    focusText = filtered
                    filtered.toIntOrNull()?.let { minutes ->
                        persist(config.copy(focusMinutes = minutes.coerceIn(1, 180)))
                    }
                },
                label = { Text(stringResource(R.string.fg_pomodoro_focus_time)) },
                trailingIcon = { Text("min", color = TextHint) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkCard),
                border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text(
                        stringResource(R.string.fg_pomodoro_intervals_defined),
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        stringResource(
                            R.string.fg_pomodoro_focus_break_summary,
                            formatMinutes(config.focusMinutes),
                            formatMinutes(config.shortBreakMinutes)
                        ),
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                    Text(
                        stringResource(
                            R.string.fg_pomodoro_long_break_summary,
                            formatMinutes(config.longBreakMinutes),
                            config.longBreakEvery
                        ),
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                    val target = if (config.targetSessions == 0) {
                        stringResource(R.string.fg_pomodoro_until_i_stop)
                    } else {
                        config.targetSessions.toString()
                    }
                    Text(
                        stringResource(R.string.fg_pomodoro_sessions_label, target),
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                }
            }

            error?.let {
                Text(it, color = DangerRed, fontSize = 12.sp, textAlign = TextAlign.Center)
            }

            Button(
                onClick = ::start,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                shape = RoundedCornerShape(15.dp)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = DarkBg)
                Spacer(Modifier.width(7.dp))
                Text(
                    stringResource(R.string.fg_pomodoro_start_button),
                    color = DarkBg,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }

    private fun formatMinutes(minutes: Int): String {
        val hours = minutes / 60
        val rest = minutes % 60
        return when {
            hours == 0 -> "${minutes}min"
            rest == 0 -> "${hours}h"
            else -> "${hours}h ${rest}min"
        }
    }
}
