package com.focusguard.ui

import android.content.Intent
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.doOnPreDraw
import com.focusguard.R
import com.focusguard.manager.BlockingSessionManager
import com.focusguard.security.AuthManager
import com.focusguard.security.BiometricAppUnlockPolicy
import com.focusguard.security.CurtainDestinationReadyCoordinator
import com.focusguard.security.PasswordAppUnlockStore
import com.focusguard.security.SafeSurfaceReadinessPolicy
import com.focusguard.service.BlockingAccessibilityService
import com.focusguard.ui.compose.screens.PasswordProtectedTargetUnlockPanel
import com.focusguard.ui.compose.theme.AccentCyan
import com.focusguard.ui.compose.theme.DarkBg
import com.focusguard.ui.compose.theme.FocusGuardTheme
import com.focusguard.ui.compose.theme.SuccessGreen
import com.focusguard.ui.compose.theme.TextHint
import com.focusguard.ui.compose.theme.TextPrimary
import com.focusguard.ui.compose.theme.TextSecondary
import com.focusguard.usage.UsageImpactRouter
import com.focusguard.utils.FocusGuardLogger
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.delay

@AndroidEntryPoint
class BlockNoticeActivity : AppCompatActivity() {

    @Inject lateinit var authManager: AuthManager
    @Inject lateinit var blockingSessionManager: BlockingSessionManager

    private var strictBlock = false
    private var redirectBrowserPackage: String? = null
    private var renderedNotice: NoticePayload? = null
    private var noticeDrawn = false
    private var activityResumed = false
    private var windowFocused = false
    private var pendingCurtainGeneration = 0L
    private var freshFrameGeneration = 0L

    private data class NoticePayload(
        val strictBlock: Boolean,
        val blockedPackage: String?,
        val blockedDomain: String?,
        val redirectBrowserPackage: String?
    )

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

    override fun onResume() {
        super.onResume()
        activityResumed = true
        acknowledgePendingNoticeIfPresented()
    }

    override fun onPause() {
        activityResumed = false
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        windowFocused = hasFocus
        if (hasFocus) acknowledgePendingNoticeIfPresented()
    }

    private fun showBlockNotice(sourceIntent: Intent) {
        val curtainGeneration = sourceIntent.getLongExtra(
            BlockingAccessibilityService.EXTRA_CURTAIN_GENERATION,
            0L
        )
        val payload = NoticePayload(
            strictBlock = sourceIntent.getBooleanExtra(
                BlockingAccessibilityService.EXTRA_STRICT_BLOCK,
                false
            ),
            blockedPackage = sourceIntent.getStringExtra(
                BlockingAccessibilityService.EXTRA_BLOCKED_PACKAGE
            ),
            blockedDomain = sourceIntent.getStringExtra(
                BlockingAccessibilityService.EXTRA_BLOCKED_DOMAIN
            ),
            redirectBrowserPackage = sourceIntent.getStringExtra(
                BlockingAccessibilityService.EXTRA_REDIRECT_BROWSER_PACKAGE
            )?.takeIf(String::isNotBlank)
        )
        strictBlock = payload.strictBlock
        redirectBrowserPackage = payload.redirectBrowserPackage
        pendingCurtainGeneration = curtainGeneration
        freshFrameGeneration = 0L

        if (renderedNotice == payload) {
            acknowledgeNotice(curtainGeneration)
            return
        }
        renderedNotice = payload
        noticeDrawn = false

        setContent {
            FocusGuardTheme {
                BlockNoticeContent(
                    strictBlock = payload.strictBlock,
                    blockedPackage = payload.blockedPackage,
                    blockedDomain = payload.blockedDomain,
                    redirectBrowserPackage = payload.redirectBrowserPackage,
                    authManager = authManager,
                    blockingSessionManager = blockingSessionManager,
                    onReturnToTarget = ::finish,
                    onRedirectBlockedWebsite = ::redirectBlockedWebsite,
                    onGoToPomodoroLock = ::goToPomodoroLock,
                    onGoToUsageImpact = ::goToUsageImpact
                )
            }
        }

        window.decorView.doOnPreDraw {
            noticeDrawn = true
            if (pendingCurtainGeneration == curtainGeneration &&
                activityResumed && window.decorView.isShown
            ) {
                freshFrameGeneration = curtainGeneration
            }
            val detectedAt = sourceIntent.getLongExtra(
                BlockingAccessibilityService.EXTRA_BLOCK_EVENT_UPTIME_MILLIS,
                0L
            )
            if (detectedAt > 0L) {
                FocusGuardLogger.log(
                    "BlockNotice",
                    "Evento→primeiro desenho=${SystemClock.uptimeMillis() - detectedAt}ms"
                )
            }
            acknowledgePendingNoticeIfPresented()
        }
        window.decorView.invalidate()
    }

    private fun acknowledgeNotice(curtainGeneration: Long) {
        pendingCurtainGeneration = curtainGeneration
        freshFrameGeneration = 0L
        if (acknowledgePendingNoticeIfPresented()) return
        window.decorView.doOnPreDraw {
            noticeDrawn = true
            if (pendingCurtainGeneration == curtainGeneration &&
                activityResumed && window.decorView.isShown
            ) {
                freshFrameGeneration = curtainGeneration
            }
            acknowledgePendingNoticeIfPresented()
        }
        window.decorView.invalidate()
    }

    private fun acknowledgePendingNoticeIfPresented(): Boolean {
        val generation = pendingCurtainGeneration
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
        pendingCurtainGeneration = 0L
        freshFrameGeneration = 0L
        CurtainDestinationReadyCoordinator.notifyReady(generation)
        return true
    }

    private fun redirectBlockedWebsite(browserPackageName: String) {
        val redirected = runCatching {
            startActivity(BlockingAccessibilityService.createSafeRedirectIntent(browserPackageName))
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

    private fun goToUsageImpact(packageName: String) {
        startActivity(UsageImpactActivity.createIntent(this, packageName))
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
    onReturnToTarget: () -> Unit,
    onRedirectBlockedWebsite: (String) -> Unit,
    onGoToPomodoroLock: () -> Unit,
    onGoToUsageImpact: (String) -> Unit
) {
    val context = LocalContext.current
    val unlockStore = remember(context) { PasswordAppUnlockStore(context) }
    val targetId = remember(blockedPackage, blockedDomain) {
        unlockStore.resolveWebsiteTargetId(blockedDomain)
            ?: PasswordAppUnlockStore.targetIdForPackage(blockedPackage)
    }
    val customUnlockConfig = remember(targetId) { unlockStore.getTarget(targetId) }
    var unlocked by remember { mutableStateOf(false) }
    var credentialUnlockOrigin by remember {
        mutableStateOf<BiometricAppUnlockPolicy.BlockOrigin?>(null)
    }
    var credentialUnlockResolved by remember { mutableStateOf(false) }

    val hasTargetCredential = credentialUnlockResolved &&
        credentialUnlockOrigin == BiometricAppUnlockPolicy.BlockOrigin.PASSWORD_SESSION &&
        customUnlockConfig != null

    LaunchedEffect(strictBlock, blockedPackage, blockedDomain) {
        val packageToInspect = blockedPackage
        if (!strictBlock && blockedDomain == null && !packageToInspect.isNullOrBlank() &&
            UsageImpactRouter.shouldShowForBlockedApp(context, packageToInspect)
        ) {
            delay(650L)
            onGoToUsageImpact(packageToInspect)
        }
    }

    LaunchedEffect(strictBlock, blockedPackage, blockedDomain) {
        credentialUnlockResolved = false
        credentialUnlockOrigin = if (strictBlock) {
            null
        } else {
            blockingSessionManager.credentialUnlockOrigin(
                blockedPackage = blockedPackage,
                blockedDomain = blockedDomain,
                strictPomodoroActive = false
            )
        }
        credentialUnlockResolved = true
    }

    LaunchedEffect(
        strictBlock,
        redirectBrowserPackage,
        credentialUnlockResolved,
        hasTargetCredential,
        unlocked
    ) {
        if (unlocked) return@LaunchedEffect
        when {
            strictBlock && redirectBrowserPackage != null -> {
                delay(BlockingAccessibilityService.STRICT_BLOCK_NOTICE_DURATION_MILLIS)
                onRedirectBlockedWebsite(redirectBrowserPackage)
            }
            strictBlock -> {
                delay(BlockingAccessibilityService.STRICT_BLOCK_NOTICE_DURATION_MILLIS)
                onGoToPomodoroLock()
            }
            redirectBrowserPackage != null && credentialUnlockResolved && !hasTargetCredential -> {
                onRedirectBlockedWebsite(redirectBrowserPackage)
            }
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
            if (
                redirectBrowserPackage != null &&
                !unlocked &&
                credentialUnlockResolved &&
                !hasTargetCredential
            ) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Redirecionando para o Google…",
                    color = TextHint,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
            }
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
                    LaunchedEffect(Unit) {
                        delay(250L)
                        onReturnToTarget()
                    }
                }
                strictBlock -> {
                    Text(
                        text = stringResource(R.string.block_notice_pomodoro_cannot_stop),
                        color = TextHint,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                }
                hasTargetCredential -> {
                    PasswordProtectedTargetUnlockPanel(
                        blockedPackage = blockedPackage,
                        blockedDomain = blockedDomain,
                        authManager = authManager,
                        sessionManager = blockingSessionManager,
                        onUnlocked = { unlocked = true }
                    )
                }
                credentialUnlockResolved -> {
                    Text(
                        text = stringResource(R.string.block_notice_no_password_unlock),
                        color = TextHint,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}
