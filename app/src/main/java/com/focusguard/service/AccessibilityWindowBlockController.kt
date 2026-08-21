package com.focusguard.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import com.focusguard.MainActivity
import com.focusguard.focusmode.FocusModePolicy
import com.focusguard.security.SettingsInterceptionPolicy
import com.focusguard.utils.FocusGuardLogger

/** Applies window-level app, Focus Mode, and strict-Pomodoro decisions. */
class AccessibilityWindowBlockController(
    private val service: BlockingAccessibilityService,
    private val presenter: AccessibilityBlockPresenter,
    private val websiteTracker: AccessibilityWebsiteTracker
) {
    private var lastFocusModeRedirectElapsed = 0L

    fun handleWindow(
        event: AccessibilityEvent,
        packageName: String,
        defaultLauncherPackage: String?,
        focusModeFallbackActive: Boolean,
        focusModeBlockedApps: Set<String>,
        focusModeAllowedApps: Set<String>,
        blockedApps: Set<String>,
        blockedWebsiteApps: Map<String, String>,
        limitedWebsiteApps: Map<String, String>,
        browserPackages: Set<String>,
        blockedWebsiteRules: Set<String>,
        limitedWebsiteRules: Set<String>
    ) {
        if (packageName.isBlank()) return
        val className = event.className?.toString().orEmpty()
        if (className.contains("Toast") || className.contains("PopupWindow")) return
        if (packageName == service.packageName) return
        if (FocusModePolicy.shouldRedirectToFocusGuard(
                focusModeFallbackActive = focusModeFallbackActive,
                foregroundPackage = packageName,
                focusGuardPackage = service.packageName,
                launcherPackage = defaultLauncherPackage,
                focusModeBlockedPackages = focusModeBlockedApps
            )
        ) {
            redirectToFocusGuard(packageName)
            return
        }
        if (packageName in focusModeAllowedApps || packageName == defaultLauncherPackage) return

        val blockedWebsiteDomain = blockedWebsiteApps[packageName]
        val limitedWebsiteDomain = limitedWebsiteApps[packageName]
        when {
            blockedWebsiteDomain != null ->
                presenter.blockWebsiteApp(blockedWebsiteDomain, packageName)
            packageName in blockedApps -> presenter.blockApp(packageName)
            limitedWebsiteDomain != null -> websiteTracker.update(
                urlOrDomain = limitedWebsiteDomain,
                packageName = packageName,
                now = System.currentTimeMillis()
            )
            packageName in browserPackages &&
                (blockedWebsiteRules.isNotEmpty() || limitedWebsiteRules.isNotEmpty()) ->
                websiteTracker.handleBrowserEvent(event)
        }
    }

    fun handleStrictPomodoro(
        packageName: String,
        className: String,
        defaultLauncherPackage: String?
    ) {
        if (packageName.isBlank() ||
            packageName == service.packageName ||
            packageName in PHONE_PACKAGES
        ) {
            return
        }

        if (packageName == "com.android.systemui") {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            presenter.launchPomodoroLockScreen()
            return
        }

        if (packageName == defaultLauncherPackage ||
            packageName in SettingsInterceptionPolicy.settingsPackages
        ) {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            presenter.launchPomodoroLockScreen()
            return
        }

        FocusGuardLogger.log("A11y", "Pomodoro rigoroso bloqueou $packageName ($className)")
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        presenter.blockApp(packageName)
    }

    private fun redirectToFocusGuard(blockedPackage: String) {
        val nowElapsed = SystemClock.elapsedRealtime()
        if (nowElapsed - lastFocusModeRedirectElapsed < FOCUS_REDIRECT_COOLDOWN_MILLIS) return
        lastFocusModeRedirectElapsed = nowElapsed
        FocusGuardLogger.log(
            "FocusMode",
            "Modo consumidor redirecionou $blockedPackage para o FocusGuard"
        )
        runCatching {
            service.startActivity(
                Intent(service, MainActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                }
            )
        }.onFailure { error ->
            FocusGuardLogger.logError(
                "FocusMode",
                "Falha ao retornar ao FocusGuard",
                error
            )
        }
    }

    private companion object {
        const val FOCUS_REDIRECT_COOLDOWN_MILLIS = 600L
        val PHONE_PACKAGES = setOf(
            "com.android.dialer",
            "com.google.android.dialer",
            "com.android.phone",
            "com.android.server.telecom",
            "com.samsung.android.dialer",
            "com.samsung.android.incallui"
        )
    }
}
