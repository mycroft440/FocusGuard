package com.focusguard.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.doOnPreDraw
import androidx.lifecycle.lifecycleScope
import com.focusguard.R
import com.focusguard.manager.BlockingSessionManager
import com.focusguard.security.AppUnlockBiometricAuthenticator
import com.focusguard.security.AuthManager
import com.focusguard.security.CurtainDestinationReadyCoordinator
import com.focusguard.security.IntruderAttemptCaptureController
import com.focusguard.security.PasswordAppUnlockMode
import com.focusguard.security.PasswordAppUnlockStore
import com.focusguard.security.SafeSurfaceReadinessPolicy
import com.focusguard.service.AppBlockSurfaceResolver
import com.focusguard.service.BlockingAccessibilityService
import com.focusguard.ui.compose.screens.PasswordProtectedTargetUnlockPanel
import com.focusguard.ui.compose.theme.AccentCyan
import com.focusguard.ui.compose.theme.DangerRed
import com.focusguard.ui.compose.theme.DarkBg
import com.focusguard.ui.compose.theme.FocusGuardTheme
import com.focusguard.ui.compose.theme.SuccessGreen
import com.focusguard.ui.compose.theme.TextPrimary
import com.focusguard.ui.compose.theme.TextSecondary
import com.focusguard.utils.AppLabelCache
import com.focusguard.utils.FocusGuardLogger
import com.focusguard.utils.WebsiteBlocker
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Exclusive authentication surface for PASSWORD-session targets.
 *
 * This Activity owns target password, pattern, biometric fallback, the one-visit
 * grant, and the lifecycle of the optional intruder selfie for app attempts.
 * Generic hard-block UI has no access to those states. Cancelling authentication
 * exits to Home so the protected app/browser is no longer visible behind the
 * authentication surface.
 */
@AndroidEntryPoint
class PasswordUnlockActivity : AppCompatActivity() {

    @Inject lateinit var authManager: AuthManager
    @Inject lateinit var blockingSessionManager: BlockingSessionManager

    private lateinit var intruderCaptureController: IntruderAttemptCaptureController
    private val presentation = PasswordUnlockPresentationState()
    private var noticeDrawn = false
    // Se o desenho que marcou noticeDrawn foi confirmado na tela (frame commit).
    private var drawnFrameCommitted = false
    private var activityResumed = false
    private var windowFocused = false
    private var freshFrameGeneration = 0L
    private var authenticationReady by mutableStateOf(false)

    // Aberta direto pelo serviço (sem o roteador), a tela confere o dono do bloqueio
    // antes de liberar senha ou digital: se outro bloqueio mais forte assumiu o app
    // desde o último refresh do serviço, o pedido volta para o roteador e a senha nunca
    // aparece. A cortina não espera essa checagem: ela protege o app, e esta tela opaca
    // já o cobre; assim a consulta ao banco sai do caminho até a tela aparecer.
    private var ownerVerified by mutableStateOf(true)
    private var ownerCheckJob: Job? = null

    // Accessibility can send more than one intent while the same unlock surface is
    // visible. Keep a stable access id for apps and websites. Intruder capture is
    // armed only for actual app targets; a website attempt still needs the stable id
    // so duplicate browser events do not recreate the credential panel.
    private var accessAttemptId = 0L
    private var accessAttemptTargetKey: String? = null
    private var accessAttemptBackgrounded = false
    private var accessAttemptAuthenticated = false
    private var intruderCaptureArmedForAttempt = false

    // Geração e pedido cuja cortina ainda não saiu; ao sair, a digital/senha libera.
    private var awaitingCurtainHidden: Pair<Long, Long>? = null
    private val curtainHiddenListener =
        CurtainDestinationReadyCoordinator.CurtainHiddenListener { generation ->
            window.decorView.post {
                val (awaitedGeneration, request) = awaitingCurtainHidden
                    ?: return@post
                if (generation != awaitedGeneration || isFinishing || isDestroyed) return@post
                awaitingCurtainHidden = null
                if (presentation.finishCurtainSettle(request)) authenticationReady = true
            }
        }

    private companion object {
        const val FRAME_COMMIT_ACK_TIMEOUT_MILLIS = 100L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CurtainDestinationReadyCoordinator.setCurtainHiddenListener(curtainHiddenListener)
        intruderCaptureController = IntruderAttemptCaptureController(this, authManager)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                goHome()
            }
        })
        showPasswordUnlock(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        showPasswordUnlock(intent)
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        acknowledgePendingNoticeIfPresented()
        startIntruderCaptureIfVerified(accessAttemptId)
    }

    override fun onDestroy() {
        CurtainDestinationReadyCoordinator.clearCurtainHiddenListener(curtainHiddenListener)
        ownerCheckJob?.cancel()
        super.onDestroy()
    }

    override fun onPause() {
        activityResumed = false
        super.onPause()
    }

    override fun onStop() {
        if (!accessAttemptAuthenticated) {
            accessAttemptBackgrounded = true
        }
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        windowFocused = hasFocus
        if (hasFocus) acknowledgePendingNoticeIfPresented()
    }

    private fun showPasswordUnlock(sourceIntent: Intent) {
        val packageName = sourceIntent.getStringExtra(
            BlockingAccessibilityService.EXTRA_BLOCKED_PACKAGE
        )?.takeIf(String::isNotBlank)
        val blockedDomain = sourceIntent.getStringExtra(
            BlockingAccessibilityService.EXTRA_BLOCKED_DOMAIN
        )?.takeIf(String::isNotBlank)
        val curtainGeneration = sourceIntent.getLongExtra(
            BlockingAccessibilityService.EXTRA_CURTAIN_GENERATION,
            0L
        )
        val currentAccessAttemptId = beginOrContinueAccessAttempt(packageName, blockedDomain)
        val newAttempt = presentation.present(currentAccessAttemptId, curtainGeneration)
        val attemptId = presentation.attemptId
        val curtainRequestId = presentation.curtainRequestId
        val pendingGeneration = presentation.pendingCurtainGeneration
        freshFrameGeneration = 0L
        if (newAttempt) noticeDrawn = false
        authenticationReady = presentation.authenticationReady
        if (newAttempt) verifyPasswordOwnerIfNeeded(sourceIntent, packageName, blockedDomain)

        // Accessibility can repeat this request for the same visible access.
        // Keep its Compose state (and BiometricPrompt) instead of replacing the
        // panel with a spinner and launching authentication again.
        if (newAttempt) {
            val targetLabel = resolveAppLabel(packageName)
                ?: blockedDomain?.let(WebsiteBlocker::displayRule)
            setContent {
                FocusGuardTheme {
                    key(attemptId) {
                        PasswordUnlockContent(
                            blockAttemptId = attemptId,
                            blockedPackage = packageName,
                            blockedDomain = blockedDomain,
                            targetLabel = targetLabel,
                            authenticationReady = authenticationReady && ownerVerified,
                            authManager = authManager,
                            blockingSessionManager = blockingSessionManager,
                            onAuthenticationSucceeded = {
                                if (intruderCaptureArmedForAttempt) {
                                    intruderCaptureController.markAuthenticated(
                                        currentAccessAttemptId
                                    )
                                }
                                if (currentAccessAttemptId == accessAttemptId) {
                                    accessAttemptAuthenticated = true
                                }
                            },
                            onCredentialRejected = {
                                if (intruderCaptureArmedForAttempt) {
                                    intruderCaptureController.markCredentialRejected(
                                        currentAccessAttemptId
                                    )
                                }
                            },
                            onUnlocked = {
                                returnToAuthenticatedTarget(packageName, blockedDomain)
                            },
                            onCancelled = ::goHome
                        )
                    }
                }
            }
        }

        val onFramePresented: () -> Unit = onFramePresented@{
            if (curtainRequestId != presentation.curtainRequestId) return@onFramePresented
            noticeDrawn = true
            if (
                presentation.pendingCurtainGeneration == pendingGeneration &&
                activityResumed &&
                window.decorView.isShown
            ) {
                freshFrameGeneration = pendingGeneration
            }
            val detectedAt = sourceIntent.getLongExtra(
                BlockingAccessibilityService.EXTRA_BLOCK_EVENT_UPTIME_MILLIS,
                0L
            )
            if (detectedAt > 0L) {
                FocusGuardLogger.log(
                    "PasswordUnlock",
                    "Evento→primeiro desenho=${SystemClock.uptimeMillis() - detectedAt}ms"
                )
            }
            acknowledgePendingNoticeIfPresented()
        }
        // No Android 10+ o aviso sai quando o quadro foi de fato para a tela (frame
        // commit), não antes de desenhar: o serviço então solta a cortina com uma
        // espera curta em vez de adivinhar com 160 ms.
        window.decorView.doOnPreDraw {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                var presented = false
                window.decorView.viewTreeObserver.registerFrameCommitCallback {
                    if (!presented) {
                        presented = true
                        drawnFrameCommitted = true
                        onFramePresented()
                    }
                }
                // Reserva: se nenhum quadro chegar a ser confirmado (tela parada), o
                // aviso sai assim mesmo, sem a marca de commit, e o serviço volta à
                // espera cheia em vez de segurar a cortina até o failsafe de 5 s.
                window.decorView.postDelayed({
                    if (!presented) {
                        presented = true
                        drawnFrameCommitted = false
                        onFramePresented()
                    }
                }, FRAME_COMMIT_ACK_TIMEOUT_MILLIS)
            } else {
                drawnFrameCommitted = false
                onFramePresented()
            }
        }
        window.decorView.invalidate()

        // onNewIntent can start a genuinely new access while this singleTop Activity
        // is already resumed. Do not wait for another lifecycle callback to stage it.
        if (activityResumed) startIntruderCaptureIfVerified(currentAccessAttemptId)
    }

    private fun beginOrContinueAccessAttempt(
        packageName: String?,
        blockedDomain: String?
    ): Long {
        val targetKey = packageName?.takeIf(String::isNotBlank)?.let { "app:$it" }
            ?: blockedDomain?.takeIf(String::isNotBlank)?.let { "site:${WebsiteBlocker.normalizeRule(it)}" }
            ?: return 0L
        val sameVisibleAttempt =
            accessAttemptId > 0L &&
                accessAttemptTargetKey == targetKey &&
                !accessAttemptBackgrounded &&
                !accessAttemptAuthenticated
        if (sameVisibleAttempt) return accessAttemptId

        accessAttemptId += 1L
        accessAttemptTargetKey = targetKey
        accessAttemptBackgrounded = false
        accessAttemptAuthenticated = false
        intruderCaptureArmedForAttempt = !packageName.isNullOrBlank()
        if (intruderCaptureArmedForAttempt) {
            intruderCaptureController.beginAttempt(accessAttemptId)
        }
        return accessAttemptId
    }

    // Só fotografa depois de confirmar o dono: uma tentativa que volta ao roteador
    // (tempo/limite) vira a tela genérica e não é um acesso à senha.
    private fun startIntruderCaptureIfVerified(attemptId: Long) {
        if (
            ownerVerified &&
            attemptId > 0L &&
            intruderCaptureArmedForAttempt &&
            !accessAttemptAuthenticated
        ) {
            intruderCaptureController.startCaptureIfEligible(attemptId)
        }
    }

    private fun verifyPasswordOwnerIfNeeded(
        sourceIntent: Intent,
        packageName: String?,
        blockedDomain: String?
    ) {
        ownerCheckJob?.cancel()
        ownerCheckJob = null
        val needsCheck = sourceIntent.getBooleanExtra(
            BlockingAccessibilityService.EXTRA_VERIFY_PASSWORD_OWNER,
            false
        )
        if (!needsCheck || (packageName == null && blockedDomain == null)) {
            ownerVerified = true
            return
        }
        ownerVerified = false
        ownerCheckJob = lifecycleScope.launch {
            val allowed = try {
                if (packageName != null) {
                    AppBlockSurfaceResolver(
                        context = applicationContext,
                        sessionManager = blockingSessionManager
                    ).resolveAttempt(blockedPackage = packageName).allowsPasswordVisit
                } else {
                    // Mesma decisão do roteador (websiteSurfaceFor): só PASSWORD fica aqui.
                    blockingSessionManager.activeWebsiteProtection(blockedDomain) ==
                        BlockingSessionManager.ActiveWebsiteProtection.PASSWORD
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                FocusGuardLogger.logError(
                    "PasswordUnlock",
                    "Falha ao conferir o dono do bloqueio de ${packageName ?: blockedDomain}",
                    error
                )
                false
            }
            if (isFinishing || isDestroyed) return@launch
            if (allowed) {
                ownerVerified = true
                if (activityResumed) startIntruderCaptureIfVerified(accessAttemptId)
            } else {
                rerouteThroughBlockRouter(sourceIntent)
            }
        }
    }

    /** O dono não é mais uma sessão PASSWORD: o roteador escolhe a tela certa. */
    private fun rerouteThroughBlockRouter(sourceIntent: Intent) {
        val routed = runCatching {
            startActivity(
                Intent(this, BlockNoticeActivity::class.java).apply {
                    sourceIntent.extras?.let { putExtras(it) }
                    removeExtra(BlockingAccessibilityService.EXTRA_VERIFY_PASSWORD_OWNER)
                    addFlags(
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION
                    )
                }
            )
        }.isSuccess
        if (routed) finish() else goHome()
    }

    private fun acknowledgePendingNoticeIfPresented(): Boolean {
        val generation = presentation.pendingCurtainGeneration
        // No pending acknowledgement can also mean that its settle timer is
        // still running. Focus/resume callbacks must not bypass that timer.
        if (generation <= 0L) return false
        val decor = window.decorView
        val ready = SafeSurfaceReadinessPolicy.decide(
            alreadyDrawn = noticeDrawn,
            freshFrameAfterRequest = freshFrameGeneration == generation,
            lifecycleResumed = activityResumed,
            decorShown = decor.isShown,
            windowFocused = windowFocused
        ) == SafeSurfaceReadinessPolicy.Decision.ACK_NOW
        if (!ready) return false

        presentation.acknowledgeCurtain()
        freshFrameGeneration = 0L
        val acknowledgedRequest = presentation.curtainRequestId
        awaitingCurtainHidden = generation to acknowledgedRequest
        CurtainDestinationReadyCoordinator.notifyReady(
            generation,
            frameCommitted = drawnFrameCommitted
        )

        // The target panel auto-opens BiometricPrompt, which must never be born
        // underneath the touch-consuming curtain. The service reports the moment the
        // curtain is hidden (curtainHiddenListener); this timer is only the fallback
        // for when that report never comes.
        decor.postDelayed(
            {
                if (
                    !isFinishing &&
                    !isDestroyed &&
                    presentation.finishCurtainSettle(acknowledgedRequest)
                ) {
                    authenticationReady = true
                }
            },
            BlockingAccessibilityService.SAFE_WINDOW_SETTLE_MILLIS + 80L
        )
        return true
    }

    // Normalmente já pré-carregado pelo serviço (sem chamada ao PackageManager aqui).
    private fun resolveAppLabel(packageName: String?): String? =
        AppLabelCache.get(this, packageName)

    /**
     * A successful target credential grants one visit without deleting the block.
     * The intercepted app/browser task is already intact immediately behind this
     * authentication surface. Never relaunch it here: moving this task to the back
     * preserves the exact deep-link/page/back-stack that was intercepted.
     */
    private fun returnToAuthenticatedTarget(
        packageName: String?,
        blockedDomain: String?
    ) {
        val target = packageName?.takeIf(String::isNotBlank)
            ?: blockedDomain?.takeIf(String::isNotBlank)
        if (target == null) {
            goHome()
            return
        }

        val movedToBack = runCatching {
            moveTaskToBack(true)
        }.onFailure { error ->
            FocusGuardLogger.logError(
                "PasswordUnlock",
                "Falha ao devolver foco ao alvo autenticado $target",
                error
            )
        }.getOrDefault(false)

        if (!movedToBack) {
            FocusGuardLogger.log(
                "PasswordUnlock",
                "Task de autenticação não pôde ser movida para trás para $target"
            )
        }
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
private fun PasswordUnlockContent(
    blockAttemptId: Long,
    blockedPackage: String?,
    blockedDomain: String?,
    targetLabel: String?,
    authenticationReady: Boolean,
    authManager: AuthManager,
    blockingSessionManager: BlockingSessionManager,
    onAuthenticationSucceeded: () -> Unit,
    onCredentialRejected: () -> Unit,
    onUnlocked: () -> Unit,
    onCancelled: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = remember(context) { PasswordAppUnlockStore(context) }
    val targetId = remember(blockAttemptId, blockedPackage, blockedDomain) {
        PasswordAppUnlockStore.targetIdForPackage(blockedPackage)
            ?: store.resolveWebsiteTargetId(blockedDomain)
    }
    val config = remember(blockAttemptId, targetId) {
        store.getTarget(targetId)
    }
    var unlocked by remember(blockAttemptId) { mutableStateOf(false) }
    var biometricAvailability by remember(blockAttemptId) {
        mutableStateOf(AppUnlockBiometricAuthenticator.availability(context))
    }
    val biometricEnrollmentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        biometricAvailability = AppUnlockBiometricAuthenticator.availability(context)
    }
    val biometricOnlyNeedsEnrollment =
        config?.mode == PasswordAppUnlockMode.BIOMETRIC_ONLY &&
            biometricAvailability != AppUnlockBiometricAuthenticator.Availability.AVAILABLE

    // A malformed/missing PASSWORD target must not strand the user on a dead
    // authentication screen and must never fall through to the generic block UI.
    LaunchedEffect(blockAttemptId, targetId, config) {
        if (targetId.isNullOrBlank() || config == null) {
            onCancelled()
        }
    }

    // Re-check after the opaque curtain hands control to this Activity. This
    // closes the race where the user removes the enrolled biometric after the
    // block was configured but before the next protected-target attempt.
    LaunchedEffect(blockAttemptId, authenticationReady) {
        if (authenticationReady) {
            biometricAvailability = AppUnlockBiometricAuthenticator.availability(context)
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
                text = stringResource(R.string.password_unlock_screen_title),
                color = TextPrimary,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = targetLabel ?: blockedPackage ?: blockedDomain.orEmpty(),
                color = TextSecondary,
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.password_unlock_screen_subtitle),
                color = TextSecondary,
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(28.dp))

            when {
                unlocked -> {
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
                    // A liberação já foi publicada antes daqui: nada espera por uma
                    // pausa, então o app volta na hora (antes eram 180 ms parados).
                    LaunchedEffect(blockAttemptId) {
                        onUnlocked()
                    }
                }
                targetId.isNullOrBlank() || config == null -> {
                    Text(
                        text = stringResource(R.string.password_unlock_configuration_missing),
                        color = DangerRed,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                }
                !authenticationReady -> {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.password_unlock_preparing),
                        color = TextSecondary,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                }
                biometricOnlyNeedsEnrollment -> {
                    Text(
                        text = stringResource(
                            R.string.password_app_unlock_biometric_blocked_until_restored
                        ),
                        color = DangerRed,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = {
                            biometricEnrollmentLauncher.launch(
                                AppUnlockBiometricAuthenticator.createEnrollmentIntent(context)
                            )
                        },
                        modifier = Modifier.fillMaxWidth().height(50.dp)
                    ) {
                        Text(
                            stringResource(
                                R.string.password_app_unlock_biometric_reactivate_action
                            )
                        )
                    }
                }
                else -> {
                    PasswordProtectedTargetUnlockPanel(
                        blockedPackage = blockedPackage,
                        blockedDomain = blockedDomain,
                        authManager = authManager,
                        sessionManager = blockingSessionManager,
                        onUnlocked = {
                            onAuthenticationSucceeded()
                            unlocked = true
                        },
                        onCredentialRejected = onCredentialRejected,
                        onCancelled = onCancelled
                    )
                }
            }
        }
    }
}
