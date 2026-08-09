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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.R
import com.focusguard.data.PredefinedWebsites
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
    val normalizedMode = lockMode.uppercase(Locale.ROOT)
    val yesLabel = stringResource(R.string.action_yes)
    val agreementMatches = agreement.trim().equals(yesLabel.trim(), ignoreCase = true)
    val passwordValid = password.length >= 4
    val daysValid = days.toLongOrNull()?.let { it > 0L } == true

    LaunchedEffect(normalizedMode, password, agreement, days) {
        onConfirmed(
            when (normalizedMode) {
                "NONE" -> true
                "PASSWORD" -> passwordValid
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
                    OutlinedTextField(
                        value = password,
                        onValueChange = onPasswordChange,
                        label = { Text(stringResource(R.string.limits_security_password_label)) },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        isError = password.isNotEmpty() && !passwordValid,
                        supportingText = {
                            if (!passwordValid) {
                                Text(stringResource(R.string.limits_password_minimum))
                            }
                        },
                        colors = limitFieldColors()
                    )
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

// ConfirmLimitPasswordDialog foi removido: alterar ou remover um limite passou a
// exigir a senha mestre (ConfirmMasterCredentialDialog). A senha do próprio
// limite continua valendo para abrir o app bloqueado, não para editar a regra.

private fun hashLimitPassword(password: String): String {
    val salt = AuthManager.generateSalt()
    return "$salt:${AuthManager.hashPasswordWithSalt(password, salt)}"
}

fun filteredApps(apps: List<UsageLimitAppUi>, query: String): List<UsageLimitAppUi> {
    if (query.isBlank()) return apps
    return apps.filter {
        it.appName.contains(query, ignoreCase = true) ||
            it.packageName.contains(query, ignoreCase = true)
    }
}
