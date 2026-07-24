package com.focusguard.ui.compose.screens

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.R
import com.focusguard.security.AccessibilityMaintenanceCredentialManager
import com.focusguard.security.AccessibilityProtectionGate

@Composable
internal fun AccessibilityUnlockDialog(
    onDismiss: () -> Unit,
    onManageCredential: () -> Unit,
    onUnlocked: () -> Unit
) {
    val context = LocalContext.current
    val credentialManager = remember(context) {
        AccessibilityMaintenanceCredentialManager(context)
    }
    var credential by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var automaticDateTimeEnabled by remember {
        mutableStateOf(AccessibilityProtectionGate.isAutomaticDateAndTimeEnabled(context))
    }
    var credentialConfigured by remember { mutableStateOf(credentialManager.hasCredential()) }

    val credentialRequiredMessage =
        stringResource(R.string.accessibility_maintenance_required)
    val invalidCredentialMessage =
        stringResource(R.string.accessibility_maintenance_invalid)
    val automaticTimeMessage =
        stringResource(R.string.accessibility_unlock_auto_time_required)
    val recoveryConsumedMessage =
        stringResource(R.string.accessibility_recovery_consumed_toast)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.accessibility_unlock_dialog_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.accessibility_unlock_dialog_description_maintenance),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = credential,
                    onValueChange = {
                        credential = it
                        errorMessage = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = {
                        Text(stringResource(R.string.accessibility_maintenance_credential_label))
                    },
                    singleLine = true,
                    isError = errorMessage != null,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                )
                errorMessage?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
                if (!automaticDateTimeEnabled) {
                    TextButton(
                        onClick = {
                            runCatching {
                                context.startActivity(Intent(Settings.ACTION_DATE_SETTINGS))
                            }
                        }
                    ) {
                        Text(stringResource(R.string.accessibility_unlock_open_date_settings))
                    }
                }
                TextButton(onClick = onManageCredential) {
                    Text(
                        if (credentialConfigured) {
                            stringResource(R.string.accessibility_maintenance_manage)
                        } else {
                            stringResource(R.string.accessibility_maintenance_create)
                        }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = credential.isNotBlank(),
                onClick = {
                    automaticDateTimeEnabled =
                        AccessibilityProtectionGate.isAutomaticDateAndTimeEnabled(context)
                    if (!automaticDateTimeEnabled) {
                        AccessibilityProtectionGate.revoke(context)
                        errorMessage = automaticTimeMessage
                        return@TextButton
                    }

                    credentialConfigured = credentialManager.hasCredential()
                    if (!credentialConfigured) {
                        errorMessage = credentialRequiredMessage
                        return@TextButton
                    }

                    when (credentialManager.verify(credential)) {
                        AccessibilityMaintenanceCredentialManager.VerificationResult.PASSWORD_ACCEPTED -> {
                            grantAccessibilityWindow(context, onUnlocked) {
                                automaticDateTimeEnabled = false
                                errorMessage = automaticTimeMessage
                            }
                        }
                        AccessibilityMaintenanceCredentialManager.VerificationResult.RECOVERY_ACCEPTED -> {
                            Toast.makeText(
                                context,
                                recoveryConsumedMessage,
                                Toast.LENGTH_LONG
                            ).show()
                            grantAccessibilityWindow(context, onUnlocked) {
                                automaticDateTimeEnabled = false
                                errorMessage = automaticTimeMessage
                            }
                        }
                        AccessibilityMaintenanceCredentialManager.VerificationResult.NOT_CONFIGURED -> {
                            credentialConfigured = false
                            errorMessage = credentialRequiredMessage
                        }
                        AccessibilityMaintenanceCredentialManager.VerificationResult.REJECTED -> {
                            errorMessage = invalidCredentialMessage
                        }
                    }
                }
            ) {
                Text(stringResource(R.string.accessibility_unlock_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.accessibility_unlock_cancel))
            }
        }
    )
}

@Composable
internal fun AccessibilityMaintenanceCredentialDialog(
    onDismiss: () -> Unit,
    onCredentialChanged: () -> Unit
) {
    val context = LocalContext.current
    val manager = remember(context) {
        AccessibilityMaintenanceCredentialManager(context)
    }
    var wasConfigured by remember { mutableStateOf(manager.hasCredential()) }
    var currentCredential by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var recoveryCode by remember { mutableStateOf<String?>(null) }

    val invalidCredentialMessage =
        stringResource(R.string.accessibility_maintenance_invalid)
    val passwordTooShortMessage = stringResource(
        R.string.accessibility_maintenance_password_too_short,
        AccessibilityMaintenanceCredentialManager.MIN_PASSWORD_LENGTH
    )
    val passwordsDifferMessage =
        stringResource(R.string.accessibility_maintenance_passwords_differ)

    AlertDialog(
        onDismissRequest = {
            if (recoveryCode == null) onDismiss()
        },
        title = {
            Text(
                if (recoveryCode != null) {
                    stringResource(R.string.accessibility_recovery_title)
                } else if (wasConfigured) {
                    stringResource(R.string.accessibility_maintenance_change_title)
                } else {
                    stringResource(R.string.accessibility_maintenance_create_title)
                }
            )
        },
        text = {
            if (recoveryCode != null) {
                Column {
                    Text(stringResource(R.string.accessibility_recovery_description))
                    Spacer(Modifier.height(16.dp))
                    SelectionContainer {
                        Text(
                            text = recoveryCode.orEmpty(),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.accessibility_recovery_once_warning),
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp
                    )
                }
            } else {
                Column {
                    Text(
                        text = stringResource(R.string.accessibility_maintenance_description),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (wasConfigured) {
                        Spacer(Modifier.height(12.dp))
                        SecureCredentialField(
                            value = currentCredential,
                            onValueChange = {
                                currentCredential = it
                                errorMessage = null
                            },
                            label = stringResource(
                                R.string.accessibility_maintenance_current_label
                            )
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    SecureCredentialField(
                        value = newPassword,
                        onValueChange = {
                            newPassword = it
                            errorMessage = null
                        },
                        label = stringResource(R.string.accessibility_maintenance_new_label)
                    )
                    Spacer(Modifier.height(8.dp))
                    SecureCredentialField(
                        value = confirmation,
                        onValueChange = {
                            confirmation = it
                            errorMessage = null
                        },
                        label = stringResource(R.string.accessibility_maintenance_confirm_label)
                    )
                    errorMessage?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = {
            if (recoveryCode != null) {
                TextButton(
                    onClick = {
                        onCredentialChanged()
                        onDismiss()
                    }
                ) {
                    Text(stringResource(R.string.accessibility_recovery_saved_action))
                }
            } else {
                TextButton(
                    enabled = newPassword.isNotBlank() && confirmation.isNotBlank(),
                    onClick = {
                        if (!AccessibilityMaintenanceCredentialManager.isPasswordValid(newPassword)) {
                            errorMessage = passwordTooShortMessage
                            return@TextButton
                        }
                        if (newPassword != confirmation) {
                            errorMessage = passwordsDifferMessage
                            return@TextButton
                        }

                        if (wasConfigured) {
                            when (manager.verify(currentCredential)) {
                                AccessibilityMaintenanceCredentialManager.VerificationResult.PASSWORD_ACCEPTED,
                                AccessibilityMaintenanceCredentialManager.VerificationResult.RECOVERY_ACCEPTED -> Unit
                                AccessibilityMaintenanceCredentialManager.VerificationResult.NOT_CONFIGURED -> {
                                    wasConfigured = false
                                }
                                AccessibilityMaintenanceCredentialManager.VerificationResult.REJECTED -> {
                                    errorMessage = invalidCredentialMessage
                                    return@TextButton
                                }
                            }
                        }

                        recoveryCode = runCatching {
                            manager.configure(newPassword)
                        }.getOrElse {
                            errorMessage = context.getString(
                                R.string.accessibility_maintenance_save_failed
                            )
                            null
                        }
                    }
                ) {
                    Text(stringResource(R.string.accessibility_maintenance_save_action))
                }
            }
        },
        dismissButton = {
            if (recoveryCode == null) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.accessibility_unlock_cancel))
                }
            }
        }
    )
}

@Composable
private fun SecureCredentialField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
    )
}

private fun grantAccessibilityWindow(
    context: android.content.Context,
    onUnlocked: () -> Unit,
    onAutomaticTimeRejected: () -> Unit
) {
    when (AccessibilityProtectionGate.requestTemporaryUnlock(context)) {
        AccessibilityProtectionGate.UnlockResult.UNLOCKED -> onUnlocked()
        AccessibilityProtectionGate.UnlockResult.AUTOMATIC_DATE_TIME_REQUIRED -> {
            onAutomaticTimeRejected()
        }
    }
}
