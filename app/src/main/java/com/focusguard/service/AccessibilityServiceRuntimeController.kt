package com.focusguard.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.focusguard.R
import com.focusguard.utils.FocusGuardLogger

/** Foreground lifecycle, broadcasts, and browser/package resolution for the service. */
class AccessibilityServiceRuntimeController(
    private val service: BlockingAccessibilityService,
    private val onPackageChanged: (changedPackage: String, isKnownBrowser: Boolean) -> Unit,
    private val onRefresh: (Intent) -> Unit,
    private val onBlockNoticeReady: () -> Unit,
    private val onScreenOff: () -> Unit
) {
    private val knownBrowserPackages = setOf(
        "com.android.chrome",
        "com.chrome.beta",
        "com.chrome.dev",
        "com.chrome.canary",
        "com.microsoft.emmx",
        "com.microsoft.emmx.beta",
        "com.microsoft.emmx.dev",
        "com.microsoft.emmx.canary",
        "com.brave.browser",
        "com.brave.browser_beta",
        "com.brave.browser_nightly",
        "com.kiwibrowser.browser",
        "com.kiwibrowser.browser.dev",
        "com.vivaldi.browser",
        "com.vivaldi.browser.snapshot",
        "com.ecosia.android",
        "com.yandex.browser",
        "com.UCMobile.intl",
        "com.UCMobile.intl.mi",
        "org.mozilla.firefox",
        "org.mozilla.firefox_beta",
        "org.mozilla.fenix",
        "org.mozilla.fennec_aurora",
        "org.mozilla.focus",
        "org.mozilla.klar",
        "com.opera.browser",
        "com.opera.browser.beta",
        "com.opera.mini.native",
        "com.opera.gx",
        "com.sec.android.app.sbrowser",
        "com.sec.android.app.sbrowser.beta",
        "com.duckduckgo.mobile.android",
        "com.google.android.googlequicksearchbox"
    )

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val changedPackage = intent?.data?.schemeSpecificPart.orEmpty()
            onPackageChanged(changedPackage, changedPackage in knownBrowserPackages)
        }
    }
    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let(onRefresh)
        }
    }
    private val blockNoticeReadyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            onBlockNoticeReady()
        }
    }
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) onScreenOff()
        }
    }

    fun start() {
        registerPackageReceiver()
        registerRefreshReceiver()
        registerBlockNoticeReadyReceiver()
        registerScreenStateReceiver()
        createNotificationChannel()
        startAsForeground()
    }

    fun destroy() {
        runCatching { service.unregisterReceiver(packageReceiver) }
        runCatching { service.unregisterReceiver(refreshReceiver) }
        runCatching { service.unregisterReceiver(blockNoticeReadyReceiver) }
        runCatching { service.unregisterReceiver(screenStateReceiver) }
    }

    fun calculateBrowserPackages(): Set<String> {
        return try {
            val browserIntent = Intent(Intent.ACTION_VIEW).apply {
                data = android.net.Uri.parse("https://www.example.com")
            }
            val resolved = service.packageManager.queryIntentActivities(
                browserIntent,
                PackageManagerCompat.MATCH_ALL
            ).mapNotNullTo(mutableSetOf()) { it.activityInfo?.packageName }
            resolved + knownBrowserPackages.filter(::isPackageInstalled)
        } catch (error: RuntimeException) {
            FocusGuardLogger.logError("A11y", "Falha ao listar navegadores", error)
            knownBrowserPackages.filterTo(mutableSetOf(), ::isPackageInstalled)
        }
    }

    fun calculateDefaultLauncher(): String? {
        return try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
            }
            service.packageManager.resolveActivity(
                intent,
                android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
            )?.activityInfo?.packageName
        } catch (error: RuntimeException) {
            FocusGuardLogger.logError("A11y", "Falha ao identificar launcher", error)
            null
        }
    }

    fun resolveEventPackageName(event: AccessibilityEvent): String {
        val directPackage = event.packageName?.toString().orEmpty()
        if (event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED &&
            directPackage.isNotBlank()
        ) {
            return directPackage
        }

        if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            val eventWindowRoot = service.windows
                .firstOrNull { window -> window.id == event.windowId }
                ?.root
            val eventWindowPackage = try {
                eventWindowRoot?.packageName?.toString().orEmpty()
            } finally {
                recycle(eventWindowRoot)
            }
            if (eventWindowPackage.isNotBlank()) return eventWindowPackage
        }

        val root = service.rootInActiveWindow
        return try {
            root?.packageName?.toString().orEmpty().ifBlank { directPackage }
        } finally {
            recycle(root)
        }
    }

    private fun registerPackageReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(
            service,
            packageReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun registerRefreshReceiver() {
        val filter = IntentFilter(BlockingAccessibilityService.ACTION_REFRESH_BLOCKING)
        ContextCompat.registerReceiver(
            service,
            refreshReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun registerBlockNoticeReadyReceiver() {
        val filter = IntentFilter(BlockingAccessibilityService.ACTION_BLOCK_NOTICE_READY)
        ContextCompat.registerReceiver(
            service,
            blockNoticeReadyReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun registerScreenStateReceiver() {
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        ContextCompat.registerReceiver(
            service,
            screenStateReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = service.getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "FocusGuard Protection Service",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = service.getString(
                        R.string.mantem_o_focusguard_ativo_para_garantir_
                    )
                    setShowBadge(false)
                }
            )
        }
    }

    private fun startAsForeground() {
        val notification = NotificationCompat.Builder(service, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(service.getString(R.string.service_notification_title))
            .setContentText(service.getString(R.string.service_notification_text))
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            service.startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            service.startForeground(NOTIFICATION_ID, notification)
        }
    }

    @Suppress("DEPRECATION")
    private fun isPackageInstalled(packageName: String): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            service.packageManager.getPackageInfo(
                packageName,
                android.content.pm.PackageManager.PackageInfoFlags.of(0L)
            )
        } else {
            service.packageManager.getPackageInfo(packageName, 0)
        }
    }.isSuccess

    private fun recycle(node: AccessibilityNodeInfo?) {
        if (node != null) runCatching { node.recycle() }
    }

    private companion object {
        const val CHANNEL_ID = "focusguard_service_channel"
        const val NOTIFICATION_ID = 101
    }

    private object PackageManagerCompat {
        const val MATCH_ALL: Int = 0x00020000
    }
}
