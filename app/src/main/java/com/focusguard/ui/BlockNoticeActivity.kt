package com.focusguard.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.R
import com.focusguard.manager.BlockingSessionManager
import com.focusguard.security.AuthManager
import com.focusguard.ui.compose.theme.*
import com.focusguard.utils.FocusGuardLogger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Tela de aviso exibida quando o usuário tenta abrir um app/site bloqueado.
 *
 * Comportamento:
 * - **Modo STRICT_BLOCK (Pomodoro rigoroso)**: redireciona para
 *   PomodoroLockActivity após 2s. Back bloqueado. Sem opção de senha
 *   (Pomodoro rigoroso é intencionalmente não-interrompível).
 * - **Modo normal (sessões PASSWORD/TIME)**: mostra botão "Desbloquear
 *   com senha" que, se autenticado, encerra a sessão ativa e volta
 *   para home.
 *
 * Desbloqueio por senha respeita as regras do BlockingSessionManager:
 * - POMODORO: não pode ser interrompido (mostra mensagem)
 * - TIME na janela ativa: não pode ser revogado (mostra mensagem)
 * - PASSWORD: pode ser encerrado a qualquer momento
 */
@AndroidEntryPoint
class BlockNoticeActivity : ComponentActivity() {

    @Inject lateinit var authManager: AuthManager
    @Inject lateinit var blockingSessionManager: BlockingSessionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val isStrictBlock = intent.getBooleanExtra("STRICT_BLOCK", false)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!isStrictBlock) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        setContent {
            FocusGuardTheme {
                BlockNoticeContent(
                    isStrictBlock = isStrictBlock,
                    authManager = authManager,
                    blockingSessionManager = blockingSessionManager,
                    onGoHome = { goHome() },
                    onGoToPomodoroLock = {
                        val lockIntent = Intent(this@BlockNoticeActivity, PomodoroLockActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        }
                        startActivity(lockIntent)
                        finish()
                    }
                )
            }
        }
    }

    private fun goHome() {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(homeIntent)
        finish()
    }
}

@Composable
private fun BlockNoticeContent(
    isStrictBlock: Boolean,
    authManager: AuthManager,
    blockingSessionManager: BlockingSessionManager,
    onGoHome: () -> Unit,
    onGoToPomodoroLock: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var showUnlockDialog by remember { mutableStateOf(false) }
    var passwordInput by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isVerifying by remember { mutableStateOf(false) }
    var successMessage by remember { mutableStateOf<String?>(null) }
    val focusRequester = remember { FocusRequester() }
    val context = androidx.compose.ui.platform.LocalContext.current

    // Foca o campo de senha quando o diálogo abre
    LaunchedEffect(showUnlockDialog) {
        if (showUnlockDialog) {
            delay(100)
            focusRequester.requestFocus()
        }
    }

    // Auto-timeout: em modo strict, vai para PomodoroLockActivity após 2s
    LaunchedEffect(Unit) {
        if (isStrictBlock) {
            delay(2000)
            onGoToPomodoroLock()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg.copy(alpha = 0.95f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            // Ícone de escudo
            Surface(
                color = AccentCyan.copy(alpha = 0.1f),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.size(100.dp),
                border = androidx.compose.foundation.BorderStroke(2.dp, AccentCyan)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_shield),
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                        tint = AccentCyan
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = "App bloqueado pelo\nFocusGuard",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                textAlign = TextAlign.Center,
                lineHeight = 32.sp
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Mantenha o foco em seus objetivos.",
                fontSize = 16.sp,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Mensagem de sucesso (se desbloqueou)
            successMessage?.let { msg ->
                Surface(
                    color = SuccessGreen.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, SuccessGreen)
                ) {
                    Text(
                        text = msg,
                        color = SuccessGreen,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(16.dp),
                        textAlign = TextAlign.Center
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Botão "Desbloquear com senha" — só em modo não-strict
            if (!isStrictBlock && successMessage == null) {
                Button(
                    onClick = { showUnlockDialog = true },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Default.LockOpen, contentDescription = null, tint = DarkBg, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.block_notice_unlock_with_password), color = DarkBg, fontWeight = FontWeight.Bold)
                }
            }

            // Em modo strict, explica que não pode desbloquear por senha
            if (isStrictBlock) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "🔒 Modo rigoroso ativo. O bloqueio só será liberado quando o tempo acabar.",
                    color = TextHint,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
        }
    }

    // Diálogo de senha
    if (showUnlockDialog) {
        AlertDialog(
            onDismissRequest = {
                if (!isVerifying) {
                    showUnlockDialog = false
                    passwordInput = ""
                    errorMessage = null
                }
            },
            containerColor = DarkSurface,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Lock, contentDescription = null, tint = AccentCyan)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.block_notice_unlock_title), color = TextPrimary, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column {
                    Text(stringResource(R.string.block_notice_unlock_desc), color = TextSecondary, fontSize = 14.sp)
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = passwordInput,
                        onValueChange = {
                            passwordInput = it
                            errorMessage = null
                        },
                        placeholder = { Text(stringResource(R.string.sessions_password_hint), color = TextHint) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        isError = errorMessage != null,
                        enabled = !isVerifying,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(onDone = {
                            if (passwordInput.isNotBlank() && !isVerifying) {
                                scope.launch { attemptUnlock(
                                    passwordInput, authManager, blockingSessionManager, context
                                ) { verifying, error, success ->
                                    isVerifying = verifying
                                    errorMessage = error
                                    if (success != null) {
                                        successMessage = success
                                        showUnlockDialog = false
                                        scope.launch {
                                            delay(1000)
                                            onGoHome()
                                        }
                                    }
                                } }
                            }
                        }),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedBorderColor = AccentCyan
                        ),
                        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester)
                    )
                    if (errorMessage != null) {
                        Text(errorMessage!!, color = DangerRed, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (passwordInput.isNotBlank() && !isVerifying) {
                            scope.launch { attemptUnlock(
                                passwordInput, authManager, blockingSessionManager, context
                            ) { verifying, error, success ->
                                isVerifying = verifying
                                errorMessage = error
                                if (success != null) {
                                    successMessage = success
                                    showUnlockDialog = false
                                    scope.launch {
                                        delay(1000)
                                        onGoHome()
                                    }
                                }
                            } }
                        }
                    },
                    enabled = passwordInput.isNotBlank() && !isVerifying,
                    colors = ButtonDefaults.buttonColors(containerColor = AccentCyan)
                ) {
                    if (isVerifying) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = DarkBg)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(stringResource(R.string.sessions_confirm), color = DarkBg)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    if (!isVerifying) {
                        showUnlockDialog = false
                        passwordInput = ""
                        errorMessage = null
                    }
                }) {
                    Text(stringResource(R.string.cancel), color = TextSecondary)
                }
            }
        )
    }
}

/**
 * Tenta desbloquear verificando a senha e encerrando a sessão ativa.
 *
 * Fluxo:
 * 1. Verifica a senha via AuthManager.verifyPassword
 * 2. Se correta, busca sessão ativa e chama endSession
 * 3. Respeita regras: POMODORO não interrompível, TIME na janela ativa não revogável
 * 4. onStateChange recebe (isVerifying, errorMessage, successMessage)
 */
private suspend fun attemptUnlock(
    password: String,
    authManager: AuthManager,
    blockingSessionManager: BlockingSessionManager,
    context: android.content.Context,
    onStateChange: (isVerifying: Boolean, error: String?, success: String?) -> Unit
) {
    onStateChange(true, null, null)

    val successMsg = context.getString(R.string.block_notice_unlock_success)
    val wrongPassMsg = context.getString(R.string.sessions_wrong_password)
    val noSessionMsg = context.getString(R.string.block_notice_no_active_session)
    val pomodoroMsg = context.getString(R.string.block_notice_pomodoro_cannot_stop)
    val timeMsg = context.getString(R.string.block_notice_time_cannot_revoke)

    val result = withContext(Dispatchers.IO) {
        try {
            // 1. Verifica senha
            if (!authManager.verifyPassword(password)) {
                return@withContext Pair(false, wrongPassMsg)
            }

            // 2. Busca sessão ativa
            val sessions = blockingSessionManager.activeSessionsFlow.first()
            if (sessions.isEmpty()) {
                return@withContext Pair(false, noSessionMsg)
            }

            // 3. Tenta encerrar cada sessão ativa (respeitando regras)
            var anyEnded = false
            var errorMsg: String? = null
            for (session in sessions) {
                when {
                    session.sessionType == "POMODORO" -> {
                        errorMsg = pomodoroMsg
                    }
                    session.sessionType == "TIME" &&
                        blockingSessionManager.isCurrentlyInBlockingWindow(session) -> {
                        errorMsg = timeMsg
                    }
                    else -> {
                        blockingSessionManager.endSession(session.id)
                        anyEnded = true
                    }
                }
            }

            if (anyEnded) {
                Pair(true, successMsg)
            } else {
                Pair(false, errorMsg ?: noSessionMsg)
            }
        } catch (e: Throwable) {
            FocusGuardLogger.logError("BlockNotice", "Erro ao desbloquear", e)
            Pair(false, e.localizedMessage ?: "Erro desconhecido")
        }
    }

    if (result.first) {
        onStateChange(false, null, result.second)
    } else {
        onStateChange(false, result.second, null)
    }
}
