package com.focusguard.ui.compose.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.R
import com.focusguard.security.AuthManager
import com.focusguard.ui.compose.theme.AccentCyan
import com.focusguard.ui.compose.theme.DangerRed
import com.focusguard.ui.compose.theme.TextHint
import com.focusguard.ui.compose.theme.TextPrimary
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
    password: String,
    onPasswordChange: (String) -> Unit,
    days: String,
    onDaysChange: (String) -> Unit,
    onConfirmed: (Boolean) -> Unit
) {
    var agreement by remember { mutableStateOf("") }
    var passwordUnlock by remember(lockMode) { mutableStateOf(lockMode == "PASSWORD") }
    val yesLabel = stringResource(R.string.action_yes)
    val agreementMatches = agreement.trim().equals(yesLabel.trim(), ignoreCase = true)
    val passwordValid = password.length >= 4
    val daysValid = days.toLongOrNull()?.let { it > 0L } == true

    LaunchedEffect(passwordUnlock, password, agreement, days) {
        onConfirmed(if (passwordUnlock) passwordValid else agreementMatches && daysValid)
    }

    Column {
        Text(
            stringResource(R.string.limits_security_mode),
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
                stringResource(R.string.limits_security_mode_desc),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 13.sp
            )
            Text(
                stringResource(R.string.limits_security_mode_warning),
                color = DangerRed,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = days,
                onValueChange = { value ->
                    if (value.all(Char::isDigit)) onDaysChange(value)
                },
                label = { Text(stringResource(R.string.limits_security_days_label)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                isError = days.isNotEmpty() && !daysValid,
                colors = limitFieldColors()
            )

            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.limits_security_password_question),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 13.sp
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                SecurityChoice(
                    selected = !passwordUnlock,
                    label = stringResource(R.string.action_no),
                    onClick = {
                        passwordUnlock = false
                        onLockModeChange("TIME")
                    }
                )
                Spacer(Modifier.width(20.dp))
                SecurityChoice(
                    selected = passwordUnlock,
                    label = yesLabel,
                    onClick = {
                        passwordUnlock = true
                        onLockModeChange("PASSWORD")
                    }
                )
            }

            if (passwordUnlock) {
                OutlinedTextField(
                    value = password,
                    onValueChange = onPasswordChange,
                    label = { Text(stringResource(R.string.limits_security_password_label)) },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    isError = password.isNotEmpty() && !passwordValid,
                    supportingText = {
                        if (!passwordValid) Text("Use pelo menos 4 caracteres")
                    },
                    colors = limitFieldColors()
                )
            } else {
                Spacer(Modifier.height(12.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = DangerRed.copy(alpha = 0.1f)
                    ),
                    border = BorderStroke(1.dp, DangerRed.copy(alpha = 0.3f))
                ) {
                    Column(Modifier.padding(8.dp)) {
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

@Composable
private fun SecurityChoice(selected: Boolean, label: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(selectedColor = AccentCyan)
        )
        Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
    }
}

@Composable
private fun limitFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = AccentCyan,
    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
    focusedTextColor = MaterialTheme.colorScheme.onSurface
)

@Composable
fun AppLimitDialog(
    app: UsageLimitAppUi,
    permissionsMissing: Boolean,
    onDismiss: () -> Unit,
    onSave: (Int?, Boolean, String, String?, Long?) -> Unit
) {
    val editMode = app.currentLimitMinutes != null
    var hours by remember { mutableStateOf(if (editMode) "" else "") }
    var lockMode by remember { mutableStateOf(if (editMode) app.lockMode else "NONE") }
    var password by remember { mutableStateOf("") }
    var days by remember { mutableStateOf("") }
    var confirmed by remember { mutableStateOf(lockMode == "NONE") }
    var extensionDays by remember { mutableStateOf("") }

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
                if (editMode) {
                    LimitSummary(
                        label = stringResource(R.string.limits_daily_time_label),
                        minutes = app.currentLimitMinutes ?: 0,
                        lockUntil = app.lockUntilTimestamp
                    )
                    OutlinedTextField(
                        value = extensionDays,
                        onValueChange = { if (it.all(Char::isDigit)) extensionDays = it },
                        label = { Text(stringResource(R.string.limits_add_more_days)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        colors = limitFieldColors()
                    )
                } else {
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
                        colors = limitFieldColors()
                    )
                    Spacer(Modifier.height(20.dp))
                    LimitSecuritySection(
                        lockMode,
                        { lockMode = it },
                        password,
                        { password = it },
                        days,
                        { days = it },
                        { confirmed = it }
                    )
                }
            }
        },
        confirmButton = {
            val enteredMinutes = (hours.replace(',', '.').toDoubleOrNull()?.times(60))?.toInt() ?: 0
            val canSave = if (editMode) extensionDays.toLongOrNull()?.let { it > 0 } == true
            else enteredMinutes > 0 && (lockMode == "NONE" || confirmed)
            TextButton(
                enabled = canSave,
                onClick = {
                    if (editMode) {
                        val extraDays = extensionDays.toLongOrNull() ?: return@TextButton
                        val base = maxOf(app.lockUntilTimestamp ?: 0L, System.currentTimeMillis())
                        onSave(
                            app.currentLimitMinutes,
                            app.isEnabled,
                            "TIME",
                            app.lockPasswordHash,
                            base + TimeUnit.DAYS.toMillis(extraDays)
                        )
                    } else {
                        val storedPassword = password.takeIf { lockMode == "PASSWORD" }
                            ?.let(::hashLimitPassword)
                        val until = days.toLongOrNull()
                            ?.takeIf { lockMode == "TIME" && it > 0L }
                            ?.let { System.currentTimeMillis() + TimeUnit.DAYS.toMillis(it) }
                        onSave(enteredMinutes, true, lockMode, storedPassword, until)
                    }
                }
            ) {
                Text(
                    if (editMode) stringResource(R.string.limits_extend_btn)
                    else stringResource(R.string.save),
                    color = if (canSave) AccentCyan else TextHint
                )
            }
        },
        dismissButton = {
            if (editMode && (app.lockUntilTimestamp == null || app.lockUntilTimestamp <= System.currentTimeMillis())) {
                TextButton(onClick = { onSave(null, false, "NONE", null, null) }) {
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
fun AddWebsiteLimitDialog(
    permissionsMissing: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, Int, String, String?, Long?) -> Unit
) {
    var domain by remember { mutableStateOf("") }
    var hours by remember { mutableStateOf("") }
    var lockMode by remember { mutableStateOf("NONE") }
    var password by remember { mutableStateOf("") }
    var days by remember { mutableStateOf("") }
    var confirmed by remember { mutableStateOf(true) }

    val normalizedDomain = WebsiteBlocker.normalizeRule(domain)
    val domainValid = WebsiteBlocker.isValidRule(normalizedDomain)
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
                            Text(stringResource(R.string.website_rule_invalid))
                        } else {
                            Text(stringResource(R.string.website_rule_hint))
                        }
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
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(16.dp))
                LimitSecuritySection(
                    lockMode,
                    { lockMode = it },
                    password,
                    { password = it },
                    days,
                    { days = it },
                    { confirmed = it }
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    val storedPassword = password.takeIf { lockMode == "PASSWORD" }
                        ?.let(::hashLimitPassword)
                    val until = days.toLongOrNull()
                        ?.takeIf { lockMode == "TIME" && it > 0L }
                        ?.let { System.currentTimeMillis() + TimeUnit.DAYS.toMillis(it) }
                    onSave(normalizedDomain, minutes, lockMode, storedPassword, until)
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

@Composable
fun ConfirmLimitPasswordDialog(
    expectedHash: String,
    fallbackVerifier: (suspend (String) -> Boolean)? = null,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    var verifying by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.limits_confirm_password_title),
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column {
                Text(
                    stringResource(R.string.limits_confirm_password_desc),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        error = false
                    },
                    label = { Text(stringResource(R.string.sessions_password_hint)) },
                    visualTransformation = PasswordVisualTransformation(),
                    isError = error,
                    colors = limitFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = password.isNotBlank() && !verifying &&
                    (expectedHash.isNotBlank() || fallbackVerifier != null),
                onClick = {
                    scope.launch {
                        verifying = true
                        val valid = runCatching {
                            if (expectedHash.isNotBlank()) {
                                verifyLimitPassword(password, expectedHash)
                            } else {
                                fallbackVerifier?.invoke(password) == true
                            }
                        }.getOrDefault(false)
                        verifying = false
                        if (valid) onConfirm() else error = true
                    }
                }
            ) {
                Text(stringResource(R.string.sessions_confirm), color = AccentCyan)
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

private fun hashLimitPassword(password: String): String {
    val salt = AuthManager.generateSalt()
    return "$salt:${AuthManager.hashPasswordWithSalt(password, salt)}"
}

private fun verifyLimitPassword(password: String, stored: String): Boolean {
    return AuthManager.verifySerializedPassword(password, stored)
}

fun filteredApps(apps: List<UsageLimitAppUi>, query: String): List<UsageLimitAppUi> {
    if (query.isBlank()) return apps
    return apps.filter {
        it.appName.contains(query, ignoreCase = true) ||
            it.packageName.contains(query, ignoreCase = true)
    }
}
