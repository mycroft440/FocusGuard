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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.withResumed
import com.focusguard.R
import com.focusguard.manager.BlockingSessionManager
import com.focusguard.security.AppUnlockBiometricAuthenticator
import com.focusguard.security.AuthManager
import com.focusguard.security.PasswordAppUnlockMode
import com.focusguard.security.PasswordAppUnlockStore
import com.focusguard.security.PasswordTargetAccessGrant
import com.focusguard.security.TargetCredentialThrottle
import com.focusguard.service.AppBlockSurfaceResolver
import com.focusguard.ui.compose.components.PatternLockInput
import com.focusguard.ui.compose.theme.AccentCyan
import com.focusguard.ui.compose.theme.DangerRed
import com.focusguard.ui.compose.theme.DarkBg
import com.focusguard.ui.compose.theme.TextSecondary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

// Keep rejected scans inside Android's biometric prompt. Password/pattern is
// an explicit negative-button fallback; terminal biometric errors still fall back.
private const val BIOMETRIC_FAILURES_BEFORE_FALLBACK = Int.MAX_VALUE

internal fun isPasswordTargetBiometricAllowed(
    globalBiometricUnlockEnabled: Boolean,
    targetBiometricEnabled: Boolean
): Boolean = globalBiometricUnlockEnabled && targetBiometricEnabled

/**
 * Unlock controls for a PASSWORD session target.
 *
 * The target credential is independent from the master credential. A successful
 * unlock grants a temporary visit and never edits or deletes the PASSWORD block.
 * Cancelling authentication returns control to the owner Activity so the protected
 * target can be closed instead of falling back to a generic block surface.
 * Intruder-camera ownership also stays in that Activity so the whole access
 * attempt, including cancel/Back without a submitted password, can be recorded.
 */
@Composable
internal fun PasswordProtectedTargetUnlockPanel(
    blockedPackage: String?,
    blockedDomain: String?,
    authManager: AuthManager,
    sessionManager: BlockingSessionManager,
    onUnlocked: () -> Unit,
    onCredentialRejected: () -> Unit = {},
    onCancelled: () -> Unit = {}
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
    var showCredentialDialog by remember(targetId) { mutableStateOf(false) }
    var credentialSurfaceLaunched by remember(targetId) { mutableStateOf(false) }
    var biometricPromptLaunched by remember(targetId) { mutableStateOf(false) }
    var biometricPromptInFlight by remember(targetId) { mutableStateOf(false) }
    var biometricHandle by remember(targetId) {
        mutableStateOf<AppUnlockBiometricAuthenticator.AuthenticationHandle?>(null)
    }
    var error by remember(targetId) { mutableStateOf<String?>(null) }
    var verifying by remember(targetId) { mutableStateOf(false) }
    // O sistema fechou o quadro da digital (app pausado, outra janela por cima):
    // ele volta sozinho quando esta tela retorna ao primeiro plano.
    var biometricRetryOnResume by remember(targetId) { mutableStateOf(false) }

    val globalBiometricUnlockEnabled = authManager.isBiometricAppUnlockEnabled()
    // Uma consulta ao BiometricManager por tentativa, não a cada recomposição. Se a
    // digital for removida no meio, authenticate() confere de novo e recusa.
    val biometricAvailable = remember(activity, targetId) {
        activity != null && AppUnlockBiometricAuthenticator.isAvailable(context)
    }
    val failureMessage = stringResource(R.string.password_app_unlock_failed)
    val wrongCredentialMessage = stringResource(R.string.sessions_wrong_password)
    val promptTitle = stringResource(R.string.password_app_unlock_biometric_prompt_title)
    val promptSubtitle = stringResource(R.string.password_app_unlock_biometric_prompt_subtitle)
    val cancelLabel = stringResource(R.string.cancel)

    DisposableEffect(activity, targetId) {
        onDispose { biometricHandle?.cancel() }
    }

    fun revokePendingGrant() {
        if (websiteRule != null) {
            PasswordTargetAccessGrant.revokeWebsiteRule(websiteRule)
        } else {
            PasswordTargetAccessGrant.revokePackage(blockedPackage)
        }
    }

    // Uma única checagem do dono na hora de liberar (fecha a corrida de um limite ou
    // bloqueio por tempo começar com a tela aberta). Para apps, allowsPasswordVisit já
    // implica uma sessão PASSWORD; para sites, activeWebsiteProtection só responde
    // PASSWORD sem regra mais forte ou limite esgotado.
    suspend fun passwordStillOwnsTarget(): Boolean = if (!blockedPackage.isNullOrBlank()) {
        AppBlockSurfaceResolver(
            context = context,
            sessionManager = sessionManager
        ).resolveAttempt(blockedPackage = blockedPackage).allowsPasswordVisit
    } else {
        sessionManager.activeWebsiteProtection(blockedDomain ?: websiteRule) ==
            BlockingSessionManager.ActiveWebsiteProtection.PASSWORD
    }

    /**
     * @param credential senha ou padrão digitado; null quando a digital já confirmou.
     *   O hash (PBKDF2) roda fora da thread principal e em paralelo com a checagem do
     *   dono: a espera é a maior das duas, não a soma, e a tela não congela.
     */
    fun completeUnlock(credential: String? = null, onInvalid: (() -> Unit)? = null) {
        if (verifying || targetId == null) return
        val throttleKey = store.throttleKey(targetId) ?: targetId
        if (credential != null) {
            val waitMillis = TargetCredentialThrottle.remainingLockoutMillis(throttleKey)
            if (waitMillis > 0L) {
                error = context.getString(
                    R.string.password_unlock_too_many_attempts,
                    ((waitMillis + 999L) / 1000L).toInt()
                )
                onInvalid?.invoke()
                return
            }
        }
        scope.launch {
            verifying = true
            error = null
            try {
                val (credentialAccepted, ownerConfirmed) = coroutineScope {
                    val credentialCheck = async(Dispatchers.Default) {
                        credential == null || store.verifyTarget(targetId, credential)
                    }
                    val ownerCheck = async { passwordStillOwnsTarget() }
                    credentialCheck.await() to ownerCheck.await()
                }
                if (credential != null) {
                    if (credentialAccepted) {
                        TargetCredentialThrottle.recordSuccess(throttleKey)
                    } else {
                        TargetCredentialThrottle.recordFailure(throttleKey)
                    }
                }
                if (!credentialAccepted) {
                    error = wrongCredentialMessage
                    onCredentialRejected()
                    onInvalid?.invoke()
                    return@launch
                }
                if (!ownerConfirmed) {
                    error = failureMessage
                    onInvalid?.invoke()
                    return@launch
                }

                if (websiteRule != null) {
                    PasswordTargetAccessGrant.grantWebsite(context, websiteRule)
                    if (!PasswordTargetAccessGrant.isWebsiteRuleGranted(websiteRule)) {
                        error = failureMessage
                        onInvalid?.invoke()
                        return@launch
                    }
                } else {
                    val packageName = blockedPackage?.takeIf(String::isNotBlank)
                        ?: run {
                            error = failureMessage
                            onInvalid?.invoke()
                            return@launch
                        }
                    PasswordTargetAccessGrant.grantPackage(context, packageName)
                    if (!PasswordTargetAccessGrant.isPackageGranted(packageName)) {
                        error = failureMessage
                        onInvalid?.invoke()
                        return@launch
                    }
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
        if (biometricPromptInFlight || verifying) return
        val host = activity ?: run {
            error = failureMessage
            onCancelled()
            return
        }
        val latest = store.getTarget(targetId) ?: run {
            error = failureMessage
            onCancelled()
            return
        }
        val biometricAllowed = isPasswordTargetBiometricAllowed(
            globalBiometricUnlockEnabled = authManager.isBiometricAppUnlockEnabled(),
            targetBiometricEnabled = latest.biometricEnabled
        )
        if (!biometricAllowed || !biometricAvailable || verifying) {
            error = failureMessage
            if (!verifying) onCancelled()
            return
        }

        // When password/pattern is available, the negative button becomes an
        // explicit "use password/pattern" route instead of a generic cancel.
        val fallbackLabel = if (latest.hasTypedCredential) {
            host.getString(
                if (latest.mode == PasswordAppUnlockMode.PATTERN) {
                    R.string.password_app_unlock_with_pattern
                } else {
                    R.string.password_app_unlock_with_password
                }
            )
        } else {
            cancelLabel
        }

        biometricPromptInFlight = true
        biometricHandle = AppUnlockBiometricAuthenticator.authenticate(
            activity = host,
            title = promptTitle,
            subtitle = promptSubtitle,
            cancelLabel = fallbackLabel,
            onSuccess = {
                val rechecked = store.getTarget(targetId)
                val biometricStillAllowed = rechecked?.let {
                    isPasswordTargetBiometricAllowed(
                        globalBiometricUnlockEnabled = authManager.isBiometricAppUnlockEnabled(),
                        targetBiometricEnabled = it.biometricEnabled
                    )
                } == true
                if (biometricStillAllowed) completeUnlock()
            },
            onError = { message ->
                if (message.isNotBlank()) error = message
            },
            failureThresholdBeforeFallback = if (latest.hasTypedCredential) {
                BIOMETRIC_FAILURES_BEFORE_FALLBACK
            } else {
                0
            },
            onFallbackRequested = {
                if (latest.hasTypedCredential) {
                    error = null
                    credentialSurfaceLaunched = true
                    showCredentialDialog = true
                }
            },
            // "Usar senha/padrão" no quadro: abre a senha. Sem senha, só fecha o quadro.
            onNegativeButton = {
                error = null
                if (latest.hasTypedCredential) {
                    credentialSurfaceLaunched = true
                    showCredentialDialog = true
                }
            },
            // Toque fora ou Voltar: o quadro fecha e a pessoa continua nesta tela,
            // com os botões de digital e de senha. Antes isso abria a senha sozinho
            // ou saía para a tela inicial.
            onCancelled = {
                error = null
            },
            onSystemCancelled = {
                biometricRetryOnResume = true
            },
            onFinished = {
                biometricPromptInFlight = false
            }
        )
    }

    LaunchedEffect(config, targetId) {
        if (targetId == null || config == null) {
            onCancelled()
        }
    }

    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, targetId) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME &&
                biometricRetryOnResume && !showCredentialDialog
            ) {
                biometricRetryOnResume = false
                launchBiometric()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Go directly to the method chosen while creating the block. Biometrics
    // launch first only for targets that explicitly opted in; otherwise the
    // password/pattern surface opens immediately without an extra choice screen.
    LaunchedEffect(
        config?.biometricEnabled,
        config?.mode,
        biometricAvailable,
        globalBiometricUnlockEnabled,
        targetId
    ) {
        val current = config ?: return@LaunchedEffect
        val biometricAllowed = isPasswordTargetBiometricAllowed(
  globalBiometricUnlockEnabled = globalBiometricUnlockEnabled,
  targetBiometricEnabled = current.biometricEnabled
        ) && biometricAvailable

        if (biometricAllowed && !biometricPromptLaunched) {
  activity?.lifecycle?.withResumed {
      biometricPromptLaunched = true
      launchBiometric()
  }
        } else if (!biometricAllowed && current.hasTypedCredential && !credentialSurfaceLaunched) {
  credentialSurfaceLaunched = true
  showCredentialDialog = true
        }
    }

    val currentConfig = config ?: return
    val currentBiometricAllowed = isPasswordTargetBiometricAllowed(
        globalBiometricUnlockEnabled = globalBiometricUnlockEnabled,
        targetBiometricEnabled = currentConfig.biometricEnabled
    )
    val canSwitchCredentialToBiometric = currentBiometricAllowed && biometricAvailable

    fun switchCredentialToBiometric() {
        if (verifying || biometricPromptInFlight || !canSwitchCredentialToBiometric) return
        showCredentialDialog = false
        error = null
        launchBiometric()
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        if (currentBiometricAllowed && biometricAvailable) {
            Button(
                onClick = { launchBiometric() },
                enabled = !verifying && !biometricPromptInFlight,
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
            Spacer(Modifier.height(6.dp))
            Text(
                if (biometricPromptInFlight) {
                    "Encoste o dedo no sensor de digital."
                } else {
                    "Toque acima para usar a digital de novo."
                },
                color = TextSecondary,
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }

        if (currentConfig.hasTypedCredential) {
            if (currentBiometricAllowed && biometricAvailable) {
                Spacer(Modifier.height(10.dp))
            }
            OutlinedButton(
                onClick = {
                    credentialSurfaceLaunched = true
                    showCredentialDialog = true
                },
                enabled = !verifying && !biometricPromptInFlight,
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
            (!currentBiometricAllowed || !biometricAvailable)
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

    if (showCredentialDialog) {
        when (currentConfig.mode) {
            PasswordAppUnlockMode.PASSWORD -> PasswordUnlockDialog(
                verifying = verifying,
                error = error,
                allowBiometricSwitch = canSwitchCredentialToBiometric,
                onUseBiometric = ::switchCredentialToBiometric,
                // Fechar a caixa volta para esta tela (digital ou senha de novo); só o
                // Voltar da tela sai para a tela inicial.
                onDismiss = {
                    if (!verifying) {
                        showCredentialDialog = false
                        error = null
                    }
                },
                onSubmit = { password -> completeUnlock(credential = password) }
            )

            PasswordAppUnlockMode.PATTERN -> PatternUnlockDialog(
                hideTrace = currentConfig.hidePatternTrace,
                verifying = verifying,
                error = error,
                allowBiometricSwitch = canSwitchCredentialToBiometric,
                onUseBiometric = ::switchCredentialToBiometric,
                // Fechar a caixa volta para esta tela (digital ou senha de novo); só o
                // Voltar da tela sai para a tela inicial.
                onDismiss = {
                    if (!verifying) {
                        showCredentialDialog = false
                        error = null
                    }
                },
                onSubmit = { pattern, reset ->
                    completeUnlock(credential = pattern, onInvalid = reset)
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
    onUnlocked: () -> Unit,
    onCredentialRejected: () -> Unit = {},
    onCancelled: () -> Unit = {}
) = PasswordProtectedTargetUnlockPanel(
    blockedPackage = blockedPackage,
    blockedDomain = null,
    authManager = authManager,
    sessionManager = sessionManager,
    onUnlocked = onUnlocked,
    onCredentialRejected = onCredentialRejected,
    onCancelled = onCancelled
)

@Composable
private fun PasswordUnlockDialog(
    verifying: Boolean,
    error: String?,
    allowBiometricSwitch: Boolean,
    onUseBiometric: () -> Unit,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit
) {
    var password by remember { mutableStateOf("") }
    // O campo já abre com foco e teclado: antes era preciso tocar nele primeiro.
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    // Espera um quadro para a janela da caixa ter foco; depois de uma senha errada
    // (campo desabilitado durante a conferência), o foco e o teclado voltam sozinhos.
    LaunchedEffect(error, verifying) {
        if (verifying) return@LaunchedEffect
        withFrameNanos { }
        runCatching { focusRequester.requestFocus() }
        keyboardController?.show()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.block_notice_unlock_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
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
                if (allowBiometricSwitch) {
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        onClick = onUseBiometric,
                        enabled = !verifying,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Fingerprint, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.password_app_unlock_with_biometric))
                    }
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
    allowBiometricSwitch: Boolean,
    onUseBiometric: () -> Unit,
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
                if (allowBiometricSwitch) {
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        onClick = onUseBiometric,
                        enabled = !verifying,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Fingerprint, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.password_app_unlock_with_biometric))
                    }
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
