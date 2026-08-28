from pathlib import Path

ROOT = Path('.')

focus = ROOT / 'app/src/main/java/com/focusguard/ui/compose/screens/FocusModeScreen.kt'
text = focus.read_text(encoding='utf-8')

imports = {
    'import androidx.compose.foundation.Canvas\n': 'import androidx.compose.foundation.Canvas\n',
    'import androidx.compose.foundation.gestures.detectDragGestures\n': 'import androidx.compose.foundation.gestures.detectDragGestures\n',
    'import androidx.compose.foundation.shape.CircleShape\n': 'import androidx.compose.foundation.shape.CircleShape\n',
    'import androidx.compose.ui.geometry.Offset\n': 'import androidx.compose.ui.geometry.Offset\n',
    'import androidx.compose.ui.graphics.Brush\n': 'import androidx.compose.ui.graphics.Brush\n',
    'import androidx.compose.ui.graphics.StrokeCap\n': 'import androidx.compose.ui.graphics.StrokeCap\n',
    'import androidx.compose.ui.graphics.drawscope.Stroke\n': 'import androidx.compose.ui.graphics.drawscope.Stroke\n',
    'import androidx.compose.ui.input.pointer.pointerInput\n': 'import androidx.compose.ui.input.pointer.pointerInput\n',
}
for line in imports:
    if line not in text:
        if line.startswith('import androidx.compose.foundation.Canvas'):
            text = text.replace('import androidx.compose.foundation.BorderStroke\n', 'import androidx.compose.foundation.BorderStroke\n' + line, 1)
        elif line.startswith('import androidx.compose.foundation.gestures'):
            text = text.replace('import androidx.compose.foundation.layout.Arrangement\n', line + 'import androidx.compose.foundation.layout.Arrangement\n', 1)
        elif line.startswith('import androidx.compose.foundation.shape.CircleShape'):
            text = text.replace('import androidx.compose.foundation.shape.RoundedCornerShape\n', line + 'import androidx.compose.foundation.shape.RoundedCornerShape\n', 1)
        elif line.startswith('import androidx.compose.ui.geometry.Offset'):
            text = text.replace('import androidx.compose.ui.Alignment\n', 'import androidx.compose.ui.Alignment\n' + line, 1)
        elif line.startswith('import androidx.compose.ui.graphics.Brush'):
            text = text.replace('import androidx.compose.ui.graphics.Color\n', 'import androidx.compose.ui.graphics.Color\n' + line, 1)
        elif line.startswith('import androidx.compose.ui.graphics.StrokeCap'):
            text = text.replace('import androidx.compose.ui.graphics.ImageBitmap\n', 'import androidx.compose.ui.graphics.ImageBitmap\n' + line, 1)
        elif line.startswith('import androidx.compose.ui.graphics.drawscope.Stroke'):
            text = text.replace('import androidx.compose.ui.graphics.asImageBitmap\n', 'import androidx.compose.ui.graphics.asImageBitmap\n' + line, 1)
        elif line.startswith('import androidx.compose.ui.input.pointer.pointerInput'):
            text = text.replace('import androidx.compose.ui.platform.LocalContext\n', line + 'import androidx.compose.ui.platform.LocalContext\n', 1)

if 'import androidx.compose.material.icons.filled.Lock\n' not in text:
    text = text.replace('import androidx.compose.material.icons.filled.LockClock\n', 'import androidx.compose.material.icons.filled.Lock\nimport androidx.compose.material.icons.filled.LockClock\n', 1)
if 'import kotlin.math.PI\n' not in text:
    text = text.replace('import kotlin.math.roundToInt\n', 'import kotlin.math.PI\nimport kotlin.math.atan2\nimport kotlin.math.cos\nimport kotlin.math.roundToInt\nimport kotlin.math.sin\n', 1)

start = text.index('@Composable\nprivate fun FocusModeSetupContent(')
end = text.index('@Composable\nprivate fun FocusModeActiveContent(')

new_block = r'''@Composable
private fun FocusModeSetupContent(
    apps: List<FocusModeSelectableApp>,
    mandatoryPackages: Set<String>,
    isLoadingApps: Boolean,
    selectedPackages: Set<String>,
    durationText: String,
    onDurationTextChange: (String) -> Unit,
    durationUnit: FocusModePolicy.DurationUnit,
    onDurationUnitChange: (FocusModePolicy.DurationUnit) -> Unit,
    durationValid: Boolean,
    grayscaleEnabled: Boolean,
    onGrayscaleEnabledChange: (Boolean) -> Unit,
    onAddApps: () -> Unit,
    isStarting: Boolean,
    startOutcome: FocusModeManager.StartOutcome?,
    onStart: () -> Unit
) {
    val selectedApps = remember(apps, mandatoryPackages, selectedPackages) {
        apps.filter {
            it.packageName in selectedPackages && it.packageName !in mandatoryPackages
        }
    }
    val initialMinutes = when (durationUnit) {
        FocusModePolicy.DurationUnit.MINUTES -> durationText.toIntOrNull() ?: 40
        FocusModePolicy.DurationUnit.HOURS -> (durationText.toIntOrNull() ?: 1) * 60
        FocusModePolicy.DurationUnit.DAYS -> (durationText.toIntOrNull() ?: 0) * 24 * 60
    }.coerceIn(1, FOCUS_DURATION_MAX_MINUTES)
    var dialMinutes by rememberSaveable { mutableIntStateOf(initialMinutes) }
    var showHowItWorks by rememberSaveable { mutableStateOf(false) }

    val hoursUnit = stringResource(R.string.focus_mode_static_hours_short)
    val minutesUnit = stringResource(R.string.focus_mode_static_minutes_short)
    val durationLabel = when {
        dialMinutes < 60 -> "$dialMinutes $minutesUnit"
        dialMinutes % 60 == 0 -> "${dialMinutes / 60} $hoursUnit"
        else -> "${dialMinutes / 60} $hoursUnit ${dialMinutes % 60} $minutesUnit"
    }
    val allowedSummary = when (selectedApps.size) {
        0 -> stringResource(R.string.focus_mode_static_no_extra_apps)
        1 -> stringResource(R.string.focus_mode_static_one_extra_app)
        else -> stringResource(R.string.focus_mode_static_many_extra_apps, selectedApps.size)
    }
    val howTitle = stringResource(R.string.focus_mode_how_it_works)
    val void = Color(0xFF0A0C10)
    val surface = Color(0xFF14171D)
    val surface2 = Color(0xFF1B1F27)
    val stroke = Color(0xFF262B34)
    val tertiaryText = Color(0xFF5B6270)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF12151C), void, void)
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 4.dp)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        text = stringResource(R.string.focus_mode_compact_purpose),
                        color = TextSecondary,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        modifier = Modifier.weight(1f).padding(end = 10.dp)
                    )
                    TextButton(
                        onClick = { showHowItWorks = true },
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                    ) {
                        Text(
                            text = howTitle,
                            color = AccentCyan,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Icon(
                            Icons.Default.ArrowDropDown,
                            contentDescription = null,
                            tint = AccentCyan,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                Text(
                    text = stringResource(R.string.focus_mode_static_duration_section),
                    color = tertiaryText,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.8.sp,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )

                FocusDurationDial(
                    minutes = dialMinutes,
                    onMinutesChange = { next ->
                        if (next != dialMinutes) {
                            dialMinutes = next
                            onDurationUnitChange(FocusModePolicy.DurationUnit.MINUTES)
                            onDurationTextChange(next.toString())
                        }
                    },
                    trackColor = surface2,
                    tickColor = stroke,
                    tertiaryText = tertiaryText,
                    minutesUnit = minutesUnit,
                    hoursUnit = hoursUnit
                )

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = grayscaleEnabled,
                            role = Role.Switch,
                            onValueChange = onGrayscaleEnabledChange
                        ),
                    colors = CardDefaults.cardColors(containerColor = surface),
                    border = BorderStroke(1.dp, stroke),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.fg_focus_grayscale),
                                color = TextPrimary,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 16.sp
                            )
                            Text(
                                text = stringResource(R.string.focus_mode_grayscale_hint),
                                color = tertiaryText,
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                        Switch(
                            checked = grayscaleEnabled,
                            onCheckedChange = null
                        )
                    }
                }

                Text(
                    text = stringResource(R.string.focus_mode_static_allowed_section),
                    color = tertiaryText,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp
                )

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = surface),
                    border = BorderStroke(1.dp, stroke),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        FocusDrawerTile(
                            label = stringResource(R.string.focus_mode_static_phone),
                            caption = stringResource(R.string.focus_mode_static_always),
                            locked = true,
                            modifier = Modifier.weight(1f),
                            surface2 = surface2,
                            tertiaryText = tertiaryText,
                            stroke = stroke
                        ) {
                            Icon(
                                Icons.Default.Phone,
                                contentDescription = null,
                                tint = TextSecondary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        FocusDrawerTile(
                            label = stringResource(R.string.focus_mode_static_sms),
                            caption = stringResource(R.string.focus_mode_static_always),
                            locked = true,
                            modifier = Modifier.weight(1f),
                            surface2 = surface2,
                            tertiaryText = tertiaryText,
                            stroke = stroke
                        ) {
                            Icon(
                                Icons.Default.Message,
                                contentDescription = null,
                                tint = TextSecondary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        FocusSelectedDrawerTile(
                            selectedApps = selectedApps,
                            isLoading = isLoadingApps,
                            onClick = onAddApps,
                            modifier = Modifier.weight(1f),
                            surface2 = surface2,
                            tertiaryText = tertiaryText,
                            stroke = stroke
                        )
                        FocusDrawerTile(
                            label = stringResource(R.string.focus_mode_add_apps),
                            caption = "",
                            onClick = if (isLoadingApps) null else onAddApps,
                            modifier = Modifier.weight(1f),
                            surface2 = Color.Transparent,
                            tertiaryText = tertiaryText,
                            stroke = stroke,
                            dashedStyle = true
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = null,
                                tint = tertiaryText,
                                modifier = Modifier.size(25.dp)
                            )
                        }
                    }
                }

                if (startOutcome != null) {
                    FocusModeStartError(startOutcome)
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            Text(
                text = stringResource(
                    R.string.focus_mode_static_dock_summary,
                    durationLabel,
                    allowedSummary
                ),
                color = tertiaryText,
                fontSize = 11.sp,
                letterSpacing = 0.5.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 10.dp)
            )

            Button(
                onClick = onStart,
                enabled = !isStarting && durationValid && !isLoadingApps,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                shape = RoundedCornerShape(16.dp)
            ) {
                if (isStarting) {
                    CircularProgressIndicator(
                        color = Color(0xFF04201B),
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(20.dp)
                    )
                } else {
                    Text(
                        stringResource(R.string.focus_mode_review_start),
                        color = Color(0xFF04201B),
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 16.sp
                    )
                }
            }
        }

        if (showHowItWorks) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.78f))
                    .clickable { showHowItWorks = false }
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = surface),
                    border = BorderStroke(1.dp, AccentCyan.copy(alpha = 0.38f)),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(9.dp)
                    ) {
                        Text(
                            text = howTitle,
                            color = TextPrimary,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(R.string.focus_mode_static_purpose_body),
                            color = TextSecondary,
                            fontSize = 13.sp,
                            lineHeight = 19.sp
                        )
                        Text(
                            text = stringResource(R.string.focus_mode_tap_anywhere_to_close),
                            color = tertiaryText,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FocusDurationDial(
    minutes: Int,
    onMinutesChange: (Int) -> Unit,
    trackColor: Color,
    tickColor: Color,
    tertiaryText: Color,
    minutesUnit: String,
    hoursUnit: String
) {
    val progress = ((minutes - 1f) / (FOCUS_DURATION_MAX_MINUTES - 1f)).coerceIn(0f, 1f)
    val displayNumber = when {
        minutes < 60 -> minutes.toString()
        minutes % 60 == 0 -> (minutes / 60).toString()
        else -> String.format(Locale.getDefault(), "%.1f", minutes / 60f)
    }
    val displayUnit = if (minutes < 60) minutesUnit.uppercase(Locale.getDefault())
    else hoursUnit.uppercase(Locale.getDefault())

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier.size(238.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        fun update(position: Offset) {
                            val cx = size.width / 2f
                            val cy = size.height / 2f
                            var degrees = Math.toDegrees(
                                atan2(
                                    (position.y - cy).toDouble(),
                                    (position.x - cx).toDouble()
                                )
                            ).toFloat()
                            if (degrees < 0f) degrees += 360f
                            var relative = degrees - 135f
                            if (relative < 0f) relative += 360f
                            relative = relative.coerceIn(0f, 270f)
                            val next = (
                                1f + (relative / 270f) * (FOCUS_DURATION_MAX_MINUTES - 1f)
                            ).roundToInt().coerceIn(1, FOCUS_DURATION_MAX_MINUTES)
                            onMinutesChange(next)
                        }
                        detectDragGestures(
                            onDragStart = { update(it) },
                            onDrag = { change, _ ->
                                update(change.position)
                                change.consume()
                            }
                        )
                    }
            ) {
                val strokeWidth = 10.dp.toPx()
                val radius = (size.minDimension - strokeWidth) / 2f
                val center = Offset(size.width / 2f, size.height / 2f)

                for (index in 0..18) {
                    val tickAngle = Math.toRadians((135f + (270f * index / 18f)).toDouble())
                    val outer = Offset(
                        center.x + cos(tickAngle).toFloat() * (radius + 9.dp.toPx()),
                        center.y + sin(tickAngle).toFloat() * (radius + 9.dp.toPx())
                    )
                    val inner = Offset(
                        center.x + cos(tickAngle).toFloat() * (radius + 3.dp.toPx()),
                        center.y + sin(tickAngle).toFloat() * (radius + 3.dp.toPx())
                    )
                    drawLine(
                        color = tickColor,
                        start = inner,
                        end = outer,
                        strokeWidth = 1.5.dp.toPx()
                    )
                }

                drawArc(
                    color = trackColor,
                    startAngle = 135f,
                    sweepAngle = 270f,
                    useCenter = false,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
                if (progress > 0f) {
                    drawArc(
                        color = AccentCyan,
                        startAngle = 135f,
                        sweepAngle = 270f * progress,
                        useCenter = false,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                    )
                }

                val handleAngle = Math.toRadians((135f + 270f * progress).toDouble())
                val handle = Offset(
                    center.x + cos(handleAngle).toFloat() * radius,
                    center.y + sin(handleAngle).toFloat() * radius
                )
                drawCircle(color = Color(0xFF0A0C10), radius = 16.dp.toPx(), center = handle)
                drawCircle(color = AccentCyan.copy(alpha = 0.20f), radius = 13.dp.toPx(), center = handle)
                drawCircle(color = AccentCyan, radius = 9.dp.toPx(), center = handle)
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = displayNumber,
                    color = TextPrimary,
                    fontSize = 50.sp,
                    lineHeight = 52.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = displayUnit,
                    color = TextSecondary,
                    fontSize = 12.sp,
                    letterSpacing = 1.4.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
        Row(
            modifier = Modifier.width(238.dp).padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("1 $minutesUnit", color = tertiaryText, fontSize = 11.sp)
            Text("8 $hoursUnit", color = tertiaryText, fontSize = 11.sp)
        }
    }
}

@Composable
private fun FocusDrawerTile(
    label: String,
    caption: String,
    locked: Boolean = false,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    surface2: Color,
    tertiaryText: Color,
    stroke: Color,
    dashedStyle: Boolean = false,
    icon: @Composable () -> Unit
) {
    Column(
        modifier = modifier.then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(surface2)
                    .border(1.dp, if (dashedStyle) stroke else Color.Transparent, RoundedCornerShape(15.dp)),
                contentAlignment = Alignment.Center
            ) { icon() }
            if (locked || selected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(if (selected) AccentCyan else tertiaryText)
                        .border(2.dp, Color(0xFF14171D), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (selected) Icons.Default.Add else Icons.Default.Lock,
                        contentDescription = null,
                        tint = if (selected) Color(0xFF04201B) else Color(0xFF14171D),
                        modifier = Modifier.size(if (selected) 12.dp else 10.dp)
                    )
                }
            }
        }
        Text(
            text = label,
            color = if (dashedStyle) tertiaryText else TextPrimary,
            fontWeight = FontWeight.SemiBold,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(top = 7.dp)
        )
        Text(
            text = caption.ifBlank { " " },
            color = if (selected) AccentCyan else tertiaryText,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp)
        )
    }
}

@Composable
private fun FocusSelectedDrawerTile(
    selectedApps: List<FocusModeSelectableApp>,
    isLoading: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
    surface2: Color,
    tertiaryText: Color,
    stroke: Color
) {
    val label = when {
        isLoading -> stringResource(R.string.focus_mode_static_apps)
        selectedApps.isEmpty() -> stringResource(R.string.focus_mode_static_apps)
        selectedApps.size == 1 -> selectedApps.first().appName
        else -> stringResource(R.string.focus_mode_static_apps_count, selectedApps.size)
    }
    val caption = when {
        isLoading -> stringResource(R.string.focus_mode_static_loading)
        selectedApps.isEmpty() -> stringResource(R.string.focus_mode_static_tap_choose)
        else -> stringResource(R.string.focus_mode_static_selected)
    }
    FocusDrawerTile(
        label = label,
        caption = caption,
        selected = selectedApps.isNotEmpty(),
        onClick = if (isLoading) null else onClick,
        modifier = modifier,
        surface2 = surface2,
        tertiaryText = tertiaryText,
        stroke = stroke
    ) {
        when {
            isLoading -> CircularProgressIndicator(
                color = AccentCyan,
                strokeWidth = 2.dp,
                modifier = Modifier.size(28.dp)
            )
            selectedApps.size == 1 -> InstalledAppIcon(
                packageName = selectedApps.first().packageName,
                appName = selectedApps.first().appName
            )
            else -> Icon(
                Icons.Default.Apps,
                contentDescription = null,
                tint = if (selectedApps.isEmpty()) tertiaryText else AccentCyan,
                modifier = Modifier.size(25.dp)
            )
        }
    }
}

'''

text = text[:start] + new_block + text[end:]
focus.write_text(text, encoding='utf-8')

# Make the Focus Mode title match the reference hierarchy without changing other tabs.
main = ROOT / 'app/src/main/java/com/focusguard/ui/compose/screens/MainScreen.kt'
main_text = main.read_text(encoding='utf-8')
needle = '''                                color = TextPrimary,\n                                fontSize = 20.sp,\n                                fontWeight = FontWeight.Bold,\n                                letterSpacing = (-0.2).sp\n'''
replacement = '''                                color = TextPrimary,\n                                fontSize = 30.sp,\n                                fontWeight = FontWeight.ExtraBold,\n                                letterSpacing = (-0.6).sp\n'''
if needle not in main_text:
    raise SystemExit('Focus Mode title style marker not found')
main.write_text(main_text.replace(needle, replacement, 1), encoding='utf-8')

# Add focused UI strings in all three maintained focus-mode locale files.
string_values = {
    'app/src/main/res/values/focus_mode_pending_strings.xml': (
        '    <string name="focus_mode_grayscale_hint">Removes colors to reduce stimulation.</string>\n'
        '    <string name="focus_mode_review_start">Review and start</string>\n'
    ),
    'app/src/main/res/values-en/focus_mode_pending_strings.xml': (
        '    <string name="focus_mode_grayscale_hint">Removes colors to reduce stimulation.</string>\n'
        '    <string name="focus_mode_review_start">Review and start</string>\n'
    ),
    'app/src/main/res/values-pt/focus_mode_pending_strings.xml': (
        '    <string name="focus_mode_grayscale_hint">Remove as cores para reduzir estímulos.</string>\n'
        '    <string name="focus_mode_review_start">Revisar e iniciar</string>\n'
    ),
}
for path_str, additions in string_values.items():
    path = ROOT / path_str
    xml = path.read_text(encoding='utf-8')
    if 'focus_mode_grayscale_hint' not in xml:
        xml = xml.replace('</resources>', additions + '</resources>')
    path.write_text(xml, encoding='utf-8')

# Harden power-menu classification: generic ActionsDialog is not enough by itself.
policy = ROOT / 'app/src/main/java/com/focusguard/security/PowerMenuProtectionPolicy.kt'
policy_text = policy.read_text(encoding='utf-8')
old = '''        if (ambiguousClassMarkers.any { className.contains(it, ignoreCase = true) }) {\n            return DirectDecision.MATCH\n        }\n'''
new = '''        if (ambiguousClassMarkers.any { className.contains(it, ignoreCase = true) }) {\n            // `ActionsDialog` is reused by SystemUI for surfaces that are not the\n            // power menu. Require rendered power actions before accepting it.\n            return if (isPowerMenu(packageName, className, values)) {\n                DirectDecision.MATCH\n            } else {\n                DirectDecision.UNKNOWN\n            }\n        }\n'''
if old not in policy_text:
    raise SystemExit('Power policy ambiguous marker not found')
policy.write_text(policy_text.replace(old, new, 1), encoding='utf-8')

# Never synthesize HOME/BACK from a power-menu signal that was never confirmed.
controller = ROOT / 'app/src/main/java/com/focusguard/service/ProtectedPowerMenuController.kt'
controller_text = controller.read_text(encoding='utf-8')
old_screen = '''    /** Screen-off is not proof that an OEM global-actions window disappeared. */\n    fun onScreenOff() {\n        if (Looper.myLooper() != Looper.getMainLooper()) {\n            mainHandler.post(::onScreenOff)\n            return\n        }\n        if (shouldRequestCloseOnScreenOff(overlayVisible)) {\n            requestNativeHomeClose()\n        }\n    }\n'''
new_screen = '''    /**\n     * Screen-off must never synthesize HOME. In Focus Mode HOME resolves to the\n     * Hard Block shell itself, which can look like the app opened spontaneously.\n     * A later real global-actions event will recreate the shield if still needed.\n     */\n    fun onScreenOff() {\n        if (Looper.myLooper() != Looper.getMainLooper()) {\n            mainHandler.post(::onScreenOff)\n            return\n        }\n        if (overlayVisible) dismiss()\n    }\n'''
if old_screen not in controller_text:
    raise SystemExit('Power controller screen-off marker not found')
controller_text = controller_text.replace(old_screen, new_screen, 1)
old_recheck = '            unconfirmedSignalGraceExpired -> RecheckDecision.REQUEST_BACK\n'
new_recheck = '''            // A class-only/undefined-window signal that never became a real\n            // SystemUI power-menu root is a false-positive candidate. Hide it\n            // instead of injecting BACK/HOME into whatever the user is doing.\n            unconfirmedSignalGraceExpired -> RecheckDecision.HIDE\n'''
if old_recheck not in controller_text:
    raise SystemExit('Power controller unconfirmed recheck marker not found')
controller.write_text(controller_text.replace(old_recheck, new_recheck, 1), encoding='utf-8')

# Update regression tests for both false-positive paths.
policy_test = ROOT / 'app/src/test/java/com/focusguard/security/PowerMenuProtectionPolicyTest.kt'
pt = policy_test.read_text(encoding='utf-8')
anchor = '''    @Test\n    fun `generic system ui window falls back to its tree`() {\n'''
addition = '''    @Test\n    fun `ambiguous actions dialog needs actual power actions`() {\n        assertThat(\n            PowerMenuProtectionPolicy.classifyDirect(\n                packageName = "com.android.systemui",\n                className = "com.android.systemui.ActionsDialog",\n                values = listOf("Wi-Fi", "Bluetooth")\n            )\n        ).isEqualTo(DirectDecision.UNKNOWN)\n        assertThat(\n            PowerMenuProtectionPolicy.classifyDirect(\n                packageName = "com.android.systemui",\n                className = "com.android.systemui.ActionsDialog",\n                values = listOf("Desligar", "Reiniciar")\n            )\n        ).isEqualTo(DirectDecision.MATCH)\n    }\n\n'''
if addition not in pt:
    if anchor not in pt:
        raise SystemExit('Power policy test anchor not found')
    pt = pt.replace(anchor, addition + anchor, 1)
policy_test.write_text(pt, encoding='utf-8')

overlay_test = ROOT / 'app/src/test/java/com/focusguard/service/OverlayWindowStateTest.kt'
ot = overlay_test.read_text(encoding='utf-8')
ot = ot.replace('fun `undefined window expiry requests back while overlay remains`()', 'fun `undefined unconfirmed power signal hides without injecting navigation`()', 1)
ot = ot.replace('ProtectedPowerMenuController.RecheckDecision.REQUEST_BACK\n        )\n    }\n\n    @Test\n    fun `ignored back escalates', 'ProtectedPowerMenuController.RecheckDecision.HIDE\n        )\n    }\n\n    @Test\n    fun `ignored back escalates', 1)
overlay_test.write_text(ot, encoding='utf-8')

print('Focus Mode redesign and power-menu false-positive fix applied.')
