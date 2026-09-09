package com.focusguard.ui.compose.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.focusguard.R
import com.focusguard.manager.BlockingSessionManager
import com.focusguard.security.AppUnlockBiometricAuthenticator
import com.focusguard.security.AuthManager
import com.focusguard.security.PasswordAppUnlockMode
import com.focusguard.security.PasswordAppUnlockStore
import com.focusguard.ui.compose.components.PatternLockInput
import com.focusguard.ui.compose.theme.DangerRed
import kotlinx.coroutines.launch

/**
 * Authenticates an explicit request to remove one app from a PASSWORD session.
 *
 * This UI is intentionally separate from the Accessibility/blocking hot path.
 * Nothing runs until the user taps the remove affordance on the management screen.
 * Password/pattern verification uses the credential stored for that target. Strong
 * biometric confirmation is offered only when the existing global and per-target
 * biometric settings already allow it, so this flow never bypasses the user's
 * biometric opt-in or its rewarded gate.
 */
@Composable
internal fun PasswordBlockRemovalDialog(
    entry: BlockingSessionManager.BlockOverview.Entry,
    sessionManager: BlockingSessionManager,
    onRemoved: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val scope = rememberCoroutineScope()
    val store = remember(context) { PasswordAppUnlockStore(context) }
    val authManager = remember(context) { AuthManager(context) }
    val config = remember(entry.identifier) { store.get(entry.identifier) }
    val label = remember(entry.identifier) {
        blockedEntryLabel(
            identifier = entry.identifier,
            isWebsite = false,
            installedLabel = runCatching {
                val pm = context.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(entry.identifier, 0)).toString()
            }.getOrNull()
        )
    }

    var password by remember(entry.identifier) { mutableStateOf("") }
    var error by remember(entry.identifier) { mutableStateOf<String?>(null) }
    var busy by remember(entry.identifier) { mutableStateOf(false) }
    var patternResetKey by remember(entry.identifier) { mutableIntStateOf(0) }

    val biometricAllowed = activity != null &&
        config?.biometricEnabled == true &&
        authManager.isBiometricAppUnlockEnabled() &&
        AppUnlockBiometricAuthenticator.isAvailable(context)

    val failureMessage = stringResource(R.string.block_notice_unlock_failed)
    val wrongCredentialMessage = stringResource(R.string.sessions_wrong_password)
    val biometricFailureMessage = stringResource(R.string.biometric_auth_failed)

    fun completeRemoval() {
        if (busy) return
        scope.launch {
            busy = true
            error = null
            try {
                when (
                    sessionManager.unlockPasswordSessionTarget(
                        blockedPackage = entry.identifier,
                        blockedDomain = null
                    )
                ) {
                    BlockingSessionManager.EndSessionResult.ENDED,
                    BlockingSessionManager.EndSessionResult.NOT_FOUND -> onRemoved()
                    else -> error = failureMessage
                }
            } catch (_: Exception) {
                error = failureMessage
            } finally {
                busy = false
            }
        }
    }

    fun launchBiometric() {
        val host = activity ?: run {
            error = biometricFailureMessage
            return
        }
        val latest = store.get(entry.identifier)
        val stillAllowed = latest?.biometricEnabled == true &&
            authManager.isBiometricAppUnlockEnabled() &&
            AppUnlockBiometricAuthenticator.isAvailable(context)
        if (!stillAllowed || busy) {
            if (!busy) error = biometricFailureMessage
            return
        }

        AppUnlockBiometricAuthenticator.authenticate(
            activity = host,
            title = host.getString(R.string.remove_button),
            subtitle = label,
            cancelLabel = host.getString(R.string.cancel),
            onSuccess = {
                val rechecked = store.get(entry.identifier)
                val remainsAllowed = rechecked?.biometricEnabled == true &&
                    authManager.isBiometricAppUnlockEnabled()
                if (remainsAllowed) {
                    completeRemoval()
                } else {
                    error = biometricFailureMessage
                }
            },
            onError = { message ->
                error = message.takeIf(String::isNotBlank) ?: biometricFailureMessage
            },
            onCancelled = { error = null }
        )
    }

    AlertDialog(
        onDismissRequest = {
            if (!busy) onDismiss()
        },
        title = { Text(stringResource(R.string.remove_button)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(label)
                Spacer(Modifier.height(12.dp))

                when (config?.mode) {
                    PasswordAppUnlockMode.PASSWORD -> {
                        OutlinedTextField(
                            value = password,
                            onValueChange = {
                                password = it
                                error = null
                            },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.digite_sua_senha)) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    if (!busy && store.verify(entry.identifier, password)) {
                                        completeRemoval()
                                    } else if (!busy) {
                                        error = wrongCredentialMessage
                                    }
                                }
                            )
                        )
                    }

                    PasswordAppUnlockMode.PATTERN -> {
                        Text(stringResource(R.string.password_app_unlock_with_pattern))
                        Spacer(Modifier.height(8.dp))
                        PatternLockInput(
                            hideTrace = config.hidePatternTrace,
                            enabled = !busy,
                            resetKey = patternResetKey,
                            onPatternComplete = { pattern ->
                                if (store.verify(entry.identifier, pattern)) {
                                    completeRemoval()
                                } else {
                                    error = wrongCredentialMessage
                                    patternResetKey++
                                }
                            }
                        )
                    }

                    PasswordAppUnlockMode.BIOMETRIC_ONLY -> {
                        if (!biometricAllowed) {
                            Text(
                                stringResource(R.string.password_app_unlock_biometric_required),
                                color = DangerRed
                            )
                        }
                    }

                    null -> Text(failureMessage, color = DangerRed)
                }

                if (biometricAllowed) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = ::launchBiometric,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Fingerprint, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.password_app_unlock_with_biometric))
                    }
                }

                if (busy) {
                    Spacer(Modifier.height(12.dp))
                    CircularProgressIndicator()
                }

                error?.let { message ->
                    Spacer(Modifier.height(8.dp))
                    Text(message, color = DangerRed)
                }
            }
        },
        confirmButton = {
            if (config?.mode == PasswordAppUnlockMode.PASSWORD) {
                Button(
                    onClick = {
                        if (store.verify(entry.identifier, password)) {
                            completeRemoval()
                        } else {
                            error = wrongCredentialMessage
                        }
                    },
                    enabled = !busy && password.isNotBlank()
                ) {
                    Text(stringResource(R.string.remove_button))
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !busy
            ) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
