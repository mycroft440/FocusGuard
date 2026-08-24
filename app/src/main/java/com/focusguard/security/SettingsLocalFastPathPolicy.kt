package com.focusguard.security

import android.view.accessibility.AccessibilityEvent
import com.focusguard.security.ImmediateInterceptionPolicy.SettingsSurface

/**
 * Zero-tree L0 decision layer for protected Android Settings surfaces.
 *
 * Only fields already contained in AccessibilityEvent are accepted here. The
 * caller may ask for remote context only when [Decision.NEED_REMOTE] is returned.
 */
object SettingsLocalFastPathPolicy {
    enum class Action { PROTECT, NEED_REMOTE, IGNORE }

    data class Decision(
        val action: Action,
        val surface: SettingsSurface? = null,
        val armTransitionGuard: Boolean = false
    )

    data class Input(
        val packageName: String,
        val eventType: Int,
        val className: String,
        val directText: EventTextNormalizer.Prepared,
        val snapshot: ProtectionFastSnapshot,
        val transitionGuardActive: Boolean
    )

    fun decide(input: Input): Decision {
        val snapshot = input.snapshot
        if (!snapshot.engaged) return Decision(Action.IGNORE)
        if (snapshot.maintenanceWindowActive) return Decision(Action.IGNORE)

        // Once an earlier destructive click armed the guard, follow-up non-click
        // window events are conclusive by state alone. No class/text work is needed.
        if (input.transitionGuardActive && input.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED) {
            return Decision(Action.PROTECT)
        }

        if (input.packageName !in SettingsInterceptionPolicy.interceptionPackages) {
            return Decision(Action.IGNORE)
        }

        val normalized = input.directText.normalized
        val className = input.className
        val deviceAdminClass = className.contains("DeviceAdmin", ignoreCase = true) ||
            className.contains("DeviceAdministrator", ignoreCase = true)
        val appInfoClass = className.contains("InstalledAppDetails", ignoreCase = true) ||
            className.contains("AppInfo", ignoreCase = true) ||
            className.contains("SpaAppBridgeActivity", ignoreCase = true)
        val accessibilityClass = className.contains("Accessibility", ignoreCase = true)
        val uninstallClass = className.contains("Uninstall", ignoreCase = true) ||
            className.contains("PackageInstaller", ignoreCase = true)

        val self = containsAny(normalized, SELF_IDENTITY)
        val deviceAdmin = containsAny(normalized, DEVICE_ADMIN)
        val appInfo = containsAny(normalized, APP_INFO)
        val accessibility = containsAny(normalized, ACCESSIBILITY)
        val installedAccessibility = containsAny(normalized, INSTALLED_ACCESSIBILITY)
        val destructive = containsAny(normalized, DESTRUCTIVE)

        if ((deviceAdminClass || deviceAdmin) && !snapshot.adminEnrollmentAuthorized) {
            return Decision(Action.PROTECT, SettingsSurface.DEVICE_ADMIN, armTransitionGuard = true)
        }

        if (input.packageName in SettingsInterceptionPolicy.systemUiPackages) {
            return when {
                deviceAdmin && !snapshot.adminEnrollmentAuthorized ->
                    Decision(Action.PROTECT, SettingsSurface.DEVICE_ADMIN, true)
                self && accessibility ->
                    Decision(Action.PROTECT, SettingsSurface.ACCESSIBILITY, true)
                self || accessibility || normalized.isEmpty() -> Decision(Action.NEED_REMOTE)
                else -> Decision(Action.IGNORE)
            }
        }

        return when {
            self && appInfo -> Decision(Action.PROTECT, SettingsSurface.APP_INFO, true)
            self && uninstallClass -> Decision(Action.PROTECT, SettingsSurface.UNINSTALL, true)
            self && destructive -> Decision(Action.PROTECT, SettingsSurface.UNINSTALL, true)
            self && (accessibilityClass || accessibility || installedAccessibility) ->
                Decision(Action.PROTECT, SettingsSurface.ACCESSIBILITY, true)
            installedAccessibility && accessibility ->
                Decision(Action.PROTECT, SettingsSurface.ACCESSIBILITY, true)
            self && appInfoClass -> Decision(Action.PROTECT, SettingsSurface.APP_INFO, true)
            // A directly exposed HardBlock row is itself an early route into
            // protected controls. Block before waiting for App Info to render.
            self -> Decision(Action.PROTECT, SettingsSurface.APP_INFO, true)
            appInfo || appInfoClass || accessibilityClass || installedAccessibility ||
                uninstallClass || destructive || className.contains("SubSettings", true) ->
                Decision(Action.NEED_REMOTE)
            // OEM Settings can report a generic textless row. Do not assert a safe
            // negative until the single bounded remote fallback inspects it.
            input.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED -> Decision(Action.NEED_REMOTE)
            else -> Decision(Action.IGNORE)
        }
    }

    private fun containsAny(values: List<String>, terms: Set<String>): Boolean =
        values.any { value -> terms.any(value::contains) }

    private val SELF_IDENTITY = setOf(
        "hardblock",
        "hard block",
        "focusguard",
        "focus guard",
        "com.focusguard.v2",
        "com.focusguard.v2.debug",
        "com.focusguard"
    )
    private val DEVICE_ADMIN = setOf(
        "device admin",
        "device administrator",
        "device administrators",
        "admin apps",
        "apps administradores",
        "apps de administrador",
        "administradores do aparelho",
        "administradores do dispositivo",
        "administrador do aparelho",
        "administrador do dispositivo",
        "device_admin",
        "deviceadmin"
    )
    private val APP_INFO = setOf(
        "app info",
        "application info",
        "informacoes do app",
        "informacoes do aplicativo",
        "app_info",
        "application_info"
    )
    private val ACCESSIBILITY = setOf(
        "accessibility",
        "acessibilidade"
    )
    private val INSTALLED_ACCESSIBILITY = setOf(
        "installed apps",
        "installed services",
        "downloaded apps",
        "aplicativos instalados",
        "servicos instalados",
        "apps instalados",
        "installed_services",
        "installed_apps"
    )
    private val DESTRUCTIVE = setOf(
        "uninstall",
        "desinstalar",
        "disable",
        "desativar",
        "force stop",
        "forcar parada",
        "clear data",
        "limpar dados"
    )
}
