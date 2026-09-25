package com.focusguard.ui.compose.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.focusguard.R
import com.focusguard.security.DeactivationCredentialManager
import com.focusguard.ui.compose.theme.AccentCyan
import com.focusguard.ui.compose.theme.CardBorder
import com.focusguard.ui.compose.theme.DangerRed
import com.focusguard.ui.compose.theme.DarkBg
import com.focusguard.ui.compose.theme.DarkCard
import com.focusguard.ui.compose.theme.TextHint
import com.focusguard.ui.compose.theme.TextPrimary
import com.focusguard.ui.compose.theme.TextSecondary

/**
 * Asks for the master credential and calls [onConfirmed] only once it verifies.
 *
 * Recovery remains available to callers by default. Sensitive flows that explicitly
 * require the configured master password can set [allowRecovery] to false.
 *
 * @param promptRes explains which action the credential is authorizing.
 */
@Composable
internal fun ConfirmMasterCredentialDialog(
    promptRes: Int,
    allowRecovery: Boolean = true,
    removalStyle: Boolean = false,
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
            DeactivationCredentialManager.VerificationResult.PASSWORD_ACCEPTED -> {
                onConfirmed()
            }

            DeactivationCredentialManager.VerificationResult.RECOVERY_ACCEPTED -> {
                if (allowRecovery) {
                    onConfirmed()
                } else {
                    errorMessage = wrongMessage
                }
            }

            DeactivationCredentialManager.VerificationResult.NOT_CONFIGURED ->
                errorMessage = notConfiguredMessage

            DeactivationCredentialManager.VerificationResult.REJECTED ->
                errorMessage = wrongMessage
        }
    }

    if (removalStyle) {
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .widthIn(max = 420.dp)
                    .fillMaxWidth()
                    .background(DarkCard, RoundedCornerShape(28.dp))
                    .border(BorderStroke(1.dp, CardBorder), RoundedCornerShape(28.dp))
                    .padding(24.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .background(AccentCyan.copy(alpha = 0.14f), CircleShape)
                                .border(
                                    BorderStroke(1.dp, AccentCyan.copy(alpha = 0.35f)),
                                    CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Outlined.Key,
                                contentDescription = null,
                                tint = AccentCyan,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                        Text(
                            stringResource(R.string.deactivation_password_title),
                            color = TextPrimary,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(promptRes),
                            color = TextSecondary,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Spacer(Modifier.height(20.dp))
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
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        isError = errorMessage != null,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentCyan,
                            unfocusedBorderColor = CardBorder,
                            focusedLabelColor = AccentCyan,
                            unfocusedLabelColor = TextHint,
                            cursorColor = AccentCyan,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        )
                    )
                    errorMessage?.let { message ->
                        Spacer(Modifier.height(8.dp))
                        Text(message, color = DangerRed, fontSize = 13.sp)
                    }

                    Spacer(Modifier.height(24.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f).height(48.dp),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, CardBorder)
                        ) {
                            Text(stringResource(R.string.cancel), color = TextSecondary)
                        }
                        Button(
                            onClick = { confirm() },
                            enabled = credential.isNotBlank(),
                            modifier = Modifier.weight(1f).height(48.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = DangerRed,
                                contentColor = Color.White,
                                disabledContainerColor = DangerRed.copy(alpha = 0.35f),
                                disabledContentColor = Color.White.copy(alpha = 0.6f)
                            )
                        ) {
                            Text(
                                stringResource(R.string.sessions_confirm),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
        return
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
