package com.focusguard.ui.compose.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.focusguard.R
import com.focusguard.ui.compose.theme.AccentCyan
import com.focusguard.ui.compose.theme.DangerRed
import com.focusguard.ui.compose.theme.TextHint
import com.focusguard.utils.WebsiteBlocker
import java.util.concurrent.TimeUnit

/**
 * Shared editor for website and keyword usage limits.
 *
 * Website rules are kept as domains. Keyword rules are persisted with the
 * internal `keyword:` prefix so WebsiteUsageLimitPolicy can aggregate time from
 * every matching domain without changing the database schema.
 */
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
    val initialValue = remember(initialRule, keywordMode) {
        val normalized = initialRule?.let(WebsiteBlocker::normalizeRule).orEmpty()
        if (keywordMode && WebsiteBlocker.isKeywordRule(normalized)) {
            WebsiteBlocker.displayRule(normalized).removePrefix("*").removeSuffix("*")
        } else {
            normalized
        }
    }

    var target by remember(initialRule, keywordMode) { mutableStateOf(initialValue) }
    var hours by remember(initialRule, keywordMode) { mutableStateOf("") }
    var lockMode by remember(initialRule, keywordMode) { mutableStateOf("NONE") }
    var days by remember(initialRule, keywordMode) { mutableStateOf("") }
    var confirmed by remember(initialRule, keywordMode) { mutableStateOf(true) }

    val normalizedRule = if (keywordMode) {
        WebsiteBlocker.normalizeRule("keyword:$target")
            .takeIf(WebsiteBlocker::isKeywordRule)
            .orEmpty()
    } else {
        WebsiteBlocker.extractDomain(target)
    }
    val targetValid = normalizedRule.isNotEmpty()
    val minutes = (hours.replace(',', '.').toDoubleOrNull()?.times(60.0))?.toInt() ?: 0
    val canSave = targetValid && minutes > 0 && (lockMode == "NONE" || confirmed)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (keywordMode) R.string.limits_add_keyword_btn
                    else R.string.limits_add_site_btn
                ),
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (permissionsMissing) {
                    Text(
                        stringResource(R.string.blocking_permissions_required_desc),
                        color = DangerRed
                    )
                    Spacer(Modifier.height(10.dp))
                }

                OutlinedTextField(
                    value = target,
                    onValueChange = { target = it },
                    label = {
                        Text(
                            stringResource(
                                if (keywordMode) R.string.limits_keyword_label
                                else R.string.limits_domain_label
                            )
                        )
                    },
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
                    }
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
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
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
                    onSave(normalizedRule, minutes, lockMode, null, until)
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
