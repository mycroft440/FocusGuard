@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.focusguard.ui.compose.screens

import android.graphics.drawable.Drawable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.focusguard.R
import com.focusguard.ui.compose.theme.AccentCyan
import com.focusguard.ui.compose.theme.AccentCyanInk
import com.focusguard.ui.compose.theme.CardBorder
import com.focusguard.ui.compose.theme.DangerRed
import com.focusguard.ui.compose.theme.DarkCard
import com.focusguard.ui.compose.theme.DarkSurface
import com.focusguard.ui.compose.theme.TextHint
import com.focusguard.ui.compose.theme.TextPrimary
import com.focusguard.ui.compose.theme.TextSecondary
import com.focusguard.utils.UsageLimitBehaviorPolicy
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val UsageLimitSummaryAmber = Color(0xFFF6AD55)
private val UsageLimitSecondaryStroke = Color(0xFF2B3844)

/**
 * Reorganized app-limit editor based on the three decisions a person makes:
 * how much time is available, what happens after it runs out, and how long the
 * rule remains active. This composable intentionally changes presentation only;
 * persistence and blocking semantics stay in the caller and behavior policy.
 */
@Suppress("UNUSED_PARAMETER")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppLimitRedesignedSheet(
    app: UsageLimitAppUi,
    permissionsMissing: Boolean,
    hasMasterCredential: Boolean,
    onConfigureMasterPassword: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (Int?, Boolean, String, String?, Long?) -> Unit
) {
    val context = LocalContext.current
    val editMode = app.currentLimitMinutes != null
    val now = System.currentTimeMillis()
    val remainingDays = app.lockUntilTimestamp
        ?.takeIf { it > now }
        ?.let {
            ((it - now + TimeUnit.DAYS.toMillis(1) - 1L) / TimeUnit.DAYS.toMillis(1)).toInt()
        }
        ?.coerceAtLeast(1)
        ?: 1

    var dailyMinutes by remember(app.packageName, app.currentLimitMinutes) {
        mutableStateOf(app.currentLimitMinutes?.toString().orEmpty())
    }
    var behavior by remember(app.packageName, app.lockMode) {
        mutableStateOf(
            if (UsageLimitBehaviorPolicy.isPauseMode(app.lockMode)) {
                UsageLimitBehaviorPolicy.PAUSE_30_PREFIX
            } else {
                UsageLimitBehaviorPolicy.BLOCK_UNTIL_TOMORROW_PREFIX
            }
        )
    }
    var durationAmount by remember(app.packageName, app.lockUntilTimestamp) {
        mutableStateOf(if (editMode) remainingDays.toString() else "1")
    }
    var durationUnit by remember(app.packageName, app.lockUntilTimestamp) {
        mutableStateOf(
            if (editMode) UsageLimitBehaviorPolicy.RuleDurationUnit.DAYS
            else UsageLimitBehaviorPolicy.RuleDurationUnit.MONTHS
        )
    }
    var minutesFocused by remember { mutableStateOf(false) }
    var iconDrawable by remember(app.packageName) { mutableStateOf<Drawable?>(null) }

    LaunchedEffect(app.packageName) {
        iconDrawable = withContext(Dispatchers.IO) {
            runCatching { context.packageManager.getApplicationIcon(app.packageName) }.getOrNull()
        }
    }

    val enteredMinutes = dailyMinutes.toIntOrNull() ?: 0
    val enteredDuration = durationAmount.toIntOrNull() ?: 0
    val ruleEnd = UsageLimitBehaviorPolicy.calculateRuleEndMillis(
        nowMillis = now,
        amount = enteredDuration,
        unit = durationUnit
    )
    val canSave = enteredMinutes > 0 && enteredDuration > 0 && ruleEnd != null

    val pauseLabel = stringResource(R.string.limits_pause_30_option)
    val blockTomorrowLabel = stringResource(R.string.limits_block_tomorrow_option)
    val daysLabel = stringResource(R.string.limits_duration_days)
    val weeksLabel = stringResource(R.string.limits_duration_weeks)
    val monthsLabel = stringResource(R.string.limits_duration_months)
    val selectedBehaviorLabel = if (behavior == UsageLimitBehaviorPolicy.PAUSE_30_PREFIX) {
        pauseLabel
    } else {
        blockTomorrowLabel
    }
    val durationUnitLabel = when (durationUnit) {
        UsageLimitBehaviorPolicy.RuleDurationUnit.DAYS -> daysLabel
        UsageLimitBehaviorPolicy.RuleDurationUnit.WEEKS -> weeksLabel
        UsageLimitBehaviorPolicy.RuleDurationUnit.MONTHS -> monthsLabel
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = DarkSurface,
        contentColor = TextPrimary,
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
        tonalElevation = 0.dp,
        scrimColor = Color.Black.copy(alpha = 0.72f),
        dragHandle = {
            BottomSheetDefaults.DragHandle(
                width = 36.dp,
                height = 4.dp,
                color = UsageLimitSecondaryStroke
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 760.dp)
        ) {
            UsageLimitSheetHeader(
                appName = app.appName,
                iconDrawable = iconDrawable
            )

            HorizontalDivider(color = CardBorder, thickness = 1.dp)

            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 22.dp)
            ) {
                PermissionWarning(permissionsMissing)

                UsageLimitDecisionBlock(
                    title = stringResource(R.string.limits_daily_max_title),
                    hint = "O contador zera à meia-noite."
                ) {
                    Row(
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(top = 14.dp, bottom = 12.dp)
                    ) {
                        BasicTextField(
                            value = dailyMinutes,
                            onValueChange = { raw ->
                                dailyMinutes = raw.filter(Char::isDigit).take(4)
                            },
                            modifier = Modifier
                                .width(108.dp)
                                .onFocusChanged { minutesFocused = it.isFocused },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            textStyle = TextStyle(
                                color = TextPrimary,
                                fontSize = 40.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            ),
                            decorationBox = { innerTextField ->
                                Column {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(50.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (dailyMinutes.isEmpty()) {
                                            Text(
                                                "0",
                                                color = TextHint.copy(alpha = 0.45f),
                                                fontSize = 40.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                        innerTextField()
                                    }
                                    HorizontalDivider(
                                        thickness = 2.dp,
                                        color = if (minutesFocused) AccentCyan else UsageLimitSecondaryStroke
                                    )
                                }
                            }
                        )
                        Text(
                            stringResource(R.string.limits_daily_max_minutes_label).lowercase(),
                            color = TextSecondary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }

                    UsageLimitMinuteShortcuts(
                        selectedMinutes = enteredMinutes.takeIf { it > 0 },
                        onSelect = { dailyMinutes = it.toString() }
                    )
                }

                UsageLimitDecisionBlock(
                    title = stringResource(R.string.limits_after_reaching_title)
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(9.dp),
                        modifier = Modifier.padding(top = 14.dp)
                    ) {
                        UsageLimitBehaviorChoice(
                            selected = behavior == UsageLimitBehaviorPolicy.PAUSE_30_PREFIX,
                            title = pauseLabel,
                            description = stringResource(R.string.limits_pause_30_desc),
                            onClick = { behavior = UsageLimitBehaviorPolicy.PAUSE_30_PREFIX }
                        )
                        UsageLimitBehaviorChoice(
                            selected = behavior == UsageLimitBehaviorPolicy.BLOCK_UNTIL_TOMORROW_PREFIX,
                            title = blockTomorrowLabel,
                            description = stringResource(R.string.limits_block_tomorrow_desc),
                            onClick = {
                                behavior = UsageLimitBehaviorPolicy.BLOCK_UNTIL_TOMORROW_PREFIX
                            }
                        )
                    }
                }

                UsageLimitDecisionBlock(
                    title = stringResource(R.string.limits_rule_duration_title),
                    hint = "Depois desse prazo o limite sai sozinho.",
                    showDivider = false
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        UsageLimitDurationStepper(
                            amount = enteredDuration.coerceAtLeast(0),
                            onDecrease = {
                                val next = (enteredDuration - 1).coerceAtLeast(1)
                                durationAmount = next.toString()
                            },
                            onIncrease = {
                                val next = (enteredDuration.coerceAtLeast(0) + 1).coerceAtMost(999)
                                durationAmount = next.toString()
                            }
                        )
                        UsageLimitDurationUnits(
                            selected = durationUnit,
                            daysLabel = daysLabel,
                            weeksLabel = weeksLabel,
                            monthsLabel = monthsLabel,
                            onSelect = { durationUnit = it },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                if (editMode && app.lockUntilTimestamp?.let { it > now } == true) {
                    val formatted = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
                        .format(Date(requireNotNull(app.lockUntilTimestamp)))
                    Text(
                        stringResource(R.string.limits_rule_current_until, formatted),
                        color = TextHint,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 2.dp, bottom = 10.dp)
                    )
                }

                UsageLimitRuleSummary(
                    canSave = canSave,
                    minutes = enteredMinutes,
                    behaviorLabel = selectedBehaviorLabel,
                    duration = enteredDuration,
                    durationUnitLabel = durationUnitLabel
                )

                if (editMode) {
                    TextButton(
                        onClick = { onSave(null, false, "NONE", null, null) },
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(bottom = 4.dp)
                    ) {
                        Text(
                            stringResource(R.string.sessions_remove_item),
                            color = DangerRed,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            HorizontalDivider(color = CardBorder, thickness = 1.dp)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DarkSurface)
                    .padding(horizontal = 22.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .weight(0.52f)
                        .height(50.dp),
                    shape = CircleShape,
                    border = BorderStroke(1.dp, UsageLimitSecondaryStroke),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary)
                ) {
                    Text(
                        stringResource(R.string.pomodoro_cancel_btn),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Button(
                    enabled = canSave,
                    onClick = {
                        val persistedMode = if (
                            behavior == UsageLimitBehaviorPolicy.PAUSE_30_PREFIX
                        ) {
                            UsageLimitBehaviorPolicy.pauseModeFor(app.packageName)
                        } else {
                            UsageLimitBehaviorPolicy.blockUntilTomorrowModeFor(app.packageName)
                        }
                        onSave(enteredMinutes, true, persistedMode, null, ruleEnd)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentCyan,
                        contentColor = AccentCyanInk,
                        disabledContainerColor = CardBorder,
                        disabledContentColor = TextHint
                    )
                ) {
                    Text(
                        stringResource(R.string.save),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun UsageLimitSheetHeader(
    appName: String,
    iconDrawable: Drawable?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        if (iconDrawable != null) {
            val bitmap = remember(iconDrawable) {
                iconDrawable.toBitmap(96, 96).asImageBitmap()
            }
            Image(
                bitmap = bitmap,
                contentDescription = appName,
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
            )
        } else {
            Surface(
                modifier = Modifier.size(42.dp),
                shape = RoundedCornerShape(12.dp),
                color = AccentCyan.copy(alpha = 0.14f),
                border = BorderStroke(1.dp, AccentCyan.copy(alpha = 0.28f))
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        appName.take(2).uppercase(),
                        color = AccentCyan,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }
        }

        Column {
            Text(
                "DEFINIR LIMITE PARA",
                color = TextSecondary,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 1.35.sp
            )
            Text(
                appName,
                color = TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun UsageLimitDecisionBlock(
    title: String,
    hint: String? = null,
    showDivider: Boolean = true,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 18.dp)
    ) {
        Text(
            title,
            color = TextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold
        )
        if (hint != null) {
            Text(
                hint,
                color = TextSecondary,
                fontSize = 12.5.sp,
                lineHeight = 18.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        content()
        if (showDivider) {
            HorizontalDivider(
                color = CardBorder,
                thickness = 1.dp,
                modifier = Modifier.padding(top = 18.dp)
            )
        }
    }
}

@Composable
private fun UsageLimitMinuteShortcuts(
    selectedMinutes: Int?,
    onSelect: (Int) -> Unit
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        listOf(
            15 to "15 min",
            30 to "30 min",
            45 to "45 min",
            60 to "1 h",
            120 to "2 h"
        ).forEach { (minutes, label) ->
            val selected = selectedMinutes == minutes
            Surface(
                onClick = { onSelect(minutes) },
                shape = CircleShape,
                color = if (selected) AccentCyan.copy(alpha = 0.14f) else Color.Transparent,
                contentColor = if (selected) AccentCyan else TextSecondary,
                border = BorderStroke(
                    1.dp,
                    if (selected) AccentCyan else UsageLimitSecondaryStroke
                )
            ) {
                Text(
                    label,
                    modifier = Modifier.padding(horizontal = 15.dp, vertical = 8.dp),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun UsageLimitBehaviorChoice(
    selected: Boolean,
    title: String,
    description: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = if (selected) AccentCyan.copy(alpha = 0.09f) else DarkCard,
        border = BorderStroke(
            if (selected) 1.5.dp else 1.dp,
            if (selected) AccentCyan else CardBorder
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(13.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 1.dp)
                    .size(20.dp)
                    .border(
                        2.dp,
                        if (selected) AccentCyan else TextHint,
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (selected) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .background(AccentCyan, CircleShape)
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    description,
                    color = TextSecondary,
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
        }
    }
}

@Composable
private fun UsageLimitDurationStepper(
    amount: Int,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = DarkCard,
        border = BorderStroke(1.dp, UsageLimitSecondaryStroke)
    ) {
        Row(
            modifier = Modifier.height(46.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = onDecrease,
                enabled = amount > 1,
                modifier = Modifier.width(40.dp)
            ) {
                Text(
                    "−",
                    color = if (amount > 1) AccentCyan else UsageLimitSecondaryStroke,
                    fontSize = 20.sp
                )
            }
            Text(
                amount.coerceAtLeast(1).toString(),
                modifier = Modifier.width(34.dp),
                textAlign = TextAlign.Center,
                color = TextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold
            )
            TextButton(
                onClick = onIncrease,
                enabled = amount < 999,
                modifier = Modifier.width(40.dp)
            ) {
                Text(
                    "+",
                    color = if (amount < 999) AccentCyan else UsageLimitSecondaryStroke,
                    fontSize = 20.sp
                )
            }
        }
    }
}

@Composable
private fun UsageLimitDurationUnits(
    selected: UsageLimitBehaviorPolicy.RuleDurationUnit,
    daysLabel: String,
    weeksLabel: String,
    monthsLabel: String,
    onSelect: (UsageLimitBehaviorPolicy.RuleDurationUnit) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .height(46.dp)
            .background(DarkCard, RoundedCornerShape(12.dp))
            .border(1.dp, UsageLimitSecondaryStroke, RoundedCornerShape(12.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        listOf(
            UsageLimitBehaviorPolicy.RuleDurationUnit.DAYS to daysLabel,
            UsageLimitBehaviorPolicy.RuleDurationUnit.WEEKS to weeksLabel,
            UsageLimitBehaviorPolicy.RuleDurationUnit.MONTHS to monthsLabel
        ).forEach { (unit, label) ->
            val active = selected == unit
            Surface(
                onClick = { onSelect(unit) },
                modifier = Modifier
                    .weight(1f)
                    .height(38.dp),
                shape = RoundedCornerShape(9.dp),
                color = if (active) AccentCyan.copy(alpha = 0.16f) else Color.Transparent,
                contentColor = if (active) AccentCyan else TextSecondary
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        label,
                        fontSize = 12.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun UsageLimitRuleSummary(
    canSave: Boolean,
    minutes: Int,
    behaviorLabel: String,
    duration: Int,
    durationUnitLabel: String
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 14.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = UsageLimitSummaryAmber.copy(alpha = 0.07f)
        ),
        border = BorderStroke(1.dp, UsageLimitSummaryAmber.copy(alpha = 0.26f))
    ) {
        Row(
            modifier = Modifier.padding(15.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                Icons.Default.Lock,
                contentDescription = null,
                tint = UsageLimitSummaryAmber,
                modifier = Modifier.size(17.dp)
            )
            Text(
                text = if (canSave) {
                    stringResource(
                        R.string.limits_rule_summary,
                        minutes,
                        behaviorLabel,
                        duration,
                        durationUnitLabel
                    )
                } else {
                    "Escolha um tempo para ver o que vai acontecer."
                },
                color = if (canSave) TextPrimary else TextSecondary,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                fontWeight = if (canSave) FontWeight.SemiBold else FontWeight.Normal
            )
        }
    }
}
