package com.focusguard.ui.compose.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.focusguard.R
import com.focusguard.manager.BlockingSessionManager
import com.focusguard.security.AppUnlockBiometricAuthenticator
import com.focusguard.security.AuthManager
import com.focusguard.security.BiometricAppUnlockPolicy
import com.focusguard.security.TargetCredentialThrottle
import com.focusguard.ui.compose.theme.AccentCyan
import com.focusguard.ui.compose.theme.CardBorder
import com.focusguard.ui.compose.theme.DangerRed
import com.focusguard.ui.compose.theme.DarkBg
import com.focusguard.ui.compose.theme.TextHint
import com.focusguard.ui.compose.theme.TextPrimary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** O que a tela de bloqueio faz com a resposta da liberação do limite com senha. */
internal enum class LimitPasswordOutcome { OPEN_APP, WRONG_PASSWORD, FAILED }

/**
 * NOT_FOUND quer dizer que nenhum limite com senha segura o app agora (já liberado,
 * zerou à meia-noite, mudou de modo): o app é aberto, e se outra proteção ainda o
 * segurar, o serviço bloqueia de novo com a tela certa. Nada é liberado sem senha.
 */
internal fun limitPasswordOutcome(
    result: BlockingSessionManager.LimitUnlockResult
): LimitPasswordOutcome = when (result) {
    BlockingSessionManager.LimitUnlockResult.UNLOCKED,
    BlockingSessionManager.LimitUnlockResult.NOT_FOUND -> LimitPasswordOutcome.OPEN_APP
    BlockingSessionManager.LimitUnlockResult.WRONG_PASSWORD -> LimitPasswordOutcome.WRONG_PASSWORD
    BlockingSessionManager.LimitUnlockResult.FAILED -> LimitPasswordOutcome.FAILED
}

/**
 * Só UNLOCKED prova que a senha foi conferida e aceita. NOT_FOUND volta antes da
 * conferência (nenhum limite com senha segura o app), então não zera a contagem de
 * tentativas erradas.
 */
internal fun limitPasswordVerified(result: BlockingSessionManager.LimitUnlockResult): Boolean =
    result == BlockingSessionManager.LimitUnlockResult.UNLOCKED

/** Todas as tentativas da senha mestra nos limites somam: é a mesma senha. */
internal const val LIMIT_PASSWORD_THROTTLE_KEY = "master:daily-limit"

/**
 * Liberação de um limite diário configurado com "Desbloquear com a senha mestra".
 * A senha (ou a digital, quando a pessoa ligou a digital para abrir apps) libera o
 * app até a meia-noite, como prometido na configuração do limite.
 */
@Composable
internal fun LimitPasswordUnlockPanel(
    blockedPackage: String,
    authManager: AuthManager,
    sessionManager: BlockingSessionManager,
    onUnlocked: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val scope = rememberCoroutineScope()
    var password by remember(blockedPackage) { mutableStateOf("") }
    var error by remember(blockedPackage) { mutableStateOf<String?>(null) }
    var verifying by remember(blockedPackage) { mutableStateOf(false) }
    var biometricOffered by remember(blockedPackage) { mutableStateOf(false) }
    var biometricHandle by remember(blockedPackage) {
        mutableStateOf<AppUnlockBiometricAuthenticator.AuthenticationHandle?>(null)
    }

    val wrongMessage = stringResource(R.string.master_credential_wrong)
    val failedMessage = stringResource(R.string.limit_password_unlock_failed)
    val promptTitle = stringResource(R.string.password_app_unlock_biometric_prompt_title)
    val promptSubtitle = stringResource(R.string.limit_password_unlock_biometric_subtitle)
    val cancelLabel = stringResource(R.string.cancel)

    // A preferência fica no cofre criptografado: lida fora da thread principal para
    // não atrasar o primeiro quadro da tela de bloqueio.
    LaunchedEffect(blockedPackage) {
        biometricOffered = withContext(Dispatchers.IO) {
            BiometricAppUnlockPolicy.shouldOfferBiometricUnlock(
                origin = BiometricAppUnlockPolicy.BlockOrigin.USAGE_LIMIT_PASSWORD_UNLOCK,
                biometricUnlockEnabled = authManager.isBiometricAppUnlockEnabled(),
                biometricAvailable = AppUnlockBiometricAuthenticator.isAvailable(context)
            )
        } && activity != null
    }
    DisposableEffect(blockedPackage) {
        onDispose { biometricHandle?.cancel() }
    }

    fun handleResult(result: BlockingSessionManager.LimitUnlockResult, typed: Boolean) {
        when (limitPasswordOutcome(result)) {
            LimitPasswordOutcome.OPEN_APP -> {
                if (typed && limitPasswordVerified(result)) {
                    TargetCredentialThrottle.recordSuccess(LIMIT_PASSWORD_THROTTLE_KEY)
                }
                onUnlocked()
            }
            LimitPasswordOutcome.WRONG_PASSWORD -> {
                if (typed) TargetCredentialThrottle.recordFailure(LIMIT_PASSWORD_THROTTLE_KEY)
                password = ""
                error = wrongMessage
            }
            LimitPasswordOutcome.FAILED -> error = failedMessage
        }
    }

    fun unlockWithPassword() {
        if (verifying || password.isBlank()) return
        val waitMillis = TargetCredentialThrottle.remainingLockoutMillis(
            LIMIT_PASSWORD_THROTTLE_KEY
        )
        if (waitMillis > 0L) {
            error = context.getString(
                R.string.password_unlock_too_many_attempts,
                ((waitMillis + 999L) / 1000L).toInt()
            )
            return
        }
        val typed = password
        scope.launch {
            verifying = true
            error = null
            try {
                // Confere a senha (fora da thread principal) e libera o limite até a
                // meia-noite numa operação só, já com a reconciliação feita.
                handleResult(
                    sessionManager.unlockPasswordProtectedLimit(
                        password = typed,
                        blockedPackage = blockedPackage,
                        blockedDomain = null
                    ),
                    typed = true
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = failedMessage
            } finally {
                verifying = false
            }
        }
    }

    fun unlockWithBiometric() {
        val host = activity ?: return
        if (verifying) return
        error = null
        biometricHandle = AppUnlockBiometricAuthenticator.authenticate(
            activity = host,
            title = promptTitle,
            subtitle = promptSubtitle,
            cancelLabel = cancelLabel,
            onSuccess = {
                scope.launch {
                    verifying = true
                    try {
                        // A digital só substitui uma senha que já abriria o app: a
                        // preferência é conferida de novo depois do quadro.
                        val stillAllowed = withContext(Dispatchers.IO) {
                            BiometricAppUnlockPolicy.canAcceptBiometricResult(
                                origin = BiometricAppUnlockPolicy.BlockOrigin
                                    .USAGE_LIMIT_PASSWORD_UNLOCK,
                                biometricUnlockEnabled = authManager.isBiometricAppUnlockEnabled()
                            )
                        }
                        if (stillAllowed) {
                            handleResult(
                                sessionManager.unlockLimitWithVerifiedIdentity(
                                    blockedPackage = blockedPackage,
                                    blockedDomain = null
                                ),
                                typed = false
                            )
                        } else {
                            error = failedMessage
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        error = failedMessage
                    } finally {
                        verifying = false
                    }
                }
            },
            onError = { message -> if (message.isNotBlank()) error = message }
        )
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = password,
            onValueChange = {
                password = it
                error = null
            },
            label = { Text(stringResource(R.string.limit_password_unlock_field)) },
            singleLine = true,
            enabled = !verifying,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(onDone = { unlockWithPassword() }),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            isError = error != null,
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
        error?.let { message ->
            Spacer(Modifier.height(8.dp))
            Text(
                message,
                color = DangerRed,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(Modifier.height(14.dp))
        Button(
            onClick = { unlockWithPassword() },
            enabled = password.isNotBlank() && !verifying,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AccentCyan)
        ) {
            if (verifying) {
                CircularProgressIndicator(
                    color = DarkBg,
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )
            } else {
                Text(
                    stringResource(R.string.limit_password_unlock_action),
                    color = DarkBg,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        if (biometricOffered) {
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = { unlockWithBiometric() },
                enabled = !verifying,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Default.Fingerprint, contentDescription = null, tint = AccentCyan)
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.password_app_unlock_with_biometric),
                    color = AccentCyan
                )
            }
        }
    }
}
