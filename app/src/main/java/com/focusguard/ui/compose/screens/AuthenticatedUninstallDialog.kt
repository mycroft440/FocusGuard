package com.focusguard.ui.compose.screens

import android.content.Intent
import android.net.Uri
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
import com.focusguard.admin.DeviceOwnerManager
import com.focusguard.security.DeactivationCredentialManager
import com.focusguard.security.DeviceOwnerMaintenanceGate
import com.focusguard.security.MasterCredentialPolicy
import com.focusguard.ui.compose.theme.DangerRed
import com.focusguard.ui.compose.theme.TextSecondary
import com.focusguard.utils.FocusGuardLogger

/**
 * Authenticated way out of the app.
 *
 * Deliberately builds on the existing maintenance machinery instead of adding a
 * second path around it: [DeviceOwnerMaintenanceGate.requestWithCredential]
 * verifies the master credential and opens the short window that
 * [DeviceOwnerManager.revokeNuclearShield] already requires. That gate also
 * refuses password-based maintenance while a block is running, which is the same
 * invariant [MasterCredentialPolicy.evaluateUninstall] expresses — uninstall must
 * not become an escape hatch from a fast the user chose.
 *
 * The app never removes itself: after releasing the shield it hands off to
 * Android's own uninstall screen.
 */
@Composable
internal fun AuthenticatedUninstallDialog(
    hasActiveIrreversibleBlock: Boolean,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val credentialManager = remember(context) { DeactivationCredentialManager(context) }
    val deviceOwnerManager = remember(context) { DeviceOwnerManager.getInstance(context) }

    var credential by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }

    val hasCredential = remember { credentialManager.hasCredential() }
    val notConfiguredMessage = stringResource(R.string.master_credential_not_configured)
    val blockedMessage = stringResource(R.string.uninstall_app_blocked_by_active_block)
    val failedMessage = stringResource(R.string.uninstall_app_failed)
    val invalidCredentialMessage = stringResource(
        R.string.device_owner_maintenance_invalid_credential
    )
    val autoTimeRequiredMessage = stringResource(
        R.string.device_owner_maintenance_auto_time_required
    )
    val outsideWindowMessage = stringResource(R.string.device_owner_maintenance_outside_window)

    // Evaluated before asking for anything, so the user is never prompted for a
    // credential that cannot unlock the action.
    val preCheck = MasterCredentialPolicy.evaluateUninstall(
        hasActiveIrreversibleBlock = hasActiveIrreversibleBlock,
        hasMasterCredential = hasCredential,
        masterCredentialVerified = false
    )

    fun handOffToAndroidUninstall() {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_DELETE).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }.onFailure { error ->
            FocusGuardLogger.logError(
                "AuthenticatedUninstall",
                "Falha ao abrir a tela de desinstalação do Android",
                error
            )
        }
        onDismiss()
    }

    fun releaseAndHandOff() {
        if (working) return
        working = true
        errorMessage = null

        // Without Device Owner there is no removal protection to release; the
        // master credential is still required because the user asked for it.
        if (!deviceOwnerManager.isDeviceOwnerActive()) {
            val verification = credentialManager.verify(credential)
            val verified = verification ==
                DeactivationCredentialManager.VerificationResult.PASSWORD_ACCEPTED ||
                verification ==
                DeactivationCredentialManager.VerificationResult.RECOVERY_ACCEPTED
            if (verified) handOffToAndroidUninstall() else errorMessage = invalidCredentialMessage
            working = false
            return
        }

        val unlock = DeviceOwnerMaintenanceGate.requestWithCredential(
            context = context,
            credential = credential,
            protectionArmed = deviceOwnerManager.isArmoredProtectionArmed()
        )

        when (unlock) {
            DeviceOwnerMaintenanceGate.UnlockResult.UNLOCKED -> {
                val released = runCatching { deviceOwnerManager.revokeNuclearShield() }
                    .onFailure { error ->
                        FocusGuardLogger.logError(
                            "AuthenticatedUninstall",
                            "Falha ao revogar a blindagem para desinstalação",
                            error
                        )
                    }
                    .isSuccess
                if (released) handOffToAndroidUninstall() else errorMessage = failedMessage
            }

            DeviceOwnerMaintenanceGate.UnlockResult.INVALID_CREDENTIAL ->
                errorMessage = invalidCredentialMessage

            DeviceOwnerMaintenanceGate.UnlockResult.CREDENTIAL_NOT_CONFIGURED ->
                errorMessage = notConfiguredMessage

            DeviceOwnerMaintenanceGate.UnlockResult.ACTIVE_BLOCK_REQUIRES_MONTHLY_WINDOW ->
                errorMessage = blockedMessage

            DeviceOwnerMaintenanceGate.UnlockResult.AUTOMATIC_DATE_TIME_REQUIRED ->
                errorMessage = autoTimeRequiredMessage

            DeviceOwnerMaintenanceGate.UnlockResult.OUTSIDE_MONTHLY_WINDOW ->
                errorMessage = outsideWindowMessage
        }
        working = false
    }

    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text(stringResource(R.string.uninstall_app_title)) },
        text = {
            Column {
                when (preCheck) {
                    MasterCredentialPolicy.UninstallGate
                        .BLOCKED_BY_ACTIVE_IRREVERSIBLE_BLOCK ->
                        Text(blockedMessage, color = DangerRed)

                    MasterCredentialPolicy.UninstallGate
                        .MASTER_CREDENTIAL_NOT_CONFIGURED ->
                        Text(notConfiguredMessage, color = DangerRed)

                    else -> {
                        Text(
                            stringResource(R.string.uninstall_app_description),
                            color = TextSecondary
                        )
                        Spacer(Modifier.height(16.dp))
                        OutlinedTextField(
                            value = credential,
                            onValueChange = {
                                credential = it
                                errorMessage = null
                            },
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
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                errorMessage?.let { message ->
                    Spacer(Modifier.height(8.dp))
                    Text(message, color = DangerRed)
                }
            }
        },
        confirmButton = {
            if (preCheck == MasterCredentialPolicy.UninstallGate.MASTER_CREDENTIAL_REQUIRED) {
                TextButton(
                    onClick = { releaseAndHandOff() },
                    enabled = credential.isNotBlank() && !working
                ) {
                    Text(stringResource(R.string.uninstall_app_confirm), color = DangerRed)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = { if (!working) onDismiss() }) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
