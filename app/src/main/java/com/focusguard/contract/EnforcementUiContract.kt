package com.focusguard.contract

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock

/** Neutral contract shared by enforcement adapters and their UI destinations. */
object EnforcementUiContract {
    const val ACTION_REFRESH_BLOCKING = "com.focusguard.ACTION_REFRESH_BLOCKING"
    const val ACTION_BLOCK_NOTICE_READY = "com.focusguard.ACTION_BLOCK_NOTICE_READY"
    const val EXTRA_STRICT_BLOCK = "STRICT_BLOCK"
    const val EXTRA_BLOCKED_PACKAGE = "BLOCKED_PACKAGE"
    const val EXTRA_BLOCKED_DOMAIN = "BLOCKED_DOMAIN"
    const val EXTRA_REDIRECT_BROWSER_PACKAGE = "REDIRECT_BROWSER_PACKAGE"
    const val EXTRA_BLOCK_DETECTED_ELAPSED_REALTIME = "BLOCK_DETECTED_ELAPSED_REALTIME"
    const val WEBSITE_BLOCK_NOTICE_DURATION_MILLIS = 1_000L
    const val FOCUS_MODE_NOTIFICATION_SERVICE_CLASS_NAME =
        "com.focusguard.service.FocusModeNotificationService"

    private const val BLOCK_NOTICE_ACTIVITY_CLASS_NAME =
        "com.focusguard.ui.BlockNoticeActivity"
    private const val POMODORO_LOCK_ACTIVITY_CLASS_NAME =
        "com.focusguard.ui.PomodoroLockActivity"
    private const val MAIN_ACTIVITY_CLASS_NAME = "com.focusguard.MainActivity"
    private const val SAFE_REDIRECT_URL = "https://www.google.com"

    fun createBlockNoticeIntent(
        context: Context,
        strictBlock: Boolean,
        blockedPackage: String?,
        blockedDomain: String?,
        redirectBrowserPackage: String?,
        detectedElapsedRealtime: Long = SystemClock.elapsedRealtime()
    ): Intent = explicitActivityIntent(context, BLOCK_NOTICE_ACTIVITY_CLASS_NAME).apply {
        addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
        )
        putExtra(EXTRA_STRICT_BLOCK, strictBlock)
        putExtra(EXTRA_BLOCKED_PACKAGE, blockedPackage)
        putExtra(EXTRA_BLOCKED_DOMAIN, blockedDomain)
        putExtra(EXTRA_BLOCK_DETECTED_ELAPSED_REALTIME, detectedElapsedRealtime)
        redirectBrowserPackage
            ?.takeIf(String::isNotBlank)
            ?.let { putExtra(EXTRA_REDIRECT_BROWSER_PACKAGE, it) }
    }

    fun createSafeRedirectIntent(browserPackageName: String): Intent {
        require(browserPackageName.isNotBlank())
        return Intent(Intent.ACTION_VIEW, Uri.parse(SAFE_REDIRECT_URL)).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            setPackage(browserPackageName)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
    }

    fun createPomodoroLockIntent(context: Context): Intent =
        explicitActivityIntent(context, POMODORO_LOCK_ACTIVITY_CLASS_NAME)

    fun createMainIntent(context: Context): Intent =
        explicitActivityIntent(context, MAIN_ACTIVITY_CLASS_NAME)

    private fun explicitActivityIntent(context: Context, className: String): Intent =
        Intent().setClassName(context, className)
}
