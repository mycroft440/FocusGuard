package com.focusguard.ui.compose.screens

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
import com.focusguard.security.DeactivationCredentialManager

@Composable
internal fun DeactivationCredentialDialog(
    manager: DeactivationCredentialManager,
    managementLocked: Boolean,
    onDismiss: () -> Unit,
    onCredentialChanged: () -> Unit
) {
    val context = LocalContext.current
    var wasConfigured by remember { mutableStateOf(manager.hasCredential()) }
    var currentCredential by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var recoveryCode by remember { mutableStateOf<String?>(null) }

    val invalidCredentialMessage = stringResource(R.string.deactivation_password_invalid)
    val passwordTooShortMessage = stringResource(
        R.string.deactivation_password_too_short,
        DeactivationCredentialManager.MIN_PASSWORD_LENGTH
    )
    val passwordsDifferMessage = stringResource(R.string.deactivation_passwords_differ)

    AlertDialog(
        onDismissRequest = {
            if (recoveryCode == null) onDismiss()
        },
        title = {
            Text(
                when {
                    managementLocked -> stringResource(R.string.deactivation_password_title)
                    recoveryCode != null -> stringResource(R.string.deactivation_recovery_title)
                    wasConfigured -> stringResource(R.string.deactivation_password_change_title)
                    else -> stringResource(R.string.deactivation_password_create_title)
                }
            )
        },
        text = {
            when {
                managementLocked -> {
                    Text(
                        text = stringResource(R.string.deactivation_password_locked_description),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                recoveryCode != null -> {
                    Column {
                        Text(stringResource(R.string.deactivation_recovery_description))
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
                            text = stringResource(R.string.deactivation_recovery_warning),
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp
                        )
                    }
                }
                else -> {
                    Column {
                        Text(
                            text = stringResource(R.string.deactivation_password_description),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (wasConfigured) {
                            Spacer(Modifier.height(12.dp))
                            DeactivationPasswordField(
                                value = currentCredential,
                                onValueChange = {
                                    currentCredential = it
                                    errorMessage = null
                                },
                                label = stringResource(R.string.deactivation_password_current_label)
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        DeactivationPasswordField(
                            value = newPassword,
                            onValueChange = {
                                newPassword = it
                                errorMessage = null
                            },
                            label = stringResource(R.string.deactivation_password_new_label)
                        )
                        Spacer(Modifier.height(8.dp))
                        DeactivationPasswordField(
                            value = confirmation,
                            onValueChange = {
                                confirmation = it
                                errorMessage = null
                            },
                            label = stringResource(R.string.deactivation_password_confirm_label)
                        )
                        errorMessage?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            when {
                managementLocked -> {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.status_close))
                    }
                }
                recoveryCode != null -> {
                    TextButton(
                        onClick = {
                            onCredentialChanged()
                            onDismiss()
                        }
                    ) {
                        Text(stringResource(R.string.deactivation_recovery_saved_action))
                    }
                }
                else -> {
                    TextButton(
                        enabled = newPassword.isNotBlank() && confirmation.isNotBlank(),
                        onClick = {
                            if (!DeactivationCredentialManager.isPasswordValid(newPassword)) {
                                errorMessage = passwordTooShortMessage
                                return@TextButton
                            }
                            if (newPassword != confirmation) {
                                errorMessage = passwordsDifferMessage
                                return@TextButton
                            }
                            if (wasConfigured) {
                                when (manager.verify(currentCredential)) {
                                    DeactivationCredentialManager.VerificationResult.PASSWORD_ACCEPTED,
                                    DeactivationCredentialManager.VerificationResult.RECOVERY_ACCEPTED -> Unit
                                    DeactivationCredentialManager.VerificationResult.NOT_CONFIGURED -> {
                                        wasConfigured = false
                                    }
                                    DeactivationCredentialManager.VerificationResult.REJECTED -> {
                                        errorMessage = invalidCredentialMessage
                                        return@TextButton
                                    }
                                }
                            }

                            recoveryCode = runCatching {
                                manager.configure(newPassword)
                            }.getOrElse {
                                errorMessage = context.getString(
                                    R.string.deactivation_password_save_failed
                                )
                                null
                            }
                        }
                    ) {
                        Text(stringResource(R.string.deactivation_password_save_action))
                    }
                }
            }
        },
        dismissButton = {
            if (!managementLocked && recoveryCode == null) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    )
}

@Composable
private fun DeactivationPasswordField(
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
