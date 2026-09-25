package com.focusguard.admin

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.UserManager
import android.provider.Settings
import com.focusguard.utils.FocusGuardLogger

/**
 * Optional Device Owner policy for blocking installation from unknown sources.
 *
 * This policy intentionally stays separate from [DeviceOwnerManager]'s automatic
 * protection shield. It is enabled only after the user explicitly completes the
 * manual revocation flow in Android settings and confirms the action in FocusGuard.
 */
class UnknownSourcesSecurityManager private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val dpm = appContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val adminComponent = FocusGuardDeviceAdminReceiver.getComponentName(appContext)

    companion object {
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

    /**
     * Returns true only when the strongest restriction available on this Android
     * version is confirmed by DevicePolicyManager.
     */
    fun isBlocked(): Boolean {
        if (!isDeviceOwnerActive()) return false
        val restriction = restrictionForSdk(Build.VERSION.SDK_INT)
        return runCatching {
            dpm.getUserRestrictions(adminComponent).getBoolean(restriction, false)
        }.onFailure { error ->
            FocusGuardLogger.logError(
                "ExtraSecurity",
                "Falha ao verificar bloqueio de fontes desconhecidas",
                error
            )
        }.getOrDefault(false)
    }

    /**
     * Applies or removes the optional policy and reads it back before reporting
     * success. On Android 10+ disabling also clears the older per-user variant so
     * a policy left by a previous build cannot remain active unexpectedly.
     */
    fun setBlocked(enabled: Boolean): Boolean {
        if (!isDeviceOwnerActive()) return false

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
                "Falha ao ${if (enabled) "ativar" else "desativar"} bloqueio de fontes desconhecidas",
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
