package com.focusguard.service

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import com.focusguard.ui.BlockNoticeActivity
import com.focusguard.utils.WebsiteBlocker

/** Pure event, geometry, and Intent contracts used by Accessibility enforcement. */
internal object AccessibilityServiceContract {
    private const val SAFE_REDIRECT_URL = "https://www.google.com"

    val settingsInterceptionEventTypes = setOf(
        AccessibilityEvent.TYPE_WINDOWS_CHANGED,
        AccessibilityEvent.TYPE_VIEW_FOCUSED,
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
        AccessibilityEvent.TYPE_VIEW_CLICKED
    )

    fun shouldExecuteProtectionAction(
        blockedUntilElapsed: Long,
        nowElapsed: Long
    ): Boolean = blockedUntilElapsed <= 0L || nowElapsed > blockedUntilElapsed

    fun isSelfProtectionEngaged(
        cachedActive: Boolean,
        persistedActive: Boolean,
        focusModeActive: Boolean,
        armoredDeviceOwnerActive: Boolean
    ): Boolean = cachedActive ||
        persistedActive ||
        focusModeActive ||
        armoredDeviceOwnerActive

    fun shouldSearchSameRowMarkers(clicked: Rect, root: Rect): Boolean =
        !clicked.isEmpty &&
            !root.isEmpty &&
            clicked.height() * 3 < root.height()

    fun boundsShareHorizontalRow(clicked: Rect, marker: Rect): Boolean =
        !clicked.isEmpty &&
            !marker.isEmpty &&
            minOf(clicked.bottom, marker.bottom) > maxOf(clicked.top, marker.top)

    fun requestedAccessibilityEventTypes(): Int =
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
            AccessibilityEvent.TYPE_WINDOWS_CHANGED or
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
            AccessibilityEvent.TYPE_VIEW_CLICKED or
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
            AccessibilityEvent.TYPE_VIEW_FOCUSED

    fun shouldLaunchBlockNotice(
        previousKey: String?,
        previousLaunchElapsed: Long,
        requestedKey: String,
        nowElapsed: Long
    ): Boolean = previousKey != requestedKey ||
        nowElapsed - previousLaunchElapsed >=
        BlockingAccessibilityService.BLOCK_NOTICE_RELAUNCH_COOLDOWN_MILLIS

    fun createRefreshBlockingIntent(
        context: Context,
        blockedApps: Collection<String>,
        blockedSites: Collection<String>,
        blockingActive: Boolean,
        strictPomodoro: Boolean
    ): Intent = Intent(BlockingAccessibilityService.ACTION_REFRESH_BLOCKING).apply {
        setPackage(context.packageName)
        putExtra(BlockingAccessibilityService.EXTRA_BLOCKING_SNAPSHOT_PRESENT, true)
        putStringArrayListExtra(
            BlockingAccessibilityService.EXTRA_BLOCKED_APPS_SNAPSHOT,
            ArrayList(blockedApps.filter(String::isNotBlank).distinct())
        )
        putStringArrayListExtra(
            BlockingAccessibilityService.EXTRA_BLOCKED_SITES_SNAPSHOT,
            ArrayList(WebsiteBlocker.normalizeRules(blockedSites))
        )
        putExtra(BlockingAccessibilityService.EXTRA_BLOCKING_ACTIVE_SNAPSHOT, blockingActive)
        putExtra(BlockingAccessibilityService.EXTRA_STRICT_POMODORO_SNAPSHOT, strictPomodoro)
    }

    fun createBlockNoticeIntent(
        context: Context,
        strictBlock: Boolean,
        blockedPackage: String?,
        blockedDomain: String?,
        redirectBrowserPackage: String?,
        detectedElapsedRealtime: Long = SystemClock.elapsedRealtime()
    ): Intent = Intent(context, BlockNoticeActivity::class.java).apply {
        addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
        )
        putExtra(BlockingAccessibilityService.EXTRA_STRICT_BLOCK, strictBlock)
        putExtra(BlockingAccessibilityService.EXTRA_BLOCKED_PACKAGE, blockedPackage)
        putExtra(BlockingAccessibilityService.EXTRA_BLOCKED_DOMAIN, blockedDomain)
        putExtra(
            BlockingAccessibilityService.EXTRA_BLOCK_DETECTED_ELAPSED_REALTIME,
            detectedElapsedRealtime
        )
        redirectBrowserPackage
            ?.takeIf(String::isNotBlank)
            ?.let {
                putExtra(BlockingAccessibilityService.EXTRA_REDIRECT_BROWSER_PACKAGE, it)
            }
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
}
