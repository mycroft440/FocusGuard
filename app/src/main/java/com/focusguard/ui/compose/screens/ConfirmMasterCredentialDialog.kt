package com.focusguard.ui.compose.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.focusguard.R
import com.focusguard.security.DeactivationCredentialManager
import com.focusguard.ui.compose.theme.AccentCyan
import com.focusguard.ui.compose.theme.DangerRed
import com.focusguard.ui.compose.theme.TextSecondary

/**
 * Asks for the master credential and calls [onConfirmed] only once it verifies.
 *
 * The recovery code is accepted too, since [DeactivationCredentialManager.verify]
 * treats it as an equivalent proof — a user who lost the password must still be
 * able to manage their own limits.
 *
 * @param promptRes explains which action the credential is authorizing.
 */
@Composable
internal fun ConfirmMasterCredentialDialog(
    promptRes: Int,
    onDismiss: () -> Unit,
    onConfirmed: () -> Unit
) {
    val context = LocalContext.current
    val credentialManager = remember(context) { DeactivationCredentialManager(context) }

    var credential by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val wrongMessage = stringResource(R.string.master_credential_wrong)
    val notConfiguredMessage = stringResource(R.string.master_credential_not_configured)

    fun confirm() {
        when (credentialManager.verify(credential)) {
            DeactivationCredentialManager.VerificationResult.PASSWORD_ACCEPTED,
            DeactivationCredentialManager.VerificationResult.RECOVERY_ACCEPTED -> {
                onConfirmed()
            }

            DeactivationCredentialManager.VerificationResult.NOT_CONFIGURED ->
                errorMessage = notConfiguredMessage

            DeactivationCredentialManager.VerificationResult.REJECTED ->
                errorMessage = wrongMessage
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.deactivation_password_title)) },
        text = {
            Column {
                Text(stringResource(promptRes), color = TextSecondary)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = credential,
                    onValueChange = {
                        credential = it
                        errorMessage = null
                    },
                    label = {
                        Text(stringResource(R.string.device_owner_maintenance_password_label))
                    },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
                errorMessage?.let { message ->
                    Spacer(Modifier.height(8.dp))
                    Text(message, color = DangerRed)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { confirm() }, enabled = credential.isNotBlank()) {
                Text(stringResource(R.string.sessions_confirm), color = AccentCyan)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel), color = TextSecondary)
            }
        }
    )
}
