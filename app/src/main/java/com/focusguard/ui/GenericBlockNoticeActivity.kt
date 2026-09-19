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
import com.focusguard.security.CurtainDestinationReadyCoordinator
import com.focusguard.security.SafeSurfaceReadinessPolicy
import com.focusguard.service.BlockingAccessibilityService
import com.focusguard.ui.compose.theme.AccentCyan
import com.focusguard.ui.compose.theme.DarkBg
import com.focusguard.ui.compose.theme.FocusGuardTheme
import com.focusguard.ui.compose.theme.TextHint
import com.focusguard.ui.compose.theme.TextPrimary
import com.focusguard.ui.compose.theme.TextSecondary
import com.focusguard.usage.UsageImpactRouter
import com.focusguard.utils.FocusGuardLogger
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Non-interactive app/generic fail-closed block surface.
 *
 * Known website targets are owned by [WebsiteBlockNoticeActivity]. PASSWORD
 * credentials are owned by [PasswordUnlockActivity]. Keeping this Activity free
 * from website URL/redirection behavior prevents the app-block UI from becoming
 * a second owner of the website pipeline.
 */
class GenericBlockNoticeActivity : AppCompatActivity() {

    private var strictBlock = false
    private var noticeDrawn = false
    private var activityResumed = false
    private var windowFocused = false
    private var pendingCurtainGeneration = 0L
    private var freshFrameGeneration = 0L

    // A usage-limit block can reuse this singleTop Activity many times. Keep an
    // attempt id so every interception gets its own impact-route decision.
    private var blockAttemptId = 0L
    private var pendingUsageImpactAttemptId = 0L
    private var pendingUsageImpactPackage: String? = null
    private var usageImpactJob: Job? = null

    private data class NoticePayload(
        val strictBlock: Boolean,
        val blockedPackage: String?
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!strictBlock) goHome()
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
            strictBlock = sourceIntent.getBooleanExtra(
                BlockingAccessibilityService.EXTRA_STRICT_BLOCK,
                false
            ),
            blockedPackage = sourceIntent.getStringExtra(
                BlockingAccessibilityService.EXTRA_BLOCKED_PACKAGE
            )?.takeIf(String::isNotBlank)
        )

        strictBlock = payload.strictBlock
        pendingCurtainGeneration = curtainGeneration
        freshFrameGeneration = 0L

        val attemptId = ++blockAttemptId
        scheduleUsageImpactRoute(payload, attemptId)
        noticeDrawn = false

        setContent {
            FocusGuardTheme {
                GenericBlockNoticeContent(
                    strictBlock = payload.strictBlock,
                    blockedPackage = payload.blockedPackage,
                    onGoToPomodoroLock = ::goToPomodoroLock
                )
            }
        }

        window.decorView.doOnPreDraw {
            noticeDrawn = true
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
        window.decorView.invalidate()
    }

    /**
     * Resolve whether this exact attempt came from an active non-password app
     * usage limit. Navigation waits for the curtain handshake so the impact screen
     * cannot race the instant opaque protection surface.
     */
    private fun scheduleUsageImpactRoute(payload: NoticePayload, attemptId: Long) {
        usageImpactJob?.cancel()
        usageImpactJob = null
        pendingUsageImpactAttemptId = 0L
        pendingUsageImpactPackage = null

        val packageName = payload.blockedPackage
        if (payload.strictBlock || packageName.isNullOrBlank()) return

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
        goToUsageImpact(packageName)
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
        CurtainDestinationReadyCoordinator.notifyReady(generation)
        routeToUsageImpactIfReady()
        return true
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
private fun GenericBlockNoticeContent(
    strictBlock: Boolean,
    blockedPackage: String?,
    onGoToPomodoroLock: () -> Unit
) {
    LaunchedEffect(strictBlock) {
        if (strictBlock) {
            delay(BlockingAccessibilityService.STRICT_BLOCK_NOTICE_DURATION_MILLIS)
            onGoToPomodoroLock()
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
                text = blockedPackage ?: "Mantenha o foco em seus objetivos.",
                color = TextSecondary,
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(28.dp))

            Text(
                text = stringResource(
                    if (strictBlock) {
                        R.string.block_notice_pomodoro_cannot_stop
                    } else {
                        R.string.block_notice_no_password_unlock
                    }
                ),
                color = TextHint,
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}
