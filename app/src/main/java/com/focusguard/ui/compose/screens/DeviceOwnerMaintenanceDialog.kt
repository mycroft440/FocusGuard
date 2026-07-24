package com.focusguard.ui.compose.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.R
import com.focusguard.admin.DeviceOwnerManager
import com.focusguard.security.DeactivationCredentialManager
import com.focusguard.security.DeviceOwnerMaintenanceGate
import kotlinx.coroutines.delay
import kotlin.math.ceil

@Composable
internal fun DeviceOwnerMaintenanceDialog(
    onDismiss: () -> Unit,
    onStateChanged: () -> Unit
) {
    val context = LocalContext.current
    val manager = remember(context) { DeviceOwnerManager.getInstance(context) }
    val credentialManager = remember(context) { DeactivationCredentialManager(context) }

    var credential by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showRevokeConfirmation by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    var clockTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000L)
            clockTick++
        }
    }

    val isDeviceOwner = manager.isDeviceOwnerActive()
    val remainingMillis = remember(revision, clockTick, isDeviceOwner) {
        manager.maintenanceRemainingMillis()
    }
    val maintenanceActive = remainingMillis > 0L
    val remainingMinutes = ceil(remainingMillis / 60_000.0).toInt().coerceAtLeast(1)
    val credentialConfigured = credentialManager.hasCredential()

    val invalidCredential = stringResource(R.string.device_owner_maintenance_invalid_credential)
    val missingCredential = stringResource(R.string.device_owner_maintenance_credential_missing)
    val automaticTimeRequired = stringResource(R.string.device_owner_maintenance_auto_time_required)
    val outsideWindow = stringResource(R.string.device_owner_maintenance_outside_window)
    val openedMessage = stringResource(R.string.device_owner_maintenance_opened)
    val closedMessage = stringResource(R.string.device_owner_maintenance_closed)

    fun handleUnlockResult(result: DeviceOwnerMaintenanceGate.UnlockResult) {
        errorMessage = when (result) {
            DeviceOwnerMaintenanceGate.UnlockResult.UNLOCKED -> {
                credential = ""
                revision++
                onStateChanged()
                Toast.makeText(context, openedMessage, Toast.LENGTH_SHORT).show()
                null
            }
            DeviceOwnerMaintenanceGate.UnlockResult.AUTOMATIC_DATE_TIME_REQUIRED ->
                automaticTimeRequired
            DeviceOwnerMaintenanceGate.UnlockResult.CREDENTIAL_NOT_CONFIGURED ->
                missingCredential
            DeviceOwnerMaintenanceGate.UnlockResult.INVALID_CREDENTIAL ->
                invalidCredential
            DeviceOwnerMaintenanceGate.UnlockResult.OUTSIDE_MONTHLY_WINDOW ->
                outsideWindow
        }
    }

    if (showRevokeConfirmation) {
        AlertDialog(
            onDismissRequest = { showRevokeConfirmation = false },
            title = { Text(stringResource(R.string.device_owner_maintenance_revoke_title)) },
            text = { Text(stringResource(R.string.device_owner_maintenance_revoke_description)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRevokeConfirmation = false
                        manager.renounceDeviceOwner()
                        revision++
                        onStateChanged()
                        onDismiss()
                    }
                ) {
                    Text(
                        stringResource(R.string.device_owner_maintenance_revoke),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showRevokeConfirmation = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.device_owner_maintenance_title)) },
        text = {
            Column {
                when {
                    !isDeviceOwner -> {
                        Text(
                            text = stringResource(R.string.device_owner_maintenance_owner_required),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    maintenanceActive -> {
                        Text(
                            text = stringResource(
                                R.string.device_owner_maintenance_active_subtitle,
                                remainingMinutes
                            ),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(
                                R.string.device_owner_maintenance_active_description
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                manager.endMaintenanceNow()
                                revision++
                                onStateChanged()
                                Toast.makeText(
                                    context,
                                    closedMessage,
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        ) {
                            Text(stringResource(R.string.device_owner_maintenance_end_now))
                        }
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { showRevokeConfirmation = true }
                        ) {
                            Text(
                                stringResource(R.string.device_owner_maintenance_revoke),
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    else -> {
                        Text(
                            text = stringResource(R.string.device_owner_maintenance_description),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        if (credentialConfigured) {
                            OutlinedTextField(
                                value = credential,
                                onValueChange = {
                                    credential = it
                                    errorMessage = null
                                },
                                modifier = Modifier.fillMaxWidth(),
                                label = {
                                    Text(
                                        stringResource(
                                            R.string.device_owner_maintenance_password_label
                                        )
                                    )
                                },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Password
                                )
                            )
                            Spacer(Modifier.height(4.dp))
                            TextButton(
                                modifier = Modifier.fillMaxWidth(),
                                enabled = credential.isNotBlank(),
                                onClick = {
                                    handleUnlockResult(
                                        manager.requestMaintenanceWithCredential(credential)
                                    )
                                }
                            ) {
                                Text(
                                    stringResource(
                                        R.string.device_owner_maintenance_open_password
                                    )
                                )
                            }
                        } else {
                            Text(
                                text = missingCredential,
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 12.sp
                            )
                        }

                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(
                                R.string.device_owner_maintenance_monthly_schedule
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp
                        )
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                handleUnlockResult(manager.requestMonthlyMaintenance())
                            }
                        ) {
                            Text(
                                stringResource(
                                    R.string.device_owner_maintenance_open_monthly
                                )
                            )
                        }

                        errorMessage?.let { message ->
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = message,
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.status_close))
            }
        }
    )
}
