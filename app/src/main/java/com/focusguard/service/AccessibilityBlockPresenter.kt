package com.focusguard.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.focusguard.R
import com.focusguard.contract.EnforcementUiContract
import com.focusguard.utils.FocusGuardLogger
import com.focusguard.utils.WebsiteBlocker
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Presents immediate overlays and navigation after an Accessibility block decision. */
class AccessibilityBlockPresenter(
    private val service: BlockingAccessibilityService,
    private val scope: CoroutineScope,
    private val strictPomodoroActive: () -> Boolean,
    private val onWebsiteBlockStarted: (Long) -> Unit
) {
    private enum class CurtainMode {
        BLOCK_NOTICE,
        SELF_PROTECTION
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager =
        service.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    private var curtain: View? = null
    private var curtainMessage: TextView? = null
    private var curtainMode: CurtainMode? = null
    private var lastWebsiteBlockTime = 0L
    private var lastWebsiteBlockKey: String? = null
    private var lastToastTime = 0L
    private var protectionActionUntilElapsed = 0L
    private var lastBlockNoticeKey: String? = null
    private var lastBlockNoticeLaunchElapsed = 0L

    private val curtainFailsafe = Runnable { dismissCurtain() }
    private val protectionCurtainDismiss = Runnable {
        if (curtainMode == CurtainMode.SELF_PROTECTION) dismissCurtain()
    }
    private val protectionGoHome = Runnable {
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
    }

    fun blockApp(packageName: String) {
        launchBlockNotice(blockedPackage = packageName, blockedDomain = null)
    }

    fun blockWebsite(domain: String, browserPackageName: String) {
        if (!beginWebsiteBlock(domain, browserPackageName)) return
        val noticeLaunched = launchBlockNotice(
            blockedPackage = null,
            blockedDomain = WebsiteBlocker.displayRule(domain),
            redirectBrowserPackage = browserPackageName
        )
        if (!noticeLaunched && !redirectBrowserToSafePage(browserPackageName)) {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        }
    }

    fun blockWebsiteApp(domain: String, packageName: String) {
        if (!beginWebsiteBlock(domain, packageName)) return
        launchBlockNotice(
            blockedPackage = null,
            blockedDomain = WebsiteBlocker.displayRule(domain)
        )
    }

    fun dismissBlockNoticeCurtain() {
        if (curtainMode == CurtainMode.BLOCK_NOTICE) dismissCurtain()
    }

    fun launchPomodoroLockScreen() {
        try {
            service.startActivity(
                EnforcementUiContract.createPomodoroLockIntent(service).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP
                    )
                }
            )
        } catch (error: RuntimeException) {
            FocusGuardLogger.logError("A11y", "Falha ao abrir Pomodoro", error)
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        }
    }

    fun executeSelfProtectionAction() {
        val nowElapsed = SystemClock.elapsedRealtime()
        if (!BlockingAccessibilityService.shouldExecuteProtectionAction(
                protectionActionUntilElapsed,
                nowElapsed
            )
        ) {
            return
        }
        protectionActionUntilElapsed =
            nowElapsed + BlockingAccessibilityService.SELF_PROTECTION_ACTION_DEBOUNCE_MILLIS

        showCurtain(
            mode = CurtainMode.SELF_PROTECTION,
            messageRes = R.string.accessibility_protection_blocked_notice
        )
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        mainHandler.removeCallbacks(protectionGoHome)
        mainHandler.postDelayed(
            protectionGoHome,
            BlockingAccessibilityService.SELF_PROTECTION_HOME_DELAY_MILLIS
        )
        mainHandler.removeCallbacks(protectionCurtainDismiss)
        mainHandler.postDelayed(
            protectionCurtainDismiss,
            BlockingAccessibilityService.SELF_PROTECTION_NOTICE_DURATION_MILLIS
        )
        showToastThrottled(
            service.getString(R.string.accessibility_protection_blocked_toast)
        )
    }

    fun destroy() {
        mainHandler.removeCallbacks(protectionGoHome)
        mainHandler.removeCallbacks(protectionCurtainDismiss)
        protectionActionUntilElapsed = 0L
        dismissCurtain()
    }

    private fun beginWebsiteBlock(domain: String, packageName: String): Boolean {
        val now = System.currentTimeMillis()
        val blockKey = "$packageName|$domain"
        if (blockKey == lastWebsiteBlockKey &&
            now - lastWebsiteBlockTime < WEBSITE_BLOCK_COOLDOWN_MILLIS
        ) {
            return false
        }

        lastWebsiteBlockKey = blockKey
        lastWebsiteBlockTime = now
        onWebsiteBlockStarted(now)
        return true
    }

    private fun redirectBrowserToSafePage(browserPackageName: String): Boolean =
        runCatching {
            service.startActivity(
                BlockingAccessibilityService.createSafeRedirectIntent(browserPackageName)
            )
            true
        }.getOrElse { error ->
            FocusGuardLogger.logError(
                "A11y",
                "Falha ao redirecionar navegador bloqueado",
                error
            )
            false
        }

    private fun launchBlockNotice(
        blockedPackage: String?,
        blockedDomain: String?,
        redirectBrowserPackage: String? = null
    ): Boolean {
        val nowElapsed = SystemClock.elapsedRealtime()
        val noticeKey = listOf(
            strictPomodoroActive().toString(),
            blockedPackage.orEmpty(),
            blockedDomain.orEmpty(),
            redirectBrowserPackage.orEmpty()
        ).joinToString("|")
        if (!BlockingAccessibilityService.shouldLaunchBlockNotice(
                previousKey = lastBlockNoticeKey,
                previousLaunchElapsed = lastBlockNoticeLaunchElapsed,
                requestedKey = noticeKey,
                nowElapsed = nowElapsed
            )
        ) {
            return true
        }

        showCurtain(CurtainMode.BLOCK_NOTICE)
        return try {
            service.startActivity(
                BlockingAccessibilityService.createBlockNoticeIntent(
                    context = service,
                    strictBlock = strictPomodoroActive(),
                    blockedPackage = blockedPackage,
                    blockedDomain = blockedDomain,
                    redirectBrowserPackage = redirectBrowserPackage
                )
            )
            lastBlockNoticeKey = noticeKey
            lastBlockNoticeLaunchElapsed = nowElapsed
            true
        } catch (error: RuntimeException) {
            FocusGuardLogger.logError("A11y", "Falha ao abrir tela de bloqueio", error)
            dismissCurtain()
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            false
        }
    }

    private fun showCurtain(mode: CurtainMode, messageRes: Int? = null) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { showCurtain(mode, messageRes) }
            return
        }

        if (curtain == null) {
            val density = service.resources.displayMetrics.density
            val iconSize = (72 * density).toInt()
            val spacing = (18 * density).toInt()
            val view = LinearLayout(service).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundColor(Color.rgb(16, 17, 23))
                isClickable = true
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                contentDescription = service.getString(
                    R.string.block_notice_instant_content_description
                )

                addView(
                    ImageView(service).apply {
                        setImageResource(R.drawable.ic_shield)
                        setColorFilter(Color.rgb(38, 198, 218))
                    },
                    LinearLayout.LayoutParams(iconSize, iconSize)
                )
                addView(
                    TextView(service).apply {
                        text = service.getString(R.string.block_notice_instant_title)
                        setTextColor(Color.WHITE)
                        textSize = 20f
                        gravity = Gravity.CENTER
                    },
                    LinearLayout.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = spacing }
                )
                addView(
                    TextView(service).apply {
                        setTextColor(Color.LTGRAY)
                        textSize = 14f
                        gravity = Gravity.CENTER
                        visibility = View.GONE
                        curtainMessage = this
                    },
                    LinearLayout.LayoutParams(
                        (280 * density).toInt(),
                        WindowManager.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = (10 * density).toInt() }
                )
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.OPAQUE
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                title = "FocusGuardInstantBlock"
            }

            try {
                val manager = windowManager ?: return
                manager.addView(view, params)
                curtain = view
            } catch (error: RuntimeException) {
                FocusGuardLogger.logError(
                    "A11y",
                    "Falha ao exibir cortina instantânea",
                    error
                )
            }
        }

        curtainMode = mode
        curtainMessage?.apply {
            if (messageRes == null) {
                text = ""
                visibility = View.GONE
            } else {
                setText(messageRes)
                visibility = View.VISIBLE
            }
        }
        mainHandler.removeCallbacks(curtainFailsafe)
        mainHandler.postDelayed(
            curtainFailsafe,
            BlockingAccessibilityService.INSTANT_CURTAIN_FAILSAFE_MILLIS
        )
    }

    private fun dismissCurtain() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(::dismissCurtain)
            return
        }
        mainHandler.removeCallbacks(curtainFailsafe)
        val current = curtain
        curtain = null
        curtainMessage = null
        curtainMode = null
        if (current == null) return
        runCatching { windowManager?.removeViewImmediate(current) }
            .onFailure { error ->
                FocusGuardLogger.logError(
                    "A11y",
                    "Falha ao remover cortina instantânea",
                    error
                )
            }
    }

    private fun showToastThrottled(message: String) {
        val now = System.currentTimeMillis()
        if (now - lastToastTime < TOAST_COOLDOWN_MILLIS) return
        lastToastTime = now
        scope.launch(Dispatchers.Main) {
            Toast.makeText(service, message, Toast.LENGTH_SHORT).show()
        }
    }

    private companion object {
        const val WEBSITE_BLOCK_COOLDOWN_MILLIS = 1_500L
        const val TOAST_COOLDOWN_MILLIS = 3_000L
    }
}
