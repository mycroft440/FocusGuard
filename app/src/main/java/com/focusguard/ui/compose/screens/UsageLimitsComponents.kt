package com.focusguard.ui.compose.screens

import com.focusguard.ui.compose.components.limits.*

import android.graphics.drawable.Drawable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.focusguard.R
import com.focusguard.ui.compose.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

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
    var agreeText by remember { mutableStateOf("") }
    val yesLabel = stringResource(R.string.action_yes)
    var allowPasswordUnlock by remember { mutableStateOf(lockMode == "PASSWORD") }

    Column {
        Text(stringResource(R.string.limits_security_mode), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
        Spacer(Modifier.height(8.dp))
        
        Column(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f), RoundedCornerShape(12.dp)).padding(12.dp)) {
            Text(stringResource(R.string.limits_security_mode_desc), color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
            Text(stringResource(R.string.limits_security_mode_warning), color = DangerRed, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            
            Spacer(Modifier.height(16.dp))
            
            OutlinedTextField(
                value = days,
                onValueChange = { if (it.all { c -> c.isDigit() }) onDaysChange(it) },
                label = { Text(stringResource(R.string.limits_security_days_label)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AccentCyan,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                    focusedTextColor = MaterialTheme.colorScheme.onSurface
                )
            )
            
            Spacer(Modifier.height(16.dp))
            
            Text(stringResource(R.string.limits_security_password_question), color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { 
                    allowPasswordUnlock = false
                    onLockModeChange("TIME")
                }) {
                    RadioButton(selected = !allowPasswordUnlock, onClick = { 
                        allowPasswordUnlock = false
                        onLockModeChange("TIME")
                    }, colors = RadioButtonDefaults.colors(selectedColor = AccentCyan))
                    Text(stringResource(R.string.action_no), color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
                }
                Spacer(Modifier.width(20.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { 
                    allowPasswordUnlock = true
                    onLockModeChange("PASSWORD")
                }) {
                    RadioButton(selected = allowPasswordUnlock, onClick = { 
                        allowPasswordUnlock = true
                        onLockModeChange("PASSWORD")
                    }, colors = RadioButtonDefaults.colors(selectedColor = AccentCyan))
                    Text(yesLabel, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
                }
            }

            if (allowPasswordUnlock) {
                OutlinedTextField(
                    value = password,
                    onValueChange = onPasswordChange,
                    label = { Text(stringResource(R.string.limits_security_password_label)) },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentCyan,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                        focusedTextColor = MaterialTheme.colorScheme.onSurface
                    )
                )
                LaunchedEffect(password) { onConfirmed(password.isNotEmpty()) }
            } else {
                Spacer(Modifier.height(12.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = DangerRed.copy(alpha = 0.1f)),
                    border = BorderStroke(1.dp, DangerRed.copy(alpha = 0.3f))
                ) {
                    Column(Modifier.padding(8.dp)) {
                        Text(stringResource(R.string.limits_security_no_password_warning), color = DangerRed, fontSize = 11.sp)
                        Text(stringResource(R.string.limits_security_agree_question), color = MaterialTheme.colorScheme.onSurface, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = agreeText,
                            onValueChange = { 
                                agreeText = it
                                onConfirmed(it.lowercase() == yesLabel)
                            },
                            placeholder = { Text(stringResource(R.string.limits_security_agree_placeholder), fontSize = 11.sp) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = if(agreeText.lowercase() == yesLabel) AccentCyan else DangerRed,
                                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                                focusedTextColor = MaterialTheme.colorScheme.onSurface
                            ),
                            singleLine = true
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AppLimitDialog(
    app: UsageLimitAppUi, 
    permissionsMissing: Boolean, 
    onDismiss: () -> Unit, 
    onSave: (Int?, Boolean, String, String?, Long?) -> Unit
) {
    val isEditMode = app.currentLimitMinutes != null
    var hoursText by remember { mutableStateOf(if(isEditMode) (app.currentLimitMinutes!! / 60f).toString() else "") }
    var lockMode by remember { mutableStateOf(app.lockMode) }
    var password by remember { mutableStateOf("") }
    var days by remember { mutableStateOf("") }
    var isConfirmed by remember { mutableStateOf(!isEditMode && lockMode == "NONE") }
    
    var extensionDays by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { 
            Text(
                if (isEditMode) "Informações do Limite: ${app.appName}" 
                else "Definir tempo máximo para ${app.appName}", 
                color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 18.sp
            ) 
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (permissionsMissing) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = DangerRed.copy(alpha = 0.1f)),
                        border = BorderStroke(1.dp, DangerRed.copy(alpha = 0.5f)),
                        modifier = Modifier.padding(bottom = 16.dp).fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = DangerRed, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "Permissões necessárias para bloqueio",
                                color = DangerRed, fontSize = 12.sp, fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                if (isEditMode) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(stringResource(R.string.limits_daily_time_label), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                            Text(stringResource(R.string.limits_minutes_count, (app.currentLimitMinutes ?: 0).toString()), color = MaterialTheme.colorScheme.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            
                            Spacer(Modifier.height(12.dp))
                            
                            Text(stringResource(R.string.limits_security_label), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                            val securityText = when(app.lockMode) {
                                "TIME" -> stringResource(R.string.limits_security_type_time)
                                "PASSWORD" -> stringResource(R.string.limits_security_type_password)
                                else -> "Sem proteção extra"
                            }
                            Text(securityText, color = if(app.lockMode != "NONE") AccentCyan else MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)

                            if (app.lockUntilTimestamp != null && app.lockUntilTimestamp > System.currentTimeMillis()) {
                                val date = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm").format(java.util.Date(app.lockUntilTimestamp))
                                Spacer(Modifier.height(8.dp))
                                Text("Inviolável até: $date", color = DangerRed, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Text(stringResource(R.string.limits_add_more_days), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = extensionDays,
                        onValueChange = { if (it.all { c -> c.isDigit() }) extensionDays = it },
                        placeholder = { Text(stringResource(R.string.limits_days_placeholder)) },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentCyan,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                            focusedTextColor = MaterialTheme.colorScheme.onSurface
                        ),
                        suffix = { Text(stringResource(R.string.limits_days_suffix)) }
                    )
                    Text(stringResource(R.string.limits_add_days_warning), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                } else {
                    OutlinedTextField(
                        value = hoursText,
                        onValueChange = { if (it.isEmpty() || it.replace(",", ".").toDoubleOrNull() != null || it == ".") hoursText = it },
                        label = { Text(stringResource(R.string.limits_daily_hours_label)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentCyan,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                            focusedTextColor = MaterialTheme.colorScheme.onSurface
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    
                    Spacer(Modifier.height(24.dp))
                    LimitSecuritySection(lockMode, { lockMode = it }, password, { password = it }, days, { days = it }, { isConfirmed = it })
                }
            }
        },
        confirmButton = { 
            TextButton(
                enabled = !isEditMode && (isConfirmed || lockMode == "NONE") || isEditMode,
                onClick = { 
                    if (isEditMode) {
                        val extraDays = extensionDays.toLongOrNull() ?: 0L
                        if (extraDays > 0) {
                            val currentUntil = app.lockUntilTimestamp ?: System.currentTimeMillis()
                            val base = if (currentUntil < System.currentTimeMillis()) System.currentTimeMillis() else currentUntil
                            val newUntil = base + TimeUnit.DAYS.toMillis(extraDays)
                            onSave(app.currentLimitMinutes, app.isEnabled, "TIME", app.lockPasswordHash, newUntil)
                        } else {
                            onDismiss()
                        }
                    } else {
                        val hours = hoursText.replace(",", ".").toDoubleOrNull() ?: 0.0
                        val minutes = (hours * 60).toInt()
                        val hash = if (lockMode == "PASSWORD" && password.isNotEmpty()) {
                            com.focusguard.security.AuthManager.hashPasswordLegacy(password)
                        } else null
                        
                        val until = if (lockMode == "TIME" && days.isNotEmpty()) System.currentTimeMillis() + TimeUnit.DAYS.toMillis(days.toLong()) else null
                        onSave(if(minutes > 0) minutes else null, true, lockMode, hash, until) 
                    }
                }
            ) { 
                Text(
                    if(isEditMode) stringResource(R.string.limits_extend_btn) else stringResource(R.string.save), 
                    color = if(!isEditMode && (isConfirmed || lockMode == "NONE") || isEditMode) AccentCyan else MaterialTheme.colorScheme.onSurfaceVariant
                ) 
            } 
        },
        dismissButton = { 
            if (isEditMode && (app.lockUntilTimestamp == null || System.currentTimeMillis() > app.lockUntilTimestamp)) {
                TextButton(onClick = { onSave(null, false, "NONE", null, null) }) {
                    Text(stringResource(R.string.sessions_remove_item), color = DangerRed)
                }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.pomodoro_cancel_btn), color = MaterialTheme.colorScheme.onSurfaceVariant) } 
            }
        },
        containerColor = MaterialTheme.colorScheme.surface
    )
}

@Composable
fun AddWebsiteLimitDialog(permissionsMissing: Boolean, onDismiss: () -> Unit, onSave: (String, Int, String, String?, Long?) -> Unit) {
    var domain by remember { mutableStateOf("") }
    var hoursText by remember { mutableStateOf("") }
    var lockMode by remember { mutableStateOf("NONE") }
    var password by remember { mutableStateOf("") }
    var days by remember { mutableStateOf("") }
    var isConfirmed by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.limits_add_site_btn), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(domain, { domain = it }, label = { Text(stringResource(R.string.limits_domain_label)) }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(hoursText, { if (it.isEmpty() || it.replace(",", ".").toDoubleOrNull() != null) hoursText = it }, label = { Text(stringResource(R.string.limits_daily_hours_label)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(16.dp))
                LimitSecuritySection(lockMode, { lockMode = it }, password, { password = it }, days, { days = it }, { isConfirmed = it })
            }
        },
        confirmButton = { 
            TextButton(
                enabled = isConfirmed || lockMode == "NONE",
                onClick = { 
                    if (domain.isNotBlank()) {
                        val hours = hoursText.replace(",", ".").toDoubleOrNull() ?: 0.0
                        val minutes = (hours * 60).toInt()
                        val hash = if (lockMode == "PASSWORD" && password.isNotEmpty()) com.focusguard.security.AuthManager.hashPasswordLegacy(password) else null
                        val until = if (lockMode == "TIME" && days.isNotEmpty()) System.currentTimeMillis() + TimeUnit.DAYS.toMillis(days.toLong()) else null
                        onSave(domain, minutes, lockMode, hash, until)
                    }
                }
            ) { Text(stringResource(R.string.save), color = AccentCyan) } 
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.pomodoro_cancel_btn), color = TextHint) } },
        containerColor = MaterialTheme.colorScheme.surface
    )
}

@Composable
fun EditWebsiteLimitDialog(site: WebsiteLimitUi, permissionsMissing: Boolean, onDismiss: () -> Unit, onSave: (Int, Boolean, String, String?, Long?) -> Unit) {
    var extensionDays by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Informações do Limite: ${site.domain}", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.limits_daily_time_website_label), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        Text(stringResource(R.string.limits_minutes_count, (site.dailyLimitMinutes ?: 0).toString()), color = MaterialTheme.colorScheme.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        
                        if (site.lockUntilTimestamp != null && site.lockUntilTimestamp > System.currentTimeMillis()) {
                            val date = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm").format(java.util.Date(site.lockUntilTimestamp))
                            Spacer(Modifier.height(12.dp))
                            Text("Inviolável até: $date", color = DangerRed, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Text(stringResource(R.string.limits_add_more_days), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = extensionDays,
                    onValueChange = { if (it.all { c -> c.isDigit() }) extensionDays = it },
                    placeholder = { Text(stringResource(R.string.limits_days_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentCyan,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                        focusedTextColor = MaterialTheme.colorScheme.onSurface
                    ),
                    suffix = { Text(stringResource(R.string.limits_days_suffix)) }
                )
            }
        },
        confirmButton = { 
            TextButton(
                onClick = { 
                    val extraDays = extensionDays.toLongOrNull() ?: 0L
                    if (extraDays > 0) {
                        val currentUntil = site.lockUntilTimestamp ?: System.currentTimeMillis()
                        val base = if (currentUntil < System.currentTimeMillis()) System.currentTimeMillis() else currentUntil
                        val newUntil = base + TimeUnit.DAYS.toMillis(extraDays)
                        onSave(site.dailyLimitMinutes, site.isEnabled, "TIME", site.lockPasswordHash, newUntil) 
                    } else {
                        onDismiss()
                    }
                }
            ) { Text(stringResource(R.string.limits_extend_btn), color = AccentCyan) } 
        },
        dismissButton = { 
             if (site.lockUntilTimestamp == null || System.currentTimeMillis() > site.lockUntilTimestamp) {
                TextButton(onClick = { onSave(0, false, "NONE", null, null) }) {
                    Text(stringResource(R.string.sessions_remove_item), color = DangerRed)
                }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.pomodoro_cancel_btn), color = MaterialTheme.colorScheme.onSurfaceVariant) } 
            }
        },
        containerColor = MaterialTheme.colorScheme.surface
    )
}

@Composable
fun ConfirmLimitPasswordDialog(expectedHash: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.limits_confirm_password_title), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(stringResource(R.string.limits_confirm_password_desc), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; error = false },
                    label = { Text(stringResource(R.string.sessions_password_hint), color = if (error) DangerRed else MaterialTheme.colorScheme.onSurfaceVariant) },
                    visualTransformation = PasswordVisualTransformation(),
                    isError = error,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AccentCyan, unfocusedTextColor = TextPrimary, focusedTextColor = TextPrimary),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val hash = com.focusguard.security.AuthManager.hashPasswordLegacy(password)
                if (expectedHash.isEmpty() || hash == expectedHash) onConfirm() else error = true
            }) { Text(stringResource(R.string.sessions_confirm), color = AccentCyan) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.pomodoro_cancel_btn), color = TextHint) } },
        containerColor = MaterialTheme.colorScheme.surface
    )
}

fun filteredApps(apps: List<UsageLimitAppUi>, query: String): List<UsageLimitAppUi> {
    return if (query.isBlank()) apps
    else apps.filter { it.appName.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true) }
}
