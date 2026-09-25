package com.focusguard.admin

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.UserManager
import android.provider.Settings
import com.focusguard.utils.FocusGuardLogger
import com.focusguard.utils.PermissionUtils

/**
 * Optional protection against enabling installation from unknown app sources.
 *
 * Device Owner keeps using Android's native user restriction when available. On
 * regular consumer devices the enabled state is persisted independently and the
 * Accessibility service protects the "Install unknown apps" settings surface after
 * the user manually revokes grants that already existed.
 */
class UnknownSourcesSecurityManager private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val dpm = appContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val adminComponent = FocusGuardDeviceAdminReceiver.getComponentName(appContext)
    private val preferences = appContext
        .createDeviceProtectedStorageContext()
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Volatile
    private var protectionEnabled = preferences.getBoolean(KEY_PROTECTION_ENABLED, false)

    companion object {
        private const val PREFS_NAME = "unknown_sources_security"
        private const val KEY_PROTECTION_ENABLED = "protection_enabled"

        @Volatile
        private var instance: UnknownSourcesSecurityManager? = null

        fun getInstance(context: Context): UnknownSourcesSecurityManager {
            return instance ?: synchronized(this) {
                instance ?: UnknownSourcesSecurityManager(context).also { instance = it }
            }
        }

        internal fun restrictionForSdk(sdkInt: Int): String =
            if (sdkInt >= Build.VERSION_CODES.Q) {
                UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY
            } else {
                UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES
            }
    }

    fun isDeviceOwnerActive(): Boolean = runCatching {
        dpm.isDeviceOwnerApp(appContext.packageName)
    }.getOrDefault(false)

    fun canEnableProtection(): Boolean =
        isDeviceOwnerActive() || PermissionUtils.isAccessibilityServiceEnabled(appContext)

    /**
     * True when the optional protection is enabled. Native Device Owner state is
     * also recognized so installs made with the previous Device-Owner-only version
     * migrate without silently turning the switch off.
     */
    fun isBlocked(): Boolean {
        if (protectionEnabled) return true
        if (!nativeRestrictionActive()) return false

        // Migrate the old Device-Owner-only state into the independent preference.
        if (preferences.edit().putBoolean(KEY_PROTECTION_ENABLED, true).commit()) {
            protectionEnabled = true
        }
        return true
    }

    /**
     * Accessibility is the consumer-mode enforcement layer. This intentionally
     * reads the cached preference first so ordinary Settings events do not trigger
     * DevicePolicyManager work when the optional feature is disabled.
     */
    fun isAccessibilityFallbackEnabled(): Boolean =
        protectionEnabled && !isDeviceOwnerActive()

    /**
     * Enables the strongest available implementation.
     *
     * With Device Owner, Android's native restriction is applied and verified.
     * Without Device Owner, FocusGuard requires its Accessibility service and stores
     * the feature state so that service can prevent the permission from being
     * enabled again in Android settings.
     */
    fun setBlocked(enabled: Boolean): Boolean {
        val deviceOwnerActive = isDeviceOwnerActive()

        if (enabled) {
            if (!deviceOwnerActive && !PermissionUtils.isAccessibilityServiceEnabled(appContext)) {
                return false
            }
            if (deviceOwnerActive && !setNativeRestriction(true)) return false

            val stored = preferences.edit()
                .putBoolean(KEY_PROTECTION_ENABLED, true)
                .commit()
            if (!stored) {
                if (deviceOwnerActive) setNativeRestriction(false)
                return false
            }
            protectionEnabled = true
            return true
        }

        // Keep the feature logically enabled if Android refuses to remove an active
        // native policy; reporting success while that restriction remained would make
        // the switch lie about the device state.
        if (deviceOwnerActive && !setNativeRestriction(false)) return false

        val stored = preferences.edit()
            .putBoolean(KEY_PROTECTION_ENABLED, false)
            .commit()
        if (!stored) return false
        protectionEnabled = false
        return true
    }

    private fun nativeRestrictionActive(): Boolean {
        if (!isDeviceOwnerActive()) return false
        val restriction = restrictionForSdk(Build.VERSION.SDK_INT)
        return runCatching {
            dpm.getUserRestrictions(adminComponent).getBoolean(restriction, false)
        }.onFailure { error ->
            FocusGuardLogger.logError(
                "ExtraSecurity",
                "Falha ao verificar bloqueio nativo de fontes desconhecidas",
                error
            )
        }.getOrDefault(false)
    }

    private fun setNativeRestriction(enabled: Boolean): Boolean {
        val requiredRestriction = restrictionForSdk(Build.VERSION.SDK_INT)
        return runCatching {
            if (enabled) {
                dpm.addUserRestriction(adminComponent, requiredRestriction)
            } else {
                dpm.clearUserRestriction(adminComponent, requiredRestriction)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    dpm.clearUserRestriction(
                        adminComponent,
                        UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES
                    )
                }
            }

            val restrictions = dpm.getUserRestrictions(adminComponent)
            val requiredStateMatches =
                restrictions.getBoolean(requiredRestriction, false) == enabled
            val legacyStateCleared = if (!enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                !restrictions.getBoolean(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES, false)
            } else {
                true
            }
            requiredStateMatches && legacyStateCleared
        }.onFailure { error ->
            FocusGuardLogger.logError(
                "ExtraSecurity",
                "Falha ao ${if (enabled) "ativar" else "desativar"} bloqueio nativo de fontes desconhecidas",
                error
            )
        }.getOrDefault(false)
    }

    /**
     * Opens Android's list for "Install unknown apps" without a package URI, so
     * the user can review every app currently allowed to install external APKs.
     */
    fun openUnknownSourcesSettings(hostContext: Context): Boolean {
        val candidates = listOf(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES),
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        )

        candidates.forEach { intent ->
            val launched = runCatching {
                if (hostContext !is Activity) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                hostContext.startActivity(intent)
                true
            }.onFailure { error ->
                FocusGuardLogger.logError(
                    "ExtraSecurity",
                    "Falha ao abrir ${intent.action}",
                    error
                )
            }.getOrDefault(false)

            if (launched) return true
        }

        return false
    }
}
