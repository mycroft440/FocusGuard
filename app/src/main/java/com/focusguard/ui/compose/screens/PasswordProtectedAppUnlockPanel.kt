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
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.focusguard.R
import com.focusguard.manager.BlockingSessionManager
import com.focusguard.security.AppUnlockBiometricAuthenticator
import com.focusguard.security.AuthManager
import com.focusguard.security.BiometricAppUnlockPolicy
import com.focusguard.security.CameraManager
import com.focusguard.security.IntruderCapturePolicy
import com.focusguard.security.PasswordAppUnlockMode
import com.focusguard.security.PasswordAppUnlockStore
import com.focusguard.security.PasswordTargetAccessGrant
import com.focusguard.ui.compose.components.PatternLockInput
import com.focusguard.ui.compose.theme.AccentCyan
import com.focusguard.ui.compose.theme.DangerRed
import com.focusguard.ui.compose.theme.DarkBg
import com.focusguard.ui.compose.theme.TextSecondary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Unlock controls for a PASSWORD session target.
 *
 * The target credential is independent from the master credential. A successful
 * unlock grants a temporary visit and never edits or deletes the PASSWORD block.
 */
@Composable
internal fun PasswordProtectedTargetUnlockPanel(
    blockedPackage: String?,
    blockedDomain: String?,
    authManager: AuthManager,
    sessionManager: BlockingSessionManager,
    onUnlocked: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val scope = rememberCoroutineScope()
    val store = remember(context) { PasswordAppUnlockStore(context) }
    val websiteTargetId = remember(blockedDomain) {
        store.resolveWebsiteTargetId(blockedDomain)
    }
    val websiteRule = remember(websiteTargetId) {
        PasswordAppUnlockStore.websiteRuleFromTargetId(websiteTargetId)
    }
    val targetId = websiteTargetId ?: PasswordAppUnlockStore.targetIdForPackage(blockedPackage)
    var config by remember(targetId) {
        mutableStateOf(store.getTarget(targetId))
    }
    var showCredentialDialog by remember { mutableStateOf(false) }
    var showBiometricOffer by remember { mutableStateOf(false) }
    var biometricPromptLaunched by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var verifying by remember { mutableStateOf(false) }

    val biometricAvailable = activity != null &&
        AppUnlockBiometricAuthenticator.isAvailable(context)
    val failureMessage = stringResource(R.string.password_app_unlock_failed)
    val wrongCredentialMessage = stringResource(R.string.sessions_wrong_password)
    val promptTitle = stringResource(R.string.password_app_unlock_biometric_prompt_title)
    val promptSubtitle = stringResource(R.string.password_app_unlock_biometric_prompt_subtitle)
    val cancelLabel = stringResource(R.string.cancel)

    fun captureIntruderIfNeeded() {
        if (
            IntruderCapturePolicy.shouldCapture(
                surface = IntruderCapturePolicy.Surface.BLOCKED_APP_UNLOCK,
                photoCaptureEnabled = authManager.isPhotoCaptureEnabled()
            )
        ) {
            activity?.let { host ->
                CameraManager(host).setupAndCaptureSilent(host) { _ -> }
            }
        }
    }

    fun revokePendingGrant() {
        if (websiteRule != null) {
            PasswordTargetAccessGrant.revokeWebsiteRule(websiteRule)
        } else {
            PasswordTargetAccessGrant.revokePackage(blockedPackage)
        }
    }

    fun completeUnlock(onInvalid: (() -> Unit)? = null) {
        if (verifying || targetId == null) return
        scope.launch {
            verifying = true
            error = null
            try {
                val origin = sessionManager.credentialUnlockOrigin(
                    blockedPackage = blockedPackage,
                    blockedDomain = blockedDomain,
                    strictPomodoroActive = false
                )
                if (origin != BiometricAppUnlockPolicy.BlockOrigin.PASSWORD_SESSION) {
                    error = failureMessage
                    onInvalid?.invoke()
                    return@launch
                }

                if (websiteRule != null) {
                    PasswordTargetAccessGrant.grantWebsite(context, websiteRule)
                } else {
                    val packageName = blockedPackage?.takeIf(String::isNotBlank)
                        ?: run {
                            error = failureMessage
                            onInvalid?.invoke()
                            return@launch
                        }
                    PasswordTargetAccessGrant.grantPackage(context, packageName)
                }
                showCredentialDialog = false
                onUnlocked()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                revokePendingGrant()
                error = failureMessage
                onInvalid?.invoke()
            } finally {
                verifying = false
            }
        }
    }

    fun launchBiometric() {
        val host = activity ?: run {
            error = failureMessage
            return
        }
        val latest = store.getTarget(targetId) ?: run {
            error = failureMessage
            return
        }
        if (!latest.biometricEnabled || !biometricAvailable || verifying) {
            error = failureMessage
            return
        }

        AppUnlockBiometricAuthenticator.authenticate(
            activity = host,
            title = promptTitle,
            subtitle = promptSubtitle,
            cancelLabel = cancelLabel,
            onSuccess = {
                val rechecked = store.getTarget(targetId)
                if (rechecked?.biometricEnabled == true) completeUnlock()
            },
            onError = { message ->
                if (message.isNotBlank()) error = message
            }
        )
    }

    LaunchedEffect(config, biometricAvailable) {
        val current = config ?: return@LaunchedEffect
        if (
            current.hasTypedCredential &&
            !current.biometricEnabled &&
            !current.biometricOfferShown &&
            biometricAvailable
        ) {
            showBiometricOffer = true
        }
    }

    LaunchedEffect(config?.mode, biometricAvailable) {
        val current = config ?: return@LaunchedEffect
        if (
            current.mode == PasswordAppUnlockMode.BIOMETRIC_ONLY &&
            biometricAvailable &&
            !biometricPromptLaunched
        ) {
            biometricPromptLaunched = true
            launchBiometric()
        }
    }

    val currentConfig = config ?: return

    Column(modifier = Modifier.fillMaxWidth()) {
        if (currentConfig.biometricEnabled && biometricAvailable) {
            Button(
                onClick = { launchBiometric() },
                enabled = !verifying,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentCyan)
            ) {
                Icon(Icons.Default.Fingerprint, contentDescription = null, tint = DarkBg)
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.password_app_unlock_with_biometric),
                    color = DarkBg,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        if (currentConfig.hasTypedCredential) {
            if (currentConfig.biometricEnabled && biometricAvailable) {
                Spacer(Modifier.height(10.dp))
            }
            OutlinedButton(
                onClick = { showCredentialDialog = true },
                enabled = !verifying,
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) {
                Icon(Icons.Default.LockOpen, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(
                        if (currentConfig.mode == PasswordAppUnlockMode.PATTERN) {
                            R.string.password_app_unlock_with_pattern
                        } else {
                            R.string.password_app_unlock_with_password
                        }
                    )
                )
            }
        }

        if (
            currentConfig.mode == PasswordAppUnlockMode.BIOMETRIC_ONLY &&
            !biometricAvailable
        ) {
            Text(
                stringResource(R.string.password_app_unlock_biometric_required),
                color = DangerRed,
                fontSize = 13.sp
            )
        }

        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = DangerRed, fontSize = 12.sp)
        }
    }

    if (showBiometricOffer) {
        AlertDialog(
            onDismissRequest = {
                store.markBiometricOfferShownForTarget(targetId)
                config = store.getTarget(targetId)
                showBiometricOffer = false
            },
            title = { Text(stringResource(R.string.password_app_unlock_biometric_offer_title)) },
            text = { Text(stringResource(R.string.password_app_unlock_biometric_offer_desc)) },
            confirmButton = {
                Button(
                    onClick = {
                        if (store.setBiometricEnabledForTarget(targetId, true)) {
                            config = store.getTarget(targetId)
                            showBiometricOffer = false
                            launchBiometric()
                        }
                    }
                ) {
                    Text(stringResource(R.string.password_app_unlock_biometric_offer_allow))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        store.markBiometricOfferShownForTarget(targetId)
                        config = store.getTarget(targetId)
                        showBiometricOffer = false
                    }
                ) {
                    Text(stringResource(R.string.password_app_unlock_biometric_offer_not_now))
                }
            }
        )
    }

    if (showCredentialDialog) {
        when (currentConfig.mode) {
            PasswordAppUnlockMode.PASSWORD -> PasswordUnlockDialog(
                verifying = verifying,
                error = error,
                onDismiss = {
                    if (!verifying) {
                        showCredentialDialog = false
                        error = null
                    }
                },
                onSubmit = { password ->
                    if (store.verifyTarget(targetId, password)) {
                        completeUnlock()
                    } else {
                        error = wrongCredentialMessage
                        captureIntruderIfNeeded()
                    }
                }
            )

            PasswordAppUnlockMode.PATTERN -> PatternUnlockDialog(
                hideTrace = currentConfig.hidePatternTrace,
                verifying = verifying,
                error = error,
                onDismiss = {
                    if (!verifying) {
                        showCredentialDialog = false
                        error = null
                    }
                },
                onSubmit = { pattern, reset ->
                    if (store.verifyTarget(targetId, pattern)) {
                        completeUnlock(onInvalid = reset)
                    } else {
                        error = wrongCredentialMessage
                        captureIntruderIfNeeded()
                        reset()
                    }
                }
            )

            PasswordAppUnlockMode.BIOMETRIC_ONLY -> Unit
        }
    }
}

/** Compatibility wrapper for call sites that still have an app-only target. */
@Composable
internal fun PasswordProtectedAppUnlockPanel(
    blockedPackage: String,
    authManager: AuthManager,
    sessionManager: BlockingSessionManager,
    onUnlocked: () -> Unit
) = PasswordProtectedTargetUnlockPanel(
    blockedPackage = blockedPackage,
    blockedDomain = null,
    authManager = authManager,
    sessionManager = sessionManager,
    onUnlocked = onUnlocked
)

@Composable
private fun PasswordUnlockDialog(
    verifying: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit
) {
    var password by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.block_notice_unlock_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !verifying,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { if (password.isNotBlank()) onSubmit(password) }
                    )
                )
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = DangerRed, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSubmit(password) },
                enabled = password.isNotBlank() && !verifying
            ) {
                if (verifying) {
                    CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(stringResource(R.string.sessions_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !verifying) {
                Text(stringResource(R.string.cancel), color = TextSecondary)
            }
        }
    )
}

@Composable
private fun PatternUnlockDialog(
    hideTrace: Boolean,
    verifying: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSubmit: (String, () -> Unit) -> Unit
) {
    var resetKey by remember { mutableIntStateOf(0) }
    val reset: () -> Unit = { resetKey++ }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.password_app_unlock_pattern_title)) },
        text = {
            Column {
                PatternLockInput(
                    hideTrace = hideTrace,
                    enabled = !verifying,
                    resetKey = resetKey,
                    onPatternComplete = { pattern -> onSubmit(pattern, reset) }
                )
                error?.let {
                    Text(it, color = DangerRed, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !verifying) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
