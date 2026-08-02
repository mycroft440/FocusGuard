package com.focusguard.ui.compose.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.R
import com.focusguard.admin.DeviceOwnerManager
import com.focusguard.security.DeviceOwnerSetupGuide

@Composable
internal fun DeviceOwnerSetupGuideDialog(
    onDismiss: () -> Unit,
    onStateChanged: () -> Unit
) {
    val context = LocalContext.current
    val manager = remember(context) { DeviceOwnerManager.getInstance(context) }
    var revision by remember { mutableIntStateOf(0) }
    val deviceOwnerActive = remember(revision) { manager.isDeviceOwnerActive() }
    val deviceAdminActive = remember(revision) { manager.isDeviceAdminActive() }
    val command = remember(context.packageName) {
        DeviceOwnerSetupGuide.buildAdbCommand(context.packageName)
    }
    val copiedMessage = stringResource(R.string.device_owner_setup_copied)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.device_owner_setup_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val status = when {
                    deviceOwnerActive -> stringResource(R.string.device_owner_setup_status_active)
                    deviceAdminActive -> stringResource(
                        R.string.device_owner_setup_status_admin_only
                    )
                    else -> stringResource(R.string.device_owner_setup_status_inactive)
                }
                Text(
                    text = status,
                    color = if (deviceOwnerActive) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    fontWeight = FontWeight.SemiBold
                )

                if (!deviceOwnerActive) {
                    Text(
                        text = stringResource(R.string.device_owner_setup_warning),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                    SetupStep(1, stringResource(R.string.device_owner_setup_step_backup))
                    SetupStep(2, stringResource(R.string.device_owner_setup_step_accounts))
                    SetupStep(3, stringResource(R.string.device_owner_setup_step_debugging))
                    SetupStep(4, stringResource(R.string.device_owner_setup_step_install))
                    SetupStep(5, stringResource(R.string.device_owner_setup_step_command))

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small
                    ) {
                        SelectionContainer {
                            Text(
                                text = command,
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp
                            )
                        }
                    }
                    TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            val clipboard = context.getSystemService(
                                Context.CLIPBOARD_SERVICE
                            ) as ClipboardManager
                            clipboard.setPrimaryClip(
                                ClipData.newPlainText("FocusGuard Device Owner", command)
                            )
                            Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Text(stringResource(R.string.device_owner_setup_copy))
                    }

                    SetupStep(6, stringResource(R.string.device_owner_setup_step_finish))
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.device_owner_setup_errors_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.device_owner_setup_errors),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                } else {
                    Text(
                        text = stringResource(R.string.device_owner_setup_active_description),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(
                    onClick = {
                        manager.applyNuclearShield()
                        revision++
                        onStateChanged()
                    }
                ) {
                    Text(stringResource(R.string.device_owner_setup_verify))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.status_close))
                }
            }
        }
    )
}

@Composable
private fun SetupStep(number: Int, description: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "$number.",
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = description,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp
        )
    }
}
