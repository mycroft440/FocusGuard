@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.focusguard.ui.compose.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.focusguard.utils.WebsiteBlocker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

private enum class WebsiteLimitEditorStep {
    DETAILS,
    BLOCK_MODE
}

private data class WebsiteLimitDetailsDraft(
    val minutes: Int,
    val duration: Int,
    val durationUnit: UsageLimitBehaviorPolicy.RuleDurationUnit,
    val durationEdited: Boolean
)

/**
 * Entry point kept for the website/keyword catalogue.
 *
 * Site limits intentionally use the same daily allowance, rule duration and
 * post-limit behavior as app limits. The legacy NONE/TIME/PASSWORD choices are
 * still understood by the enforcement layer for existing rows, but new rules
 * are persisted with the shared PAUSE_30/BLOCK_UNTIL_TOMORROW policy.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun AddUsageLimitRuleDialog(
    initialRule: String? = null,
    keywordMode: Boolean,
    permissionsMissing: Boolean,
    hasMasterCredential: Boolean,
    onConfigureMasterPassword: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (String, Int, String, String?, Long?) -> Unit
) {
    WebsiteUsageLimitEditorSheet(
        initialRule = initialRule,
        keywordMode = keywordMode,
        permissionsMissing = permissionsMissing,
        initialMinutes = null,
        initialLockMode = "NONE",
        initialLockUntilTimestamp = null,
        allowTargetEditing = true,
        allowRemove = false,
        onDismiss = onDismiss,
        onSave = { rule, minutes, enabled, lockMode, lockUntil ->
            if (enabled && minutes != null && minutes > 0) {
                onSave(rule, minutes, lockMode, null, lockUntil)
            }
        }
    )
}

/**
 * Shared two-step editor for website and keyword daily limits.
 *
 * The layout and product semantics deliberately mirror [AppLimitRedesignedSheet]:
 * screen 1 owns the daily allowance and overall rule duration; screen 2 owns
 * what happens after the allowance is consumed on a given day.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WebsiteUsageLimitEditorSheet(
    initialRule: String?,
    keywordMode: Boolean,
    permissionsMissing: Boolean,
    initialMinutes: Int?,
    initialLockMode: String,
    initialLockUntilTimestamp: Long?,
    allowTargetEditing: Boolean,
    allowRemove: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, Int?, Boolean, String, Long?) -> Unit
) {
    val initialTarget = remember(initialRule, keywordMode) {
        val normalized = initialRule?.let(WebsiteBlocker::normalizeRule).orEmpty()
        if (keywordMode && WebsiteBlocker.isKeywordRule(normalized)) {
            WebsiteBlocker.displayRule(normalized).removePrefix("*").removeSuffix("*")
        } else {
            normalized
        }
    }
    var target by remember(initialRule, keywordMode) { mutableStateOf(initialTarget) }

    val normalizedRule = if (keywordMode) {
        WebsiteBlocker.normalizeRule("keyword:$target")
            .takeIf(WebsiteBlocker::isKeywordRule)
            .orEmpty()
    } else {
        WebsiteBlocker.extractDomain(target)
    }
    val targetValid = normalizedRule.isNotEmpty()
    val editMode = initialMinutes != null
    val now = remember(initialRule, initialLockUntilTimestamp) { System.currentTimeMillis() }
    val activeExistingRuleEnd = remember(initialLockUntilTimestamp, now) {
        initialLockUntilTimestamp?.takeIf { it > now }
    }
    val remainingDays = remember(activeExistingRuleEnd, now) {
        activeExistingRuleEnd
            ?.let {
                ((it - now + TimeUnit.DAYS.toMillis(1) - 1L) /
                    TimeUnit.DAYS.toMillis(1)).toInt()
            }
            ?.coerceAtLeast(1)
            ?: 1
    }

    var step by remember(initialRule) { mutableStateOf(WebsiteLimitEditorStep.DETAILS) }
    var detailsDraft by remember(
        initialRule,
        initialMinutes,
        initialLockUntilTimestamp
    ) {
        mutableStateOf(
            WebsiteLimitDetailsDraft(
                minutes = initialMinutes ?: 0,
                duration = if (editMode) remainingDays else 1,
                durationUnit = if (editMode) {
                    UsageLimitBehaviorPolicy.RuleDurationUnit.DAYS
                } else {
                    UsageLimitBehaviorPolicy.RuleDurationUnit.MONTHS
                },
                durationEdited = false
            )
        )
    }
    var behavior by remember(initialRule, initialLockMode) {
        mutableStateOf(
            if (UsageLimitBehaviorPolicy.isPauseMode(initialLockMode)) {
                UsageLimitBehaviorPolicy.PAUSE_30_PREFIX
            } else {
                UsageLimitBehaviorPolicy.BLOCK_UNTIL_TOMORROW_PREFIX
            }
        )
    }

    val calculatedRuleEnd = remember(
        now,
        detailsDraft.duration,
        detailsDraft.durationUnit
    ) {
        UsageLimitBehaviorPolicy.calculateRuleEndMillis(
            nowMillis = now,
            amount = detailsDraft.duration,
            unit = detailsDraft.durationUnit
        )
    }
    val ruleEnd = remember(
        activeExistingRuleEnd,
        detailsDraft.durationEdited,
        calculatedRuleEnd
    ) {
        UsageLimitBehaviorPolicy.resolveRuleEndForEdit(
            existingRuleEndMillis = activeExistingRuleEnd,
            durationEdited = detailsDraft.durationEdited,
            calculatedRuleEndMillis = calculatedRuleEnd
        )
    }
    val canSave = targetValid &&
        detailsDraft.minutes > 0 &&
        detailsDraft.duration > 0 &&
        ruleEnd != null

    val daysLabel = stringResource(R.string.limits_duration_days)
    val weeksLabel = stringResource(R.string.limits_duration_weeks)
    val monthsLabel = stringResource(R.string.limits_duration_months)
    val behaviorLabel = if (behavior == UsageLimitBehaviorPolicy.PAUSE_30_PREFIX) {
        stringResource(R.string.limits_pause_30_option)
    } else {
        stringResource(R.string.limits_block_tomorrow_option)
    }
    val durationUnitLabel = when (detailsDraft.durationUnit) {
        UsageLimitBehaviorPolicy.RuleDurationUnit.DAYS -> daysLabel
        UsageLimitBehaviorPolicy.RuleDurationUnit.WEEKS -> weeksLabel
        UsageLimitBehaviorPolicy.RuleDurationUnit.MONTHS -> monthsLabel
    }
    val focusManager = LocalFocusManager.current

    BackHandler(enabled = step == WebsiteLimitEditorStep.BLOCK_MODE) {
        step = WebsiteLimitEditorStep.DETAILS
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
                color = CardBorder
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 760.dp)
        ) {
            WebsiteLimitSheetHeader(
                keywordMode = keywordMode,
                rule = normalizedRule.ifBlank { initialTarget },
                step = step
            )
            HorizontalDivider(color = CardBorder, thickness = 1.dp)

            when (step) {
                WebsiteLimitEditorStep.DETAILS -> {
                    WebsiteLimitDetailsScreen(
                        target = target,
                        onTargetChange = { target = it },
                        targetValid = targetValid,
                        keywordMode = keywordMode,
                        targetEditable = allowTargetEditing,
                        permissionsMissing = permissionsMissing,
                        initialDraft = detailsDraft,
                        nowMillis = now,
                        currentRuleEnd = initialLockUntilTimestamp,
                        editMode = editMode,
                        allowRemove = allowRemove,
                        onRemove = {
                            if (targetValid) {
                                onSave(normalizedRule, null, false, "NONE", null)
                            }
                        },
                        onDismiss = onDismiss,
                        onContinue = { draft ->
                            detailsDraft = draft
                            focusManager.clearFocus(force = true)
                            step = WebsiteLimitEditorStep.BLOCK_MODE
                        }
                    )
                }

                WebsiteLimitEditorStep.BLOCK_MODE -> {
                    WebsiteLimitBehaviorScreen(
                        behavior = behavior,
                        onBehaviorChange = { behavior = it },
                        minutes = detailsDraft.minutes,
                        duration = detailsDraft.duration,
                        durationUnitLabel = durationUnitLabel,
                        behaviorLabel = behaviorLabel,
                        canSave = canSave,
                        onBack = { step = WebsiteLimitEditorStep.DETAILS },
                        onSave = {
                            val persistedMode = if (
                                behavior == UsageLimitBehaviorPolicy.PAUSE_30_PREFIX
                            ) {
                                UsageLimitBehaviorPolicy.pauseModeFor(normalizedRule)
                            } else {
                                UsageLimitBehaviorPolicy.blockUntilTomorrowModeFor(normalizedRule)
                            }
                            onSave(
                                normalizedRule,
                                detailsDraft.minutes,
                                true,
                                persistedMode,
                                ruleEnd
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun WebsiteLimitSheetHeader(
    keywordMode: Boolean,
    rule: String,
    step: WebsiteLimitEditorStep
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        Surface(
            modifier = Modifier.size(42.dp),
            shape = RoundedCornerShape(12.dp),
            color = AccentCyan.copy(alpha = 0.12f)
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (keywordMode) Icons.Default.Tag else Icons.Default.Public,
                    contentDescription = null,
                    tint = AccentCyan,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = rule.takeIf(String::isNotBlank)?.let(WebsiteBlocker::displayRule)
                    ?: stringResource(
                        if (keywordMode) R.string.limits_add_keyword_btn
                        else R.string.limits_add_site_btn
                    ),
                color = TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                if (step == WebsiteLimitEditorStep.DETAILS) "1 / 2" else "2 / 2",
                color = TextHint,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun WebsiteLimitDetailsScreen(
    target: String,
    onTargetChange: (String) -> Unit,
    targetValid: Boolean,
    keywordMode: Boolean,
    targetEditable: Boolean,
    permissionsMissing: Boolean,
    initialDraft: WebsiteLimitDetailsDraft,
    nowMillis: Long,
    currentRuleEnd: Long?,
    editMode: Boolean,
    allowRemove: Boolean,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
    onContinue: (WebsiteLimitDetailsDraft) -> Unit
) {
    var dailyMinutes by remember(initialDraft) {
        mutableStateOf(initialDraft.minutes.takeIf { it > 0 }?.toString().orEmpty())
    }
    var durationAmount by remember(initialDraft) {
        mutableStateOf(initialDraft.duration.takeIf { it > 0 }?.toString().orEmpty())
    }
    var durationUnit by remember(initialDraft) { mutableStateOf(initialDraft.durationUnit) }
    var durationEdited by remember(initialDraft) { mutableStateOf(initialDraft.durationEdited) }

    val minutes = dailyMinutes.toIntOrNull() ?: 0
    val duration = durationAmount.toIntOrNull() ?: 0
    val canAdvance = targetValid && minutes > 0 && duration > 0
    val configuration = LocalConfiguration.current
    val currentLocale = configuration.locales[0]
    val formattedCurrentRuleEnd = remember(editMode, currentRuleEnd, nowMillis, currentLocale) {
        currentRuleEnd
            ?.takeIf { editMode && it > nowMillis }
            ?.let { end -> SimpleDateFormat("dd/MM/yyyy", currentLocale).format(Date(end)) }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp)
        ) {
            WebsitePermissionWarning(permissionsMissing)

            WebsiteDecisionBlock(
                title = stringResource(
                    if (keywordMode) R.string.limits_keyword_label
                    else R.string.limits_domain_label
                )
            ) {
                OutlinedTextField(
                    value = target,
                    onValueChange = onTargetChange,
                    readOnly = !targetEditable,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = target.isNotBlank() && !targetValid,
                    supportingText = {
                        Text(
                            stringResource(
                                if (keywordMode) R.string.limits_keyword_helper
                                else R.string.block_targets_site_helper
                            ),
                            color = if (target.isNotBlank() && !targetValid) DangerRed else TextHint
                        )
                    },
                    colors = websiteLimitFieldColors()
                )
            }

            WebsiteDecisionBlock(
                title = stringResource(R.string.limits_daily_max_title)
            ) {
                WebsiteDailyMinutesEditor(
                    value = dailyMinutes,
                    onValueChange = { raw ->
                        dailyMinutes = raw.filter(Char::isDigit).take(4)
                    }
                )
            }

            WebsiteDecisionBlock(
                title = stringResource(R.string.limits_rule_duration_title),
                showDivider = false
            ) {
                WebsiteRuleDurationEditor(
                    amount = durationAmount,
                    onAmountChange = { raw ->
                        durationAmount = raw.filter(Char::isDigit).take(3)
                        durationEdited = true
                    },
                    unit = durationUnit,
                    onUnitChange = { unit ->
                        durationUnit = unit
                        durationEdited = true
                    }
                )
            }

            formattedCurrentRuleEnd?.let { formatted ->
                Text(
                    stringResource(R.string.limits_rule_current_until, formatted),
                    color = TextHint,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(bottom = 10.dp)
                )
            }

            if (editMode && allowRemove) {
                TextButton(
                    onClick = onRemove,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
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
                    .weight(0.55f)
                    .height(50.dp),
                shape = CircleShape,
                border = BorderStroke(1.dp, CardBorder),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary)
            ) {
                Text(stringResource(R.string.pomodoro_cancel_btn))
            }
            Button(
                enabled = canAdvance,
                onClick = {
                    onContinue(
                        WebsiteLimitDetailsDraft(
                            minutes = minutes,
                            duration = duration,
                            durationUnit = durationUnit,
                            durationEdited = durationEdited
                        )
                    )
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
                    stringResource(R.string.limits_continue_to_behavior),
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun WebsiteDailyMinutesEditor(
    value: String,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(R.string.limits_daily_max_minutes_label)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        textStyle = androidx.compose.ui.text.TextStyle(
            color = TextPrimary,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        ),
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        colors = websiteLimitFieldColors()
    )
    Spacer(Modifier.height(12.dp))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        listOf(15, 30, 60, 120).forEach { minutes ->
            FilterChip(
                selected = value.toIntOrNull() == minutes,
                onClick = { onValueChange(minutes.toString()) },
                label = { Text("$minutes min") },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = AccentCyan.copy(alpha = 0.18f),
                    selectedLabelColor = AccentCyan
                )
            )
        }
    }
}

@Composable
private fun WebsiteRuleDurationEditor(
    amount: String,
    onAmountChange: (String) -> Unit,
    unit: UsageLimitBehaviorPolicy.RuleDurationUnit,
    onUnitChange: (UsageLimitBehaviorPolicy.RuleDurationUnit) -> Unit
) {
    OutlinedTextField(
        value = amount,
        onValueChange = onAmountChange,
        label = { Text(stringResource(R.string.limits_duration_amount_label)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.width(112.dp),
        singleLine = true,
        colors = websiteLimitFieldColors()
    )
    Spacer(Modifier.height(10.dp))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        WebsiteDurationChip(
            selected = unit == UsageLimitBehaviorPolicy.RuleDurationUnit.DAYS,
            label = stringResource(R.string.limits_duration_days),
            onClick = { onUnitChange(UsageLimitBehaviorPolicy.RuleDurationUnit.DAYS) }
        )
        WebsiteDurationChip(
            selected = unit == UsageLimitBehaviorPolicy.RuleDurationUnit.WEEKS,
            label = stringResource(R.string.limits_duration_weeks),
            onClick = { onUnitChange(UsageLimitBehaviorPolicy.RuleDurationUnit.WEEKS) }
        )
        WebsiteDurationChip(
            selected = unit == UsageLimitBehaviorPolicy.RuleDurationUnit.MONTHS,
            label = stringResource(R.string.limits_duration_months),
            onClick = { onUnitChange(UsageLimitBehaviorPolicy.RuleDurationUnit.MONTHS) }
        )
    }
}

@Composable
private fun WebsiteLimitBehaviorScreen(
    behavior: String,
    onBehaviorChange: (String) -> Unit,
    minutes: Int,
    duration: Int,
    durationUnitLabel: String,
    behaviorLabel: String,
    canSave: Boolean,
    onBack: () -> Unit,
    onSave: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 20.dp)
        ) {
            Text(
                stringResource(R.string.limits_after_reaching_title),
                color = TextPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(18.dp))

            WebsiteBehaviorChoiceCard(
                selected = behavior == UsageLimitBehaviorPolicy.BLOCK_UNTIL_TOMORROW_PREFIX,
                title = stringResource(R.string.limits_block_tomorrow_option),
                description = stringResource(R.string.limits_block_tomorrow_desc),
                onClick = {
                    onBehaviorChange(UsageLimitBehaviorPolicy.BLOCK_UNTIL_TOMORROW_PREFIX)
                }
            )
            Spacer(Modifier.height(12.dp))
            WebsiteBehaviorChoiceCard(
                selected = behavior == UsageLimitBehaviorPolicy.PAUSE_30_PREFIX,
                title = stringResource(R.string.limits_pause_30_option),
                description = stringResource(R.string.limits_pause_30_desc),
                onClick = { onBehaviorChange(UsageLimitBehaviorPolicy.PAUSE_30_PREFIX) }
            )

            Spacer(Modifier.height(22.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkCard),
                border = BorderStroke(1.dp, CardBorder),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    stringResource(
                        R.string.limits_rule_summary,
                        minutes,
                        behaviorLabel,
                        duration,
                        durationUnitLabel
                    ),
                    color = TextSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(16.dp)
                )
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
                onClick = onBack,
                modifier = Modifier
                    .weight(0.55f)
                    .height(50.dp),
                shape = CircleShape,
                border = BorderStroke(1.dp, CardBorder),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary)
            ) {
                Text(stringResource(R.string.action_back))
            }
            Button(
                enabled = canSave,
                onClick = onSave,
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

@Composable
private fun WebsiteDecisionBlock(
    title: String,
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
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(12.dp))
        content()
        if (showDivider) {
            Spacer(Modifier.height(18.dp))
            HorizontalDivider(color = CardBorder, thickness = 1.dp)
        }
    }
}

@Composable
private fun WebsiteDurationChip(
    selected: Boolean,
    label: String,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = AccentCyan.copy(alpha = 0.18f),
            selectedLabelColor = AccentCyan
        )
    )
}

@Composable
private fun WebsiteBehaviorChoiceCard(
    selected: Boolean,
    title: String,
    description: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) AccentCyan.copy(alpha = 0.12f) else DarkCard
        ),
        border = BorderStroke(1.dp, if (selected) AccentCyan else CardBorder),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Top
        ) {
            Surface(
                modifier = Modifier.size(24.dp),
                shape = CircleShape,
                color = if (selected) AccentCyan else Color.Transparent,
                border = BorderStroke(2.dp, if (selected) AccentCyan else TextHint)
            ) {
                if (selected) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Surface(
                            modifier = Modifier.size(8.dp),
                            shape = CircleShape,
                            color = AccentCyanInk
                        ) {}
                    }
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    color = if (selected) AccentCyan else TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    description,
                    color = TextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            }
        }
    }
}

@Composable
private fun WebsitePermissionWarning(visible: Boolean) {
    if (!visible) return
    Card(
        colors = CardDefaults.cardColors(containerColor = DangerRed.copy(alpha = 0.1f)),
        border = BorderStroke(1.dp, DangerRed.copy(alpha = 0.5f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 18.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(Icons.Outlined.Warning, contentDescription = null, tint = DangerRed)
            Text(
                stringResource(R.string.blocking_permissions_required_desc),
                color = DangerRed,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun websiteLimitFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = AccentCyan,
    unfocusedBorderColor = CardBorder,
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary
)
