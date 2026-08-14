package com.focusguard.ui.compose.screens

import android.app.NotificationManager
import android.content.Intent
import android.provider.Settings
import android.widget.NumberPicker
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.focusguard.R
import com.focusguard.pomodoro.PomodoroAlarmController
import com.focusguard.pomodoro.PomodoroNotificationController
import com.focusguard.pomodoro.PomodoroPlanConfig
import com.focusguard.pomodoro.PomodoroPlanStore
import com.focusguard.pomodoro.PomodoroProfile
import com.focusguard.ui.compose.theme.AccentCyan
import com.focusguard.ui.compose.theme.CardBorder
import com.focusguard.ui.compose.theme.DangerRed
import com.focusguard.ui.compose.theme.DarkBg
import com.focusguard.ui.compose.theme.DarkCard
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

@androidx.compose.material3.ExperimentalMaterial3Api
@Composable
fun PomodoroConfigDialog(
    onDismiss: () -> Unit,
    onConfigChanged: (PomodoroPlanConfig) -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val store = remember(context) { PomodoroPlanStore(context) }
    val notificationController = remember(context) { PomodoroNotificationController(context) }

    var config by remember { mutableStateOf(store.loadConfig()) }
    var profiles by remember { mutableStateOf(store.allProfiles()) }
    var profileName by remember { mutableStateOf("") }
    var notificationPolicyAccess by remember {
        mutableStateOf(notificationController.hasPolicyAccess())
    }
    var notificationListenerAccess by remember {
        mutableStateOf(
            NotificationManagerCompat.getEnabledListenerPackages(context)
                .contains(context.packageName)
        )
    }

    fun refreshNotificationAccess() {
        notificationPolicyAccess = notificationController.hasPolicyAccess()
        notificationListenerAccess = NotificationManagerCompat
            .getEnabledListenerPackages(context)
            .contains(context.packageName)
    }

    fun persist(updated: PomodoroPlanConfig) {
        val saved = store.saveConfig(updated)
        config = saved
        onConfigChanged(saved)
        PomodoroWidgetProvider.updateAll(context)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshNotificationAccess()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(DarkBg)
                .statusBarsPadding()
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                TopAppBar(
                    title = {
                        Text(
                            stringResource(R.string.pomodoro_config_title),
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.common_back),
                                tint = TextPrimary
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBg)
                )

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ConfigSection(title = stringResource(R.string.pomodoro_config_intervals)) {
                        DurationClockEditor(
                            title = stringResource(R.string.pomodoro_config_focus_time),
                            valueMinutes = config.focusMinutes,
                            maxMinutes = 120,
                            longFormat = false,
                            onValueChange = { persist(config.copy(focusMinutes = it)) }
                        )
                        DurationClockEditor(
                            title = stringResource(R.string.pomodoro_config_short_break),
                            valueMinutes = config.shortBreakMinutes,
                            maxMinutes = 120,
                            longFormat = false,
                            onValueChange = { persist(config.copy(shortBreakMinutes = it)) }
                        )
                        DurationClockEditor(
                            title = stringResource(R.string.pomodoro_config_long_break),
                            valueMinutes = config.longBreakMinutes,
                            maxMinutes = 12 * 60,
                            longFormat = true,
                            onValueChange = { persist(config.copy(longBreakMinutes = it)) }
                        )

                        Text(
                            stringResource(R.string.pomodoro_config_long_break_every),
                            color = TextSecondary,
                            fontSize = 13.sp
                        )
                        IntegerWheelPicker(
                            value = config.longBreakEvery,
                            minValue = 1,
                            maxValue = 20,
                            onValueChange = { persist(config.copy(longBreakEvery = it)) }
                        )
                    }

                    ConfigSection(title = stringResource(R.string.pomodoro_config_sessions)) {
                        ChoiceRow(
                            selected = config.targetSessions == 0,
                            label = stringResource(R.string.pomodoro_sessions_until_stop),
                            onClick = { persist(config.copy(targetSessions = 0)) }
                        )
                        ChoiceRow(
                            selected = config.targetSessions != 0,
                            label = stringResource(R.string.pomodoro_sessions_counted),
                            onClick = {
                                if (config.targetSessions == 0) {
                                    persist(config.copy(targetSessions = 4))
                                }
                            }
                        )
                        if (config.targetSessions != 0) {
                            IntegerWheelPicker(
                                value = config.targetSessions,
                                minValue = 1,
                                maxValue = 100,
                                onValueChange = { persist(config.copy(targetSessions = it)) }
                            )
                            Text(
                                stringResource(
                                    R.string.pomodoro_sessions_selected,
                                    config.targetSessions
                                ),
                                color = AccentCyan,
                                fontSize = 12.sp,
                                modifier = Modifier.align(Alignment.CenterHorizontally)
                            )
                        }
                    }

                    ConfigSection(title = stringResource(R.string.pomodoro_config_alarm)) {
                        ToggleRow(
                            title = stringResource(R.string.pomodoro_alarm_sound),
                            checked = config.soundEnabled,
                            onCheckedChange = { persist(config.copy(soundEnabled = it)) }
                        )
                        ToggleRow(
                            title = stringResource(R.string.pomodoro_alarm_vibration),
                            checked = config.vibrationEnabled,
                            onCheckedChange = { persist(config.copy(vibrationEnabled = it)) }
                        )

                        Text(
                            stringResource(R.string.pomodoro_alarm_duration),
                            color = TextSecondary,
                            fontSize = 13.sp
                        )
                        IntegerWheelPicker(
                            value = config.alarmDurationSeconds,
                            minValue = 1,
                            maxValue = 60,
                            onValueChange = { persist(config.copy(alarmDurationSeconds = it)) }
                        )
                        Text(
                            stringResource(
                                R.string.pomodoro_alarm_duration_value,
                                config.alarmDurationSeconds
                            ),
                            color = AccentCyan,
                            fontSize = 12.sp,
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        )

                        Text(
                            stringResource(R.string.pomodoro_alarm_sound_type),
                            color = TextSecondary,
                            fontSize = 13.sp
                        )
                        val soundNames = stringArrayResource(R.array.pomodoro_alarm_sound_names)
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (row in 0 until 5) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    repeat(2) { column ->
                                        val index = row * 2 + column
                                        val selected = config.soundIndex == index
                                        OutlinedButton(
                                            onClick = {
                                                persist(config.copy(soundIndex = index))
                                                scope.launch {
                                                    PomodoroAlarmController.preview(context, index)
                                                }
                                            },
                                            modifier = Modifier.weight(1f),
                                            border = BorderStroke(
                                                1.dp,
                                                if (selected) AccentCyan else CardBorder
                                            )
                                        ) {
                                            Icon(
                                                Icons.Default.VolumeUp,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(Modifier.width(5.dp))
                                            Text(
                                                soundNames.getOrElse(index) { "${index + 1}" },
                                                fontSize = 11.sp,
                                                maxLines = 1
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    ConfigSection(title = stringResource(R.string.pomodoro_config_notifications)) {
                        ToggleRow(
                            title = stringResource(R.string.pomodoro_hide_notifications),
                            subtitle = stringResource(R.string.pomodoro_hide_notifications_desc),
                            checked = config.hideNotifications,
                            onCheckedChange = { persist(config.copy(hideNotifications = it)) }
                        )
                        if (config.hideNotifications && !notificationListenerAccess) {
                            AccessRequiredRow(
                                text = stringResource(R.string.pomodoro_notification_listener_required),
                                onClick = {
                                    context.startActivity(
                                        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                    )
                                }
                            )
                        }

                        ToggleRow(
                            title = stringResource(R.string.pomodoro_silence_notifications),
                            subtitle = stringResource(R.string.pomodoro_silence_notifications_desc),
                            checked = config.silenceNotifications,
                            onCheckedChange = { persist(config.copy(silenceNotifications = it)) }
                        )
                        if (config.silenceNotifications && !notificationPolicyAccess) {
                            AccessRequiredRow(
                                text = stringResource(R.string.pomodoro_dnd_access_required),
                                onClick = {
                                    context.startActivity(
                                        Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                                    )
                                }
                            )
                        }

                        Text(
                            stringResource(R.string.pomodoro_foreground_notification_note),
                            color = TextHint,
                            fontSize = 11.sp
                        )
                    }

                    ConfigSection(title = stringResource(R.string.pomodoro_profiles_title)) {
                        OutlinedTextField(
                            value = profileName,
                            onValueChange = {
                                profileName = it.take(PomodoroPlanStore.MAX_PROFILE_NAME_LENGTH)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text(stringResource(R.string.pomodoro_profile_name)) }
                        )
                        Button(
                            onClick = {
                                val created = store.saveProfile(profileName, config)
                                if (created != null) {
                                    profileName = ""
                                    profiles = store.allProfiles()
                                }
                            },
                            enabled = profileName.isNotBlank() &&
                                profiles.count { !it.builtIn } < PomodoroPlanStore.MAX_USER_PROFILES,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentCyan)
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null, tint = DarkBg)
                            Spacer(Modifier.width(7.dp))
                            Text(
                                stringResource(R.string.pomodoro_profile_save),
                                color = DarkBg,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        profiles.forEach { profile ->
                            ProfileConfigRow(
                                profile = profile,
                                onApply = { persist(profile.config) },
                                onDelete = if (profile.builtIn) null else {
                                    {
                                        store.deleteProfile(profile.id)
                                        profiles = store.allProfiles()
                                    }
                                }
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
private fun ConfigSection(
    title: String,
    content: @Composable Column.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        border = BorderStroke(1.dp, CardBorder)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                title,
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
            content()
        }
    }
}

@Composable
private fun ChoiceRow(
    selected: Boolean,
    label: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        TextButton(onClick = onClick, modifier = Modifier.weight(1f)) {
            Text(label, color = TextPrimary, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            subtitle?.let {
                Text(it, color = TextHint, fontSize = 11.sp)
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun AccessRequiredRow(text: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DangerRed.copy(alpha = 0.08f)),
        border = BorderStroke(1.dp, DangerRed.copy(alpha = 0.55f))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.NotificationsOff,
                contentDescription = null,
                tint = DangerRed,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(text, color = DangerRed, fontSize = 11.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = onClick) {
                Text(stringResource(R.string.pomodoro_grant_access))
            }
        }
    }
}

@Composable
private fun IntegerWheelPicker(
    value: Int,
    minValue: Int,
    maxValue: Int,
    onValueChange: (Int) -> Unit
) {
    AndroidView(
        factory = { ctx ->
            NumberPicker(ctx).apply {
                this.minValue = minValue
                this.maxValue = maxValue
                wrapSelectorWheel = false
                descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(116.dp),
        update = { picker ->
            picker.minValue = minValue
            picker.maxValue = maxValue
            val safeValue = value.coerceIn(minValue, maxValue)
            if (picker.value != safeValue) picker.value = safeValue
            picker.setOnValueChangedListener { _, _, newValue -> onValueChange(newValue) }
        }
    )
}

@Composable
private fun DurationClockEditor(
    title: String,
    valueMinutes: Int,
    maxMinutes: Int,
    longFormat: Boolean,
    onValueChange: (Int) -> Unit
) {
    var text by remember {
        mutableStateOf(formatDurationInput(valueMinutes, longFormat))
    }

    LaunchedEffect(valueMinutes, longFormat) {
        val parsed = parseDurationInput(text, longFormat, maxMinutes)
        if (parsed != valueMinutes) {
            text = formatDurationInput(valueMinutes, longFormat)
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        MiniDurationDial(
            valueMinutes = valueMinutes,
            maxMinutes = maxMinutes,
            onValueChange = { minutes ->
                text = formatDurationInput(minutes, longFormat)
                onValueChange(minutes)
            },
            modifier = Modifier.size(92.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Spacer(Modifier.height(5.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { entered ->
                    val filtered = if (longFormat) {
                        entered.filter { it.isDigit() || it == ':' }.take(5)
                    } else {
                        entered.filter(Char::isDigit).take(4)
                    }
                    text = filtered
                    parseDurationInput(filtered, longFormat, maxMinutes)?.let(onValueChange)
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (longFormat) KeyboardType.Text else KeyboardType.Number
                ),
                suffix = {
                    Text(
                        if (longFormat) stringResource(R.string.pomodoro_hours_suffix)
                        else stringResource(R.string.pomodoro_minutes_suffix)
                    )
                }
            )
            Text(
                if (longFormat) stringResource(R.string.pomodoro_long_break_input_hint)
                else stringResource(R.string.pomodoro_minutes_input_hint),
                color = TextHint,
                fontSize = 10.sp
            )
        }
    }
}

@Composable
private fun MiniDurationDial(
    valueMinutes: Int,
    maxMinutes: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    fun minutesForPosition(position: Offset, width: Float, height: Float): Int {
        val center = Offset(width / 2f, height / 2f)
        val dx = position.x - center.x
        val dy = position.y - center.y
        var angle = atan2(dy, dx) * 180f / PI.toFloat() + 90f
        if (angle < 0f) angle += 360f
        val fraction = angle / 360f
        val raw = (fraction * maxMinutes).roundToInt()
        return if (raw == 0) maxMinutes else raw.coerceIn(1, maxMinutes)
    }

    Canvas(
        modifier = modifier.pointerInput(maxMinutes) {
            detectDragGestures(
                onDragStart = { position ->
                    onValueChange(minutesForPosition(position, size.width, size.height))
                },
                onDrag = { change, _ ->
                    onValueChange(minutesForPosition(change.position, size.width, size.height))
                }
            )
        }
    ) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = size.minDimension * 0.45f
        drawCircle(color = DarkBg, radius = radius, center = center)
        drawCircle(
            color = AccentCyan.copy(alpha = 0.55f),
            radius = radius,
            center = center,
            style = Stroke(width = 2.dp.toPx())
        )

        repeat(12) { tick ->
            val angle = (tick * 30f - 90f) * PI.toFloat() / 180f
            val outer = Offset(
                center.x + cos(angle) * radius * 0.86f,
                center.y + sin(angle) * radius * 0.86f
            )
            val inner = Offset(
                center.x + cos(angle) * radius * 0.72f,
                center.y + sin(angle) * radius * 0.72f
            )
            drawLine(
                color = TextHint,
                start = inner,
                end = outer,
                strokeWidth = 1.5.dp.toPx(),
                cap = StrokeCap.Round
            )
        }

        val fraction = valueMinutes.coerceIn(1, maxMinutes).toFloat() / maxMinutes.toFloat()
        val handAngle = (fraction * 360f - 90f) * PI.toFloat() / 180f
        val handEnd = Offset(
            center.x + cos(handAngle) * radius * 0.62f,
            center.y + sin(handAngle) * radius * 0.62f
        )
        drawLine(
            color = AccentCyan,
            start = center,
            end = handEnd,
            strokeWidth = 3.dp.toPx(),
            cap = StrokeCap.Round
        )
        drawCircle(color = AccentCyan, radius = 4.dp.toPx(), center = center)
    }
}

@Composable
private fun ProfileConfigRow(
    profile: PomodoroProfile,
    onApply: () -> Unit,
    onDelete: (() -> Unit)?
) {
    val displayName = when (profile.id) {
        "builtin-classic" -> stringResource(R.string.pomodoro_profile_classic)
        "builtin-deep" -> stringResource(R.string.pomodoro_profile_deep)
        "builtin-sprint" -> stringResource(R.string.pomodoro_profile_sprint)
        else -> profile.name
    }
    val sessions = if (profile.config.targetSessions == 0) {
        stringResource(R.string.pomodoro_sessions_infinite_short)
    } else {
        profile.config.targetSessions.toString()
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkBg.copy(alpha = 0.42f)),
        border = BorderStroke(1.dp, CardBorder)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(displayName, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text(
                    stringResource(
                        R.string.pomodoro_profile_summary,
                        profile.config.focusMinutes,
                        profile.config.shortBreakMinutes,
                        formatLongBreak(profile.config.longBreakMinutes),
                        sessions
                    ),
                    color = TextHint,
                    fontSize = 10.sp
                )
            }
            TextButton(onClick = onApply) {
                Text(stringResource(R.string.pomodoro_profile_apply))
            }
            if (onDelete != null) {
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.pomodoro_profile_delete),
                        tint = DangerRed
                    )
                }
            }
        }
    }
}

private fun formatDurationInput(minutes: Int, longFormat: Boolean): String {
    return if (longFormat) formatLongBreak(minutes) else minutes.toString()
}

private fun formatLongBreak(minutes: Int): String {
    val safe = minutes.coerceAtLeast(0)
    return "%02d:%02d".format(safe / 60, safe % 60)
}

private fun parseDurationInput(
    value: String,
    longFormat: Boolean,
    maxMinutes: Int
): Int? {
    if (value.isBlank()) return null
    val minutes = if (!longFormat) {
        value.toIntOrNull()
    } else if (value.contains(':')) {
        val parts = value.split(':', limit = 2)
        val hours = parts.getOrNull(0)?.takeIf(String::isNotBlank)?.toIntOrNull() ?: 0
        val minutePart = parts.getOrNull(1)?.takeIf(String::isNotBlank)?.toIntOrNull() ?: 0
        if (minutePart !in 0..59) return null
        hours * 60 + minutePart
    } else {
        value.toIntOrNull()?.times(60)
    }
    return minutes?.takeIf { it in 1..maxMinutes }
}
