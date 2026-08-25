package com.focusguard.ui.compose.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.R
import com.focusguard.data.PredefinedWebsites
import com.focusguard.ui.compose.theme.AccentCyan
import com.focusguard.ui.compose.theme.DangerRed
import com.focusguard.ui.compose.theme.TextHint
import com.focusguard.ui.compose.theme.TextPrimary
import com.focusguard.utils.UsageLimitBehaviorPolicy
import com.focusguard.utils.WebsiteBlocker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch

@Composable
fun LimitSecuritySection(
    lockMode: String,
    onLockModeChange: (String) -> Unit,
    days: String,
    onDaysChange: (String) -> Unit,
    hasMasterCredential: Boolean,
    onConfigureMasterPassword: () -> Unit,
    onConfirmed: (Boolean) -> Unit
) {
    var agreement by remember { mutableStateOf("") }
    val normalizedMode = lockMode.uppercase(Locale.ROOT)
    val yesLabel = stringResource(R.string.action_yes)
    val agreementMatches = agreement.trim().equals(yesLabel.trim(), ignoreCase = true)
    val daysValid = days.toLongOrNull()?.let { it > 0L } == true

    LaunchedEffect(normalizedMode, hasMasterCredential, agreement, days) {
        onConfirmed(
            when (normalizedMode) {
                "NONE" -> true
                "PASSWORD" -> hasMasterCredential
                "TIME" -> agreementMatches && daysValid
                else -> false
            }
        )
    }

    Column {
        Text(
            stringResource(R.string.limits_block_behavior_title),
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 14.sp
        )
        Spacer(Modifier.height(8.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                    RoundedCornerShape(12.dp)
                )
                .padding(12.dp)
        ) {
            Text(
                stringResource(R.string.limits_block_behavior_description),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 13.sp
            )
            Spacer(Modifier.height(12.dp))

            SecurityChoice(
                selected = normalizedMode == "NONE",
                label = stringResource(R.string.limits_block_daily_no_password_option),
                description = stringResource(R.string.limits_block_daily_no_password_desc),
                onClick = { onLockModeChange("NONE") }
            )
            Spacer(Modifier.height(8.dp))
            SecurityChoice(
                selected = normalizedMode == "TIME",
                label = stringResource(R.string.limits_block_hardened_no_password_option),
                description = stringResource(R.string.limits_block_hardened_no_password_desc),
                onClick = { onLockModeChange("TIME") }
            )
            Spacer(Modifier.height(8.dp))
            SecurityChoice(
                selected = normalizedMode == "PASSWORD",
                label = stringResource(R.string.limits_block_password_option),
                description = stringResource(R.string.limits_block_password_desc),
                onClick = { onLockModeChange("PASSWORD") }
            )

            when (normalizedMode) {
                "PASSWORD" -> {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = AccentCyan.copy(alpha = 0.08f)
                        ),
                        border = BorderStroke(1.dp, AccentCyan.copy(alpha = 0.25f))
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                stringResource(
                                    if (hasMasterCredential) {
                                        R.string.limits_master_password_configured
                                    } else {
                                        R.string.limits_master_password_missing
                                    }
                                ),
                                color = if (hasMasterCredential) AccentCyan else DangerRed,
                                fontSize = 12.sp
                            )
                            TextButton(onClick = onConfigureMasterPassword) {
                                Text(
                                    stringResource(
                                        if (hasMasterCredential) {
                                            R.string.limits_master_password_change_action
                                        } else {
                                            R.string.master_credential_create_action
                                        }
                                    )
                                )
                            }
                        }
                    }
                }

                "TIME" -> {
                    OutlinedTextField(
                        value = days,
                        onValueChange = { value ->
                            if (value.all(Char::isDigit)) onDaysChange(value)
                        },
                        label = { Text(stringResource(R.string.limits_security_days_label)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        isError = days.isNotEmpty() && !daysValid,
                        colors = limitFieldColors()
                    )
                    Spacer(Modifier.height(12.dp))
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = DangerRed.copy(alpha = 0.1f)
                        ),
                        border = BorderStroke(1.dp, DangerRed.copy(alpha = 0.3f))
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            Text(
                                stringResource(R.string.limits_security_no_password_warning),
                                color = DangerRed,
                                fontSize = 11.sp
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = agreement,
                                onValueChange = { agreement = it },
                                placeholder = {
                                    Text(
                                        stringResource(R.string.limits_security_agree_placeholder),
                                        fontSize = 11.sp
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = if (agreementMatches) AccentCyan else DangerRed,
                                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                                    focusedTextColor = MaterialTheme.colorScheme.onSurface
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SecurityChoice(
    selected: Boolean,
    label: String,
    description: String,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(
                if (selected) AccentCyan.copy(alpha = 0.1f)
                else MaterialTheme.colorScheme.surface.copy(alpha = 0.35f),
                RoundedCornerShape(10.dp)
            )
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(selectedColor = AccentCyan)
        )
        Column(Modifier.weight(1f)) {
            Text(
                label,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                description,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun limitFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = AccentCyan,
    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
    focusedTextColor = MaterialTheme.colorScheme.onSurface
)

@Suppress("UNUSED_PARAMETER")
@Composable
fun AppLimitDialog(
    app: UsageLimitAppUi,
    permissionsMissing: Boolean,
    hasMasterCredential: Boolean,
    onConfigureMasterPassword: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (Int?, Boolean, String, String?, Long?) -> Unit
) {
    val editMode = app.currentLimitMinutes != null
    val now = System.currentTimeMillis()
    val remainingDays = app.lockUntilTimestamp
        ?.takeIf { it > now }
        ?.let { ((it - now + TimeUnit.DAYS.toMillis(1) - 1L) / TimeUnit.DAYS.toMillis(1)).toInt() }
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

    val enteredMinutes = dailyMinutes.toIntOrNull() ?: 0
    val enteredDuration = durationAmount.toIntOrNull() ?: 0
    val ruleEnd = UsageLimitBehaviorPolicy.calculateRuleEndMillis(
        nowMillis = now,
        amount = enteredDuration,
        unit = durationUnit
    )
    val canSave = enteredMinutes > 0 && enteredDuration > 0 && ruleEnd != null
    val pauseLabel = stringResource(R.string.limits_pause_30_option)
    val dailyBlockLabel = stringResource(R.string.limits_block_tomorrow_option)
    val daysLabel = stringResource(R.string.limits_duration_days)
    val weeksLabel = stringResource(R.string.limits_duration_weeks)
    val monthsLabel = stringResource(R.string.limits_duration_months)
    val selectedBehaviorLabel = if (behavior == UsageLimitBehaviorPolicy.PAUSE_30_PREFIX) {
        pauseLabel
    } else {
        dailyBlockLabel
    }
    val durationUnitLabel = when (durationUnit) {
        UsageLimitBehaviorPolicy.RuleDurationUnit.DAYS -> daysLabel
        UsageLimitBehaviorPolicy.RuleDurationUnit.WEEKS -> weeksLabel
        UsageLimitBehaviorPolicy.RuleDurationUnit.MONTHS -> monthsLabel
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (editMode) "Informações do limite: ${app.appName}"
                else "Definir limite para ${app.appName}",
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                PermissionWarning(permissionsMissing)

                Text(
                    stringResource(R.string.limits_daily_max_title),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = dailyMinutes,
                    onValueChange = { raw -> dailyMinutes = raw.filter(Char::isDigit).take(4) },
                    label = { Text(stringResource(R.string.limits_daily_max_minutes_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    isError = dailyMinutes.isNotEmpty() && enteredMinutes <= 0,
                    colors = limitFieldColors()
                )

                Spacer(Modifier.height(20.dp))
                Text(
                    stringResource(R.string.limits_after_reaching_title),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Spacer(Modifier.height(8.dp))
                SecurityChoice(
                    selected = behavior == UsageLimitBehaviorPolicy.PAUSE_30_PREFIX,
                    label = pauseLabel,
                    description = stringResource(R.string.limits_pause_30_desc),
                    onClick = { behavior = UsageLimitBehaviorPolicy.PAUSE_30_PREFIX }
                )
                Spacer(Modifier.height(8.dp))
                SecurityChoice(
                    selected = behavior == UsageLimitBehaviorPolicy.BLOCK_UNTIL_TOMORROW_PREFIX,
                    label = dailyBlockLabel,
                    description = stringResource(R.string.limits_block_tomorrow_desc),
                    onClick = { behavior = UsageLimitBehaviorPolicy.BLOCK_UNTIL_TOMORROW_PREFIX }
                )

                Spacer(Modifier.height(20.dp))
                Text(
                    stringResource(R.string.limits_rule_duration_title),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = durationAmount,
                    onValueChange = { raw -> durationAmount = raw.filter(Char::isDigit).take(3) },
                    label = { Text(stringResource(R.string.limits_duration_amount_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    isError = durationAmount.isNotEmpty() && enteredDuration <= 0,
                    colors = limitFieldColors()
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = durationUnit == UsageLimitBehaviorPolicy.RuleDurationUnit.DAYS,
                        onClick = { durationUnit = UsageLimitBehaviorPolicy.RuleDurationUnit.DAYS },
                        label = { Text(daysLabel) },
                        modifier = Modifier.weight(1f),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AccentCyan.copy(alpha = 0.20f),
                            selectedLabelColor = AccentCyan
                        )
                    )
                    FilterChip(
                        selected = durationUnit == UsageLimitBehaviorPolicy.RuleDurationUnit.WEEKS,
                        onClick = { durationUnit = UsageLimitBehaviorPolicy.RuleDurationUnit.WEEKS },
                        label = { Text(weeksLabel) },
                        modifier = Modifier.weight(1f),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AccentCyan.copy(alpha = 0.20f),
                            selectedLabelColor = AccentCyan
                        )
                    )
                    FilterChip(
                        selected = durationUnit == UsageLimitBehaviorPolicy.RuleDurationUnit.MONTHS,
                        onClick = { durationUnit = UsageLimitBehaviorPolicy.RuleDurationUnit.MONTHS },
                        label = { Text(monthsLabel) },
                        modifier = Modifier.weight(1f),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AccentCyan.copy(alpha = 0.20f),
                            selectedLabelColor = AccentCyan
                        )
                    )
                }

                if (editMode && app.lockUntilTimestamp?.let { it > now } == true) {
                    Spacer(Modifier.height(12.dp))
                    val formatted = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
                        .format(Date(requireNotNull(app.lockUntilTimestamp)))
                    Text(
                        stringResource(R.string.limits_rule_current_until, formatted),
                        color = TextHint,
                        fontSize = 11.sp
                    )
                }

                if (canSave) {
                    Spacer(Modifier.height(14.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = AccentCyan.copy(alpha = 0.08f)
                        ),
                        border = BorderStroke(1.dp, AccentCyan.copy(alpha = 0.22f)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = stringResource(
                                R.string.limits_rule_summary,
                                enteredMinutes,
                                selectedBehaviorLabel,
                                enteredDuration,
                                durationUnitLabel
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    val persistedMode = if (behavior == UsageLimitBehaviorPolicy.PAUSE_30_PREFIX) {
                        UsageLimitBehaviorPolicy.pauseModeFor(app.packageName)
                    } else {
                        UsageLimitBehaviorPolicy.blockUntilTomorrowModeFor(app.packageName)
                    }
                    onSave(enteredMinutes, true, persistedMode, null, ruleEnd)
                }
            ) {
                Text(stringResource(R.string.save), color = if (canSave) AccentCyan else TextHint)
            }
        },
        dismissButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (editMode) {
                    TextButton(onClick = { onSave(null, false, "NONE", null, null) }) {
                        Text(stringResource(R.string.sessions_remove_item), color = DangerRed)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.pomodoro_cancel_btn), color = TextHint)
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.surface
    )
}

@Composable
fun AddWebsiteLimitDialog(
    permissionsMissing: Boolean,
    hasMasterCredential: Boolean,
    onConfigureMasterPassword: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (String, Int, String, String?, Long?) -> Unit
) {
    var domain by remember { mutableStateOf("") }
    var hours by remember { mutableStateOf("") }
    var lockMode by remember { mutableStateOf("NONE") }
    var days by remember { mutableStateOf("") }
    var confirmed by remember { mutableStateOf(true) }

    // Um limite de uso conta tempo gasto num alvo, e uma palavra não é um alvo em
    // que se passa duas horas. Por isso aqui vale só domínio — extractDomain, e
    // não normalizeRule, que aceitaria a palavra solta como regra de keyword.
    val normalizedDomain = WebsiteBlocker.extractDomain(domain)
    val domainValid = normalizedDomain.isNotEmpty()
    val minutes = (hours.replace(',', '.').toDoubleOrNull()?.times(60))?.toInt() ?: 0
    val canSave = domainValid && minutes > 0 && (lockMode == "NONE" || confirmed)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.limits_add_site_btn),
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                PermissionWarning(permissionsMissing)
                OutlinedTextField(
                    value = domain,
                    onValueChange = { domain = it },
                    label = { Text(stringResource(R.string.limits_domain_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    isError = domain.isNotBlank() && !domainValid,
                    supportingText = {
                        if (domain.isNotBlank() && !domainValid) {
                            Text(stringResource(R.string.block_targets_site_invalid))
                        } else {
                            Text(stringResource(R.string.block_targets_site_helper))
                        }
                    }
                )
                Spacer(Modifier.height(8.dp))
                WebsitePresetChips(
                    selectedDomain = normalizedDomain,
                    onPick = { domain = it }
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = hours,
                    onValueChange = { value ->
                        if (value.isEmpty() || value.replace(',', '.').toDoubleOrNull() != null) {
                            hours = value
                        }
                    },
                    label = { Text(stringResource(R.string.limits_daily_hours_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(16.dp))
                LimitSecuritySection(
                    lockMode = lockMode,
                    onLockModeChange = { lockMode = it },
                    days = days,
                    onDaysChange = { days = it },
                    hasMasterCredential = hasMasterCredential,
                    onConfigureMasterPassword = onConfigureMasterPassword,
                    onConfirmed = { confirmed = it }
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    val until = days.toLongOrNull()
                        ?.takeIf { lockMode == "TIME" && it > 0L }
                        ?.let { System.currentTimeMillis() + TimeUnit.DAYS.toMillis(it) }
                    onSave(normalizedDomain, minutes, lockMode, null, until)
                }
            ) {
                Text(stringResource(R.string.save), color = if (canSave) AccentCyan else TextHint)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.pomodoro_cancel_btn), color = TextHint)
            }
        },
        containerColor = MaterialTheme.colorScheme.surface
    )
}

/**
 * One-tap shortcuts for the sites people most often limit.
 *
 * Typing a domain by hand still works and is the only way to reach anything off
 * this list, but "YouTube" is a name and `youtube.com` is a spelling — the chips
 * spare the user from having to know the second one.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WebsitePresetChips(
    selectedDomain: String,
    onPick: (String) -> Unit
) {
    Text(
        stringResource(R.string.limits_preset_sites),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold
    )
    Spacer(Modifier.height(6.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PredefinedWebsites.POPULAR.forEach { website ->
            val selected = WebsiteBlocker.normalizeRule(website.domain) == selectedDomain
            FilterChip(
                selected = selected,
                onClick = { onPick(website.domain) },
                label = { Text(website.name, fontSize = 12.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = AccentCyan.copy(alpha = 0.20f),
                    selectedLabelColor = AccentCyan
                )
            )
        }
    }
}

@Composable
fun EditWebsiteLimitDialog(
    site: WebsiteLimitUi,
    permissionsMissing: Boolean,
    onDismiss: () -> Unit,
    onSave: (Int, Boolean, String, String?, Long?) -> Unit
) {
    var extensionDays by remember { mutableStateOf("") }
    val extraDays = extensionDays.toLongOrNull() ?: 0L

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "Informações do limite: ${WebsiteBlocker.displayRule(site.domain)}",
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                PermissionWarning(permissionsMissing)
                LimitSummary(
                    label = stringResource(R.string.limits_daily_time_website_label),
                    minutes = site.dailyLimitMinutes ?: 0,
                    lockUntil = site.lockUntilTimestamp
                )
                OutlinedTextField(
                    value = extensionDays,
                    onValueChange = { if (it.all(Char::isDigit)) extensionDays = it },
                    label = { Text(stringResource(R.string.limits_add_more_days)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    colors = limitFieldColors()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = extraDays > 0L,
                onClick = {
                    val base = maxOf(site.lockUntilTimestamp ?: 0L, System.currentTimeMillis())
                    onSave(
                        site.dailyLimitMinutes ?: 0,
                        site.isEnabled,
                        "TIME",
                        site.lockPasswordHash,
                        base + TimeUnit.DAYS.toMillis(extraDays)
                    )
                }
            ) {
                Text(stringResource(R.string.limits_extend_btn), color = AccentCyan)
            }
        },
        dismissButton = {
            if (site.lockUntilTimestamp == null || site.lockUntilTimestamp <= System.currentTimeMillis()) {
                TextButton(onClick = { onSave(0, false, "NONE", null, null) }) {
                    Text(stringResource(R.string.sessions_remove_item), color = DangerRed)
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.pomodoro_cancel_btn), color = TextHint)
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.surface
    )
}

@Composable
private fun PermissionWarning(visible: Boolean) {
    if (!visible) return
    Card(
        colors = CardDefaults.cardColors(containerColor = DangerRed.copy(alpha = 0.1f)),
        border = BorderStroke(1.dp, DangerRed.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Warning, contentDescription = null, tint = DangerRed)
            Spacer(Modifier.width(12.dp))
            Text(
                "Conceda as permissões necessárias para o bloqueio funcionar.",
                color = DangerRed,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun LimitSummary(label: String, minutes: Int, lockUntil: Long?) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            Text(
                "$minutes min",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            if (lockUntil != null && lockUntil > System.currentTimeMillis()) {
                Spacer(Modifier.height(8.dp))
                val formatted = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
                    .format(Date(lockUntil))
                Text(
                    "Protegido até: $formatted",
                    color = DangerRed,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

// A senha do limite não é persistida na regra. PASSWORD sempre referencia a
// credencial única de DeactivationCredentialManager.

fun filteredApps(apps: List<UsageLimitAppUi>, query: String): List<UsageLimitAppUi> {
    if (query.isBlank()) return apps
    return apps.filter {
        it.appName.contains(query, ignoreCase = true) ||
            it.packageName.contains(query, ignoreCase = true)
    }
}
