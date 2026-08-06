package com.focusguard.ui.compose.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.os.UserManager
import android.provider.Settings
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.focusguard.R
import com.focusguard.admin.DeviceOwnerManager
import com.focusguard.security.DeviceOwnerSetupGuide

@Composable
internal fun DeviceOwnerSetupGuideDialog(
    onDismiss: () -> Unit,
    onStateChanged: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val manager = remember(context) { DeviceOwnerManager.getInstance(context) }
    var revision by remember { mutableIntStateOf(0) }
    var verifyMessage by remember { mutableStateOf<String?>(null) }

    // Every step of this tutorial happens outside the app — Developer options, the
    // accounts screen, a terminal on the computer. Re-reading on resume means the
    // checklist is already current when the person comes back to the dialog.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                revision++
                verifyMessage = null
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val deviceOwnerActive = remember(revision) { manager.isDeviceOwnerActive() }
    val deviceAdminActive = remember(revision) { manager.isDeviceAdminActive() }
    val checks = remember(revision) {
        DeviceOwnerSetupGuide.inspect(readSetupEnvironment(context))
    }
    val command = remember(context.packageName) {
        DeviceOwnerSetupGuide.buildAdbCommand(context.packageName)
    }
    val copiedMessage = stringResource(R.string.device_owner_setup_copied)
    val shareSubject = stringResource(R.string.device_owner_setup_share_subject)
    val shareText = stringResource(R.string.device_owner_setup_share_text, command)
    val shareUnavailable = stringResource(R.string.device_owner_setup_share_unavailable)
    val developerOptionsUnavailable = stringResource(
        R.string.device_owner_setup_developer_options_unavailable
    )
    val verifyConfirmed = stringResource(R.string.device_owner_setup_verify_confirmed)
    val verifyPending = stringResource(R.string.device_owner_setup_verify_pending)

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

                    Text(
                        text = stringResource(R.string.device_owner_setup_checks_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    checks.forEach { check ->
                        SetupCheckRow(check)
                        if (check.requirement ==
                            DeviceOwnerSetupGuide.Requirement.USB_DEBUGGING &&
                            check.status != DeviceOwnerSetupGuide.Status.READY
                        ) {
                            TextButton(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    if (!openDeveloperOptions(context)) {
                                        Toast.makeText(
                                            context,
                                            developerOptionsUnavailable,
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                            ) {
                                Text(
                                    stringResource(
                                        R.string.device_owner_setup_open_developer_options
                                    )
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(2.dp))
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
                    // The command has to end up on the computer, and the phone's
                    // clipboard does not travel. Sharing lets the person mail or
                    // message it to themselves instead of retyping it by hand.
                    TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            if (!shareCommand(context, shareSubject, shareText)) {
                                Toast.makeText(
                                    context,
                                    shareUnavailable,
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    ) {
                        Text(stringResource(R.string.device_owner_setup_share))
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

                verifyMessage?.let { message ->
                    Text(
                        text = message,
                        color = if (deviceOwnerActive) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        fontSize = 12.sp
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
                        // The old button changed the status line silently, which reads
                        // as "nothing happened" once the list has scrolled past it.
                        verifyMessage = if (manager.isDeviceOwnerActive()) {
                            verifyConfirmed
                        } else {
                            verifyPending
                        }
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

/**
 * Reads the three provisioning signals a normal app is allowed to see.
 *
 * Unreadable signals fall back to the answer that keeps the guide honest: never
 * invent a blocking condition the person cannot act on, and never claim a step is
 * already done.
 */
private fun readSetupEnvironment(context: Context): DeviceOwnerSetupGuide.Environment {
    val resolver = context.contentResolver
    val runningOnMainUser = runCatching {
        val userManager = context.getSystemService(Context.USER_SERVICE) as UserManager
        userManager.getSerialNumberForUser(Process.myUserHandle()) == 0L
    }.getOrDefault(true)
    val usbDebuggingEnabled = runCatching {
        Settings.Global.getInt(resolver, Settings.Global.ADB_ENABLED, 0) == 1
    }.getOrDefault(false)
    val setupWizardCompleted = runCatching {
        Settings.Global.getInt(resolver, Settings.Global.DEVICE_PROVISIONED, 1) == 1
    }.getOrDefault(true)

    return DeviceOwnerSetupGuide.Environment(
        runningOnMainUser = runningOnMainUser,
        usbDebuggingEnabled = usbDebuggingEnabled,
        setupWizardCompleted = setupWizardCompleted
    )
}

/** Developer options hide behind different screens per manufacturer. */
private fun openDeveloperOptions(context: Context): Boolean {
    val candidates = listOf(
        Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
        Intent(Settings.ACTION_DEVICE_INFO_SETTINGS),
        Intent(Settings.ACTION_SETTINGS)
    )
    return candidates.any { candidate ->
        runCatching {
            context.startActivity(candidate)
            true
        }.getOrDefault(false)
    }
}

private fun shareCommand(context: Context, subject: String, text: String): Boolean =
    runCatching {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(intent, subject))
        true
    }.getOrDefault(false)

@Composable
private fun SetupCheckRow(check: DeviceOwnerSetupGuide.Check) {
    val label = when (check.requirement) {
        DeviceOwnerSetupGuide.Requirement.MAIN_USER ->
            stringResource(R.string.device_owner_setup_check_main_user)
        DeviceOwnerSetupGuide.Requirement.USB_DEBUGGING ->
            stringResource(R.string.device_owner_setup_check_usb)
        DeviceOwnerSetupGuide.Requirement.FRESH_DEVICE ->
            stringResource(R.string.device_owner_setup_check_fresh)
    }
    val statusText = when (check.status) {
        DeviceOwnerSetupGuide.Status.READY ->
            stringResource(R.string.device_owner_setup_check_ready)
        DeviceOwnerSetupGuide.Status.PENDING ->
            stringResource(R.string.device_owner_setup_check_pending)
        DeviceOwnerSetupGuide.Status.BLOCKING ->
            stringResource(R.string.device_owner_setup_check_blocking)
        DeviceOwnerSetupGuide.Status.UNCERTAIN ->
            stringResource(R.string.device_owner_setup_check_uncertain)
    }
    val statusColor = when (check.status) {
        DeviceOwnerSetupGuide.Status.READY -> MaterialTheme.colorScheme.primary
        DeviceOwnerSetupGuide.Status.BLOCKING -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.secondary
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = statusText,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = statusColor
        )
    }

    if (check.status != DeviceOwnerSetupGuide.Status.READY) {
        val hint = when (check.requirement) {
            DeviceOwnerSetupGuide.Requirement.MAIN_USER ->
                stringResource(R.string.device_owner_setup_check_main_user_hint)
            DeviceOwnerSetupGuide.Requirement.USB_DEBUGGING ->
                stringResource(R.string.device_owner_setup_check_usb_hint)
            DeviceOwnerSetupGuide.Requirement.FRESH_DEVICE ->
                stringResource(R.string.device_owner_setup_check_fresh_hint)
        }
        Text(
            text = hint,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp
        )
    }
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
