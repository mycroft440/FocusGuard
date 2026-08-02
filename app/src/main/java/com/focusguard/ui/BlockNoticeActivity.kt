package com.focusguard.ui

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import androidx.core.view.doOnPreDraw
import com.focusguard.R
import com.focusguard.manager.BlockingSessionManager
import com.focusguard.security.AuthManager
import com.focusguard.service.BlockingAccessibilityService
import com.focusguard.ui.compose.theme.AccentCyan
import com.focusguard.ui.compose.theme.DangerRed
import com.focusguard.ui.compose.theme.DarkBg
import com.focusguard.ui.compose.theme.DarkSurface
import com.focusguard.ui.compose.theme.FocusGuardTheme
import com.focusguard.ui.compose.theme.SuccessGreen
import com.focusguard.ui.compose.theme.TextHint
import com.focusguard.ui.compose.theme.TextPrimary
import com.focusguard.ui.compose.theme.TextSecondary
import com.focusguard.utils.FocusGuardLogger
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@AndroidEntryPoint
class BlockNoticeActivity : ComponentActivity() {

    @Inject lateinit var authManager: AuthManager
    @Inject lateinit var blockingSessionManager: BlockingSessionManager

    private var strictBlock = false
    private var redirectBrowserPackage: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val browserPackage = redirectBrowserPackage
                when {
                    strictBlock -> Unit
                    browserPackage != null -> redirectBlockedWebsite(browserPackage)
                    else -> goHome()
                }
            }
        })

        showBlockNotice(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        showBlockNotice(intent)
    }

    private fun showBlockNotice(sourceIntent: Intent) {
        strictBlock = sourceIntent.getBooleanExtra(
            BlockingAccessibilityService.EXTRA_STRICT_BLOCK,
            false
        )
        val blockedPackage = sourceIntent.getStringExtra(
            BlockingAccessibilityService.EXTRA_BLOCKED_PACKAGE
        )
        val blockedDomain = sourceIntent.getStringExtra(
            BlockingAccessibilityService.EXTRA_BLOCKED_DOMAIN
        )
        redirectBrowserPackage = sourceIntent.getStringExtra(
            BlockingAccessibilityService.EXTRA_REDIRECT_BROWSER_PACKAGE
        )?.takeIf(String::isNotBlank)
        val browserPackageForRedirect = redirectBrowserPackage

        setContent {
            FocusGuardTheme {
                BlockNoticeContent(
                    strictBlock = strictBlock,
                    blockedPackage = blockedPackage,
                    blockedDomain = blockedDomain,
                    redirectBrowserPackage = browserPackageForRedirect,
                    authManager = authManager,
                    blockingSessionManager = blockingSessionManager,
                    onGoHome = ::goHome,
                    onRedirectBlockedWebsite = ::redirectBlockedWebsite,
                    onGoToPomodoroLock = ::goToPomodoroLock
                )
            }
        }

        window.decorView.doOnPreDraw {
            val detectedAt = sourceIntent.getLongExtra(
                BlockingAccessibilityService.EXTRA_BLOCK_DETECTED_ELAPSED_REALTIME,
                0L
            )
            if (detectedAt > 0L) {
                FocusGuardLogger.log(
                    "BlockNotice",
                    "Tela pronta em ${SystemClock.elapsedRealtime() - detectedAt}ms"
                )
            }
            sendBroadcast(
                Intent(BlockingAccessibilityService.ACTION_BLOCK_NOTICE_READY)
                    .setPackage(packageName)
            )
        }
    }

    private fun redirectBlockedWebsite(browserPackageName: String) {
        val redirected = runCatching {
            startActivity(
                BlockingAccessibilityService.createSafeRedirectIntent(browserPackageName)
            )
            true
        }.getOrElse { error ->
            FocusGuardLogger.logError(
                "BlockNotice",
                "Falha ao redirecionar site bloqueado para o Google",
                error
            )
            false
        }

        if (strictBlock) {
            goToPomodoroLock()
        } else if (redirected) {
            finish()
        } else {
            goHome()
        }
    }

    private fun goToPomodoroLock() {
        startActivity(
            Intent(this, PomodoroLockActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
            }
        )
        finish()
    }

    private fun goHome() {
        startActivity(
            Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        )
        finish()
    }
}

@Composable
private fun BlockNoticeContent(
    strictBlock: Boolean,
    blockedPackage: String?,
    blockedDomain: String?,
    redirectBrowserPackage: String?,
    authManager: AuthManager,
    blockingSessionManager: BlockingSessionManager,
    onGoHome: () -> Unit,
    onRedirectBlockedWebsite: (String) -> Unit,
    onGoToPomodoroLock: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    var showUnlockDialog by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var verifying by remember { mutableStateOf(false) }
    var unlocked by remember { mutableStateOf(false) }

    LaunchedEffect(
        strictBlock,
        redirectBrowserPackage,
        showUnlockDialog,
        unlocked
    ) {
        val shouldRedirectWebsite = redirectBrowserPackage != null &&
            !unlocked &&
            (strictBlock || !showUnlockDialog)
        when {
            shouldRedirectWebsite -> {
                delay(BlockingAccessibilityService.WEBSITE_BLOCK_NOTICE_DURATION_MILLIS)
                onRedirectBlockedWebsite(redirectBrowserPackage)
            }
            strictBlock -> {
                delay(BlockingAccessibilityService.WEBSITE_BLOCK_NOTICE_DURATION_MILLIS)
                onGoToPomodoroLock()
            }
        }
    }

    LaunchedEffect(showUnlockDialog) {
        if (showUnlockDialog) {
            delay(100L)
            focusRequester.requestFocus()
        }
    }

    Box(
        modifier = Modifier.fillMaxSize().background(DarkBg),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Surface(
                color = AccentCyan.copy(alpha = 0.1f),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.size(100.dp),
                border = BorderStroke(2.dp, AccentCyan)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(R.drawable.ic_shield),
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                        tint = AccentCyan
                    )
                }
            }

            Spacer(Modifier.height(28.dp))
            Text(
                text = if (blockedDomain != null) "Site bloqueado pelo FocusGuard"
                else "App bloqueado pelo FocusGuard",
                color = TextPrimary,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = blockedDomain ?: blockedPackage ?: "Mantenha o foco em seus objetivos.",
                color = TextSecondary,
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
            if (redirectBrowserPackage != null && !unlocked) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Redirecionando para o Google…",
                    color = TextHint,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(Modifier.height(28.dp))

            if (unlocked) {
                Surface(
                    color = SuccessGreen.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, SuccessGreen)
                ) {
                    Text(
                        text = stringResource(R.string.block_notice_unlock_success),
                        color = SuccessGreen,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(16.dp),
                        textAlign = TextAlign.Center
                    )
                }
                LaunchedEffect(Unit) {
                    delay(600L)
                    onGoHome()
                }
            } else if (strictBlock) {
                Text(
                    text = "O bloqueio rigoroso só termina quando o Pomodoro expirar.",
                    color = TextHint,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
            } else {
                Button(
                    onClick = { showUnlockDialog = true },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Default.LockOpen, contentDescription = null, tint = DarkBg)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.block_notice_unlock_with_password),
                        color = DarkBg,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }

    if (showUnlockDialog) {
        fun submit() {
            if (password.isBlank() || verifying) return
            scope.launch {
                verifying = true
                error = null
                try {
                    when (
                        attemptUnlock(
                            password = password,
                            blockedPackage = blockedPackage,
                            blockedDomain = blockedDomain,
                            authManager = authManager,
                            sessionManager = blockingSessionManager
                        )
                    ) {
                        UnlockResult.SUCCESS -> {
                            showUnlockDialog = false
                            unlocked = true
                        }
                        UnlockResult.WRONG_PASSWORD ->
                            error = "Senha incorreta."
                        UnlockResult.POMODORO ->
                            error = "O Pomodoro rigoroso não pode ser interrompido."
                        UnlockResult.TIME_SESSION ->
                            error = "Este bloqueio por tempo ainda está ativo."
                        UnlockResult.NO_REVOCABLE_SESSION ->
                            error = "Nenhuma sessão revogável corresponde a este bloqueio."
                        UnlockResult.FAILURE ->
                            error = "Não foi possível encerrar o bloqueio."
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } finally {
                    verifying = false
                }
            }
        }

        AlertDialog(
            onDismissRequest = {
                if (!verifying) {
                    showUnlockDialog = false
                    password = ""
                    error = null
                }
            },
            containerColor = DarkSurface,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Lock, contentDescription = null, tint = AccentCyan)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.block_notice_unlock_title),
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            text = {
                Column {
                    Text(
                        stringResource(R.string.block_notice_unlock_desc),
                        color = TextSecondary,
                        fontSize = 14.sp
                    )
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = {
                            password = it
                            error = null
                        },
                        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                        singleLine = true,
                        enabled = !verifying,
                        isError = error != null,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedBorderColor = AccentCyan
                        )
                    )
                    error?.let {
                        Text(
                            text = it,
                            color = DangerRed,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { submit() },
                    enabled = password.isNotBlank() && !verifying,
                    colors = ButtonDefaults.buttonColors(containerColor = AccentCyan)
                ) {
                    if (verifying) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = DarkBg
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(stringResource(R.string.sessions_confirm), color = DarkBg)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { if (!verifying) showUnlockDialog = false }
                ) {
                    Text(stringResource(R.string.cancel), color = TextSecondary)
                }
            }
        )
    }
}

private enum class UnlockResult {
    SUCCESS,
    WRONG_PASSWORD,
    POMODORO,
    TIME_SESSION,
    NO_REVOCABLE_SESSION,
    FAILURE
}

private suspend fun attemptUnlock(
    password: String,
    blockedPackage: String?,
    blockedDomain: String?,
    authManager: AuthManager,
    sessionManager: BlockingSessionManager
): UnlockResult {
    return try {
        when (
            sessionManager.unlockPasswordProtectedLimit(
                password = password,
                blockedPackage = blockedPackage,
                blockedDomain = blockedDomain
            )
        ) {
            BlockingSessionManager.LimitUnlockResult.UNLOCKED ->
                return UnlockResult.SUCCESS
            BlockingSessionManager.LimitUnlockResult.FAILED ->
                return UnlockResult.FAILURE
            BlockingSessionManager.LimitUnlockResult.WRONG_PASSWORD,
            BlockingSessionManager.LimitUnlockResult.NOT_FOUND -> Unit
        }

        if (!authManager.verifyPassword(password)) return UnlockResult.WRONG_PASSWORD
        val sessionId = sessionManager.findResponsibleSessionId(blockedPackage, blockedDomain)
            ?: return UnlockResult.NO_REVOCABLE_SESSION
        when (sessionManager.endSessionAndWait(sessionId)) {
            BlockingSessionManager.EndSessionResult.ENDED -> UnlockResult.SUCCESS
            BlockingSessionManager.EndSessionResult.POMODORO_NOT_REVOCABLE -> UnlockResult.POMODORO
            BlockingSessionManager.EndSessionResult.TIME_NOT_REVOCABLE -> UnlockResult.TIME_SESSION
            BlockingSessionManager.EndSessionResult.NOT_FOUND -> UnlockResult.NO_REVOCABLE_SESSION
            BlockingSessionManager.EndSessionResult.FAILED -> UnlockResult.FAILURE
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        FocusGuardLogger.logError("BlockNotice", "Erro ao desbloquear", error)
        UnlockResult.FAILURE
    }
}
