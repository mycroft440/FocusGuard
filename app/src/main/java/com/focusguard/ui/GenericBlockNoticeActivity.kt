package com.focusguard.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import com.focusguard.security.AuthManager
import com.focusguard.security.CurtainDestinationReadyCoordinator
import com.focusguard.security.SafeSurfaceReadinessPolicy
import com.focusguard.service.BlockingAccessibilityService
import com.focusguard.ui.compose.screens.LimitPasswordUnlockPanel
import com.focusguard.ui.compose.theme.AccentCyan
import com.focusguard.ui.compose.theme.DarkBg
import com.focusguard.ui.compose.theme.FocusGuardTheme
import com.focusguard.ui.compose.theme.TextHint
import com.focusguard.ui.compose.theme.TextPrimary
import com.focusguard.ui.compose.theme.TextSecondary
import com.focusguard.usage.BlockReturnNavigator
import com.focusguard.usage.UsageImpactRouter
import com.focusguard.utils.AppLabelCache
import com.focusguard.utils.FocusGuardLogger
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * App/generic fail-closed block surface. Its only way out is the master password of
 * a daily limit configured with "Desbloquear com a senha mestra" (the limit is
 * released until midnight); every other owner keeps the app closed.
 *
 * PASSWORD
 * credentials are owned by [PasswordUnlockActivity]. Keeping this Activity free
 * from website URL/redirection behavior prevents the app-block UI from becoming
 * a second owner of the website pipeline.
 */
@AndroidEntryPoint
class GenericBlockNoticeActivity : AppCompatActivity() {

    @Inject lateinit var authManager: AuthManager
    @Inject lateinit var blockingSessionManager: BlockingSessionManager

    // Nome do app bloqueado; o pacote aparece só enquanto o nome não chega.
    private var blockedAppLabel by mutableStateOf<String?>(null)
    private var labelJob: Job? = null

    private var noticeDrawn = false
    // Se o desenho que marcou noticeDrawn foi confirmado na tela (frame commit).
    private var drawnFrameCommitted = false
    private var activityResumed = false
    private var windowFocused = false
    private var pendingCurtainGeneration = 0L
    private var freshFrameGeneration = 0L

    // A usage-limit block can reuse this singleTop Activity many times. Keep an
    // attempt id so every interception gets its own impact-route decision.
    private var blockAttemptId = 0L
    private var pendingUsageImpactAttemptId = 0L
    private var pendingUsageImpactPackage: String? = null
    private var pendingUsageImpactAttemptAtMillis = 0L
    private var usageImpactJob: Job? = null

    private var currentBlockedPackage: String? = null
    private var currentAttemptAtMillis = 0L
    private var returning = false

    private data class NoticePayload(
        val blockedPackage: String?,
        /** Hora (relógio de parede) do evento que disparou o bloqueio. */
        val attemptAtMillis: Long
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                goBack()
            }
        })
        showBlockNotice(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        showBlockNotice(intent)
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        acknowledgePendingNoticeIfPresented()
        routeToUsageImpactIfReady()
    }

    override fun onPause() {
        activityResumed = false
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        windowFocused = hasFocus
        if (hasFocus) {
            acknowledgePendingNoticeIfPresented()
            routeToUsageImpactIfReady()
        }
    }

    override fun onDestroy() {
        usageImpactJob?.cancel()
        usageImpactJob = null
        super.onDestroy()
    }

    private fun showBlockNotice(sourceIntent: Intent) {
        val curtainGeneration = sourceIntent.getLongExtra(
            BlockingAccessibilityService.EXTRA_CURTAIN_GENERATION,
            0L
        )
        val payload = NoticePayload(
            blockedPackage = sourceIntent.getStringExtra(
                BlockingAccessibilityService.EXTRA_BLOCKED_PACKAGE
            )?.takeIf(String::isNotBlank),
            attemptAtMillis = attemptWallClockMillis(
                eventUptimeMillis = sourceIntent.getLongExtra(
                    BlockingAccessibilityService.EXTRA_BLOCK_EVENT_UPTIME_MILLIS,
                    0L
                ),
                nowUptimeMillis = SystemClock.uptimeMillis(),
                nowWallClockMillis = System.currentTimeMillis()
            )
        )
        currentBlockedPackage = payload.blockedPackage
        currentAttemptAtMillis = payload.attemptAtMillis
        returning = false

        pendingCurtainGeneration = curtainGeneration
        freshFrameGeneration = 0L

        val attemptId = ++blockAttemptId
        scheduleUsageImpactRoute(
            payload = payload,
            attemptId = attemptId,
            knownDecision = if (sourceIntent.hasExtra(EXTRA_SHOW_USAGE_IMPACT)) {
                sourceIntent.getBooleanExtra(EXTRA_SHOW_USAGE_IMPACT, false)
            } else {
                null
            }
        )
        noticeDrawn = false
        drawnFrameCommitted = false

        loadBlockedAppLabel(payload.blockedPackage)
        val limitPasswordUnlock = payload.blockedPackage != null &&
            sourceIntent.getBooleanExtra(EXTRA_LIMIT_PASSWORD_UNLOCK, false)

        setContent {
            FocusGuardTheme {
                GenericBlockNoticeContent(
                    blockedPackage = payload.blockedPackage,
                    appLabel = blockedAppLabel,
                    limitPasswordUnlock = limitPasswordUnlock,
                    authManager = authManager,
                    blockingSessionManager = blockingSessionManager,
                    onLimitUnlocked = ::openUnlockedApp,
                    onBack = ::goBack
                )
            }
        }

        val onFramePresented: (Boolean) -> Unit = { frameCommitted ->
            noticeDrawn = true
            drawnFrameCommitted = frameCommitted
            if (
                pendingCurtainGeneration == curtainGeneration &&
                activityResumed &&
                window.decorView.isShown
            ) {
                freshFrameGeneration = curtainGeneration
            }
            val detectedAt = sourceIntent.getLongExtra(
                BlockingAccessibilityService.EXTRA_BLOCK_EVENT_UPTIME_MILLIS,
                0L
            )
            if (detectedAt > 0L) {
                FocusGuardLogger.log(
                    "GenericBlockNotice",
                    "Evento→primeiro desenho=${SystemClock.uptimeMillis() - detectedAt}ms"
                )
            }
            acknowledgePendingNoticeIfPresented()
            routeToUsageImpactIfReady()
        }
        // Como na tela de senha: no Android 10+ o aviso sai quando o quadro foi de fato
        // para a tela (frame commit), e o serviço solta a cortina com uma espera curta
        // em vez dos 160 ms de quando só se sabe que o desenho começou.
        window.decorView.doOnPreDraw {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                var presented = false
                window.decorView.viewTreeObserver.registerFrameCommitCallback {
                    if (!presented) {
                        presented = true
                        onFramePresented(true)
                    }
                }
                // Reserva: sem quadro confirmado (tela parada), o aviso sai assim mesmo,
                // sem a marca de commit, e o serviço usa a espera cheia.
                window.decorView.postDelayed({
                    if (!presented) {
                        presented = true
                        onFramePresented(false)
                    }
                }, FRAME_COMMIT_ACK_TIMEOUT_MILLIS)
            } else {
                onFramePresented(false)
            }
        }
        window.decorView.invalidate()
    }

    /**
     * Resolve whether this exact attempt came from an active non-password app
     * usage limit. Navigation waits for the curtain handshake so the impact screen
     * cannot race the instant opaque protection surface.
     */
    private fun scheduleUsageImpactRoute(
        payload: NoticePayload,
        attemptId: Long,
        /**
         * A decisão que o roteador já tomou para esta mesma tentativa. Refazer a
         * consulta (banco e eventos de uso) competia com o primeiro quadro desta tela
         * e atrasava a troca para o Impacto. Null: aberta sem o roteador; consulta.
         */
        knownDecision: Boolean?
    ) {
        usageImpactJob?.cancel()
        usageImpactJob = null
        pendingUsageImpactAttemptId = 0L
        pendingUsageImpactPackage = null

        val packageName = payload.blockedPackage
        if (packageName.isNullOrBlank()) return

        if (knownDecision != null) {
            if (!knownDecision) return
            pendingUsageImpactAttemptId = attemptId
            pendingUsageImpactPackage = packageName
            pendingUsageImpactAttemptAtMillis = payload.attemptAtMillis
            routeToUsageImpactIfReady()
            return
        }

        usageImpactJob = lifecycleScope.launch {
            val shouldShow = UsageImpactRouter.shouldShowForBlockedApp(
                this@GenericBlockNoticeActivity,
                packageName
            )
            if (
                !shouldShow ||
                attemptId != blockAttemptId ||
                isFinishing ||
                isDestroyed
            ) return@launch

            pendingUsageImpactAttemptId = attemptId
            pendingUsageImpactPackage = packageName
            pendingUsageImpactAttemptAtMillis = payload.attemptAtMillis
            routeToUsageImpactIfReady()
        }
    }

    private fun routeToUsageImpactIfReady() {
        val packageName = pendingUsageImpactPackage ?: return
        if (pendingUsageImpactAttemptId != blockAttemptId) return
        if (
            pendingCurtainGeneration > 0L ||
            !noticeDrawn ||
            !activityResumed ||
            !windowFocused ||
            isFinishing ||
            isDestroyed
        ) return

        pendingUsageImpactPackage = null
        pendingUsageImpactAttemptId = 0L
        usageImpactJob = null
        goToUsageImpact(packageName, pendingUsageImpactAttemptAtMillis)
    }

    private fun acknowledgePendingNoticeIfPresented(): Boolean {
        val generation = pendingCurtainGeneration
        if (generation <= 0L) {
            routeToUsageImpactIfReady()
            return false
        }
        val ready = SafeSurfaceReadinessPolicy.decide(
            alreadyDrawn = noticeDrawn,
            freshFrameAfterRequest = freshFrameGeneration == generation,
            lifecycleResumed = activityResumed,
            decorShown = window.decorView.isShown,
            windowFocused = windowFocused
        ) == SafeSurfaceReadinessPolicy.Decision.ACK_NOW
        if (!ready) return false

        pendingCurtainGeneration = 0L
        freshFrameGeneration = 0L
        CurtainDestinationReadyCoordinator.notifyReady(
            generation,
            frameCommitted = drawnFrameCommitted
        )
        routeToUsageImpactIfReady()
        return true
    }

    private fun goToUsageImpact(packageName: String, attemptAtMillis: Long) {
        startActivity(UsageImpactActivity.createIntent(this, packageName, attemptAtMillis))
        finish()
    }

    /**
     * O nome do app costuma já estar guardado (o serviço pré-carrega os apps
     * bloqueados); senão é lido fora da thread principal, sem atrasar o primeiro quadro.
     */
    private fun loadBlockedAppLabel(packageName: String?) {
        labelJob?.cancel()
        blockedAppLabel = packageName?.let(AppLabelCache::peek)
        if (packageName == null || blockedAppLabel != null) return
        labelJob = lifecycleScope.launch {
            val label = withContext(Dispatchers.IO) {
                AppLabelCache.get(applicationContext, packageName)
            }
            if (currentBlockedPackage == packageName) blockedAppLabel = label
        }
    }

    /**
     * A senha mestra liberou o limite até a meia-noite (a reconciliação já tirou o
     * app da lista de bloqueados): abre o app como o ícone abriria, de volta à tarefa
     * em que a pessoa estava.
     */
    private fun openUnlockedApp() {
        if (returning) return
        returning = true
        usageImpactJob?.cancel()
        pendingUsageImpactPackage = null
        val packageName = currentBlockedPackage
        val launch = packageName?.let {
            runCatching { packageManager.getLaunchIntentForPackage(it) }.getOrNull()
        }?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val opened = launch != null && runCatching { startActivity(launch) }
            .onFailure { error ->
                FocusGuardLogger.logError(
                    "GenericBlockNotice",
                    "Falha ao abrir $packageName liberado pela senha",
                    error
                )
            }
            .isSuccess
        if (!opened) runCatching { startActivity(BlockReturnNavigator.homeIntent()) }
        finish()
    }

    /** Volta à tela de antes da tentativa; sem app conhecido, à tela inicial. */
    private fun goBack() {
        if (returning) return
        returning = true
        usageImpactJob?.cancel()
        pendingUsageImpactPackage = null
        val blockedPackage = currentBlockedPackage
        if (blockedPackage == null) {
            runCatching { startActivity(BlockReturnNavigator.homeIntent()) }
            finish()
            return
        }
        val attemptAt = currentAttemptAtMillis
        lifecycleScope.launch {
            val destination = BlockReturnNavigator.resolve(
                context = this@GenericBlockNoticeActivity,
                blockedPackage = blockedPackage,
                attemptAtMs = attemptAt
            )
            BlockReturnNavigator.navigate(
                this@GenericBlockNoticeActivity,
                blockedPackage,
                destination
            )
            finish()
        }
    }

    internal companion object {
        /**
         * Decisão do roteador sobre abrir o Impacto nesta tentativa (bloqueio por tempo
         * ou limite sem senha). Ausente quando a tela foi aberta sem o roteador.
         */
        const val EXTRA_SHOW_USAGE_IMPACT = "SHOW_USAGE_IMPACT"

        /**
         * O dono da tentativa é um limite diário esgotado que libera com a senha
         * mestra: a tela oferece a senha em vez de dizer que não há senha.
         */
        const val EXTRA_LIMIT_PASSWORD_UNLOCK = "LIMIT_PASSWORD_UNLOCK"
        private const val FRAME_COMMIT_ACK_TIMEOUT_MILLIS = 100L

        /** O evento traz o tempo de uptime; o histórico de uso usa o relógio de parede. */
        fun attemptWallClockMillis(
            eventUptimeMillis: Long,
            nowUptimeMillis: Long,
            nowWallClockMillis: Long
        ): Long {
            if (eventUptimeMillis <= 0L || eventUptimeMillis > nowUptimeMillis) {
                return nowWallClockMillis
            }
            return nowWallClockMillis - (nowUptimeMillis - eventUptimeMillis)
        }
    }
}

@Composable
private fun GenericBlockNoticeContent(
    blockedPackage: String?,
    appLabel: String?,
    limitPasswordUnlock: Boolean,
    authManager: AuthManager,
    blockingSessionManager: BlockingSessionManager,
    onLimitUnlocked: () -> Unit,
    onBack: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize().background(DarkBg),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(32.dp),
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
                text = if (blockedPackage != null) {
                    "App bloqueado pelo FocusGuard"
                } else {
                    "Acesso bloqueado pelo FocusGuard"
                },
                color = TextPrimary,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = appLabel ?: blockedPackage ?: "Mantenha o foco em seus objetivos.",
                color = TextSecondary,
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(28.dp))

            Text(
                text = stringResource(
                    if (limitPasswordUnlock) {
                        R.string.limit_password_unlock_message
                    } else {
                        R.string.block_notice_no_password_unlock
                    }
                ),
                color = TextHint,
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(28.dp))
            if (limitPasswordUnlock && blockedPackage != null) {
                LimitPasswordUnlockPanel(
                    blockedPackage = blockedPackage,
                    authManager = authManager,
                    sessionManager = blockingSessionManager,
                    onUnlocked = onLimitUnlocked
                )
                Spacer(Modifier.height(10.dp))
            }
            Button(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth().height(46.dp)
            ) {
                Text("Voltar", fontSize = 15.sp)
            }
        }
    }
}
