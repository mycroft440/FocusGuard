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
 * Optional block for installation from unknown sources.
 *
 * It works without Device Owner: while enabled, the accessibility service closes the
 * Android screens that grant "Install unknown apps" (see
 * [com.focusguard.security.UnknownSourcesInterceptionPolicy]). When Device Owner is
 * active, the matching user restriction is applied as well. It is enabled only after
 * the user revokes the existing grants in Android settings and confirms it here.
 */
class UnknownSourcesSecurityManager private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val dpm = appContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val adminComponent = FocusGuardDeviceAdminReceiver.getComponentName(appContext)

    companion object {
        private const val PREFS = "extra_security"
        private const val KEY_ACCESSIBILITY_BLOCK = "unknown_sources_accessibility_block"
        private const val KEY_ACTIVATION_PENDING = "unknown_sources_activation_pending"
        private const val KEY_READY_TO_ENABLE = "unknown_sources_ready_to_enable"

        @Volatile
        private var instance: UnknownSourcesSecurityManager? = null

        /** Leitura rápida para o serviço de acessibilidade (SharedPreferences em memória). */
        fun isAccessibilityBlockEnabled(context: Context): Boolean =
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_ACCESSIBILITY_BLOCK, false)

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

    /** Whether the block is on (accessibility interception, plus Device Owner if active). */
    fun isBlocked(): Boolean = isAccessibilityBlockEnabled(appContext)

    /**
     * Keeps the activation flow recoverable while Android Settings is in front of the app.
     * The navigation shell can be rebuilt when FocusGuard returns to the foreground, so this
     * state cannot live only inside the ExtraSecurityScreen composable.
     */
    fun isActivationPending(): Boolean =
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ACTIVATION_PENDING, false)

    /** True after the user confirmed that the previously granted sources were disabled. */
    fun isReadyToEnable(): Boolean =
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_READY_TO_ENABLE, false)

    /** Marks that the user left FocusGuard to review/revoke existing unknown-source grants. */
    fun markSettingsReviewStarted(): Boolean =
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ACTIVATION_PENDING, true)
            .putBoolean(KEY_READY_TO_ENABLE, false)
            .commit()

    /** Keeps the final "Ativar bloqueio" step available even if the UI is rebuilt again. */
    fun markReadyToEnable(): Boolean =
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ACTIVATION_PENDING, true)
            .putBoolean(KEY_READY_TO_ENABLE, true)
            .commit()

    fun clearActivationFlow(): Boolean =
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_ACTIVATION_PENDING)
            .remove(KEY_READY_TO_ENABLE)
            .commit()

    /**
     * Turns the accessibility block on or off. With Device Owner active, the user
     * restriction follows the same state; its failure does not undo the accessibility
     * block, which does not depend on it.
     */
    fun setBlocked(enabled: Boolean): Boolean {
        val saved = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ACCESSIBILITY_BLOCK, enabled)
            .remove(KEY_ACTIVATION_PENDING)
            .remove(KEY_READY_TO_ENABLE)
            .commit()
        if (isDeviceOwnerActive()) applyDeviceOwnerRestriction(enabled)
        return saved
    }

    private fun applyDeviceOwnerRestriction(enabled: Boolean): Boolean {
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
            dpm.getUserRestrictions(adminComponent)
                .getBoolean(requiredRestriction, false) == enabled
        }.onFailure { error ->
            FocusGuardLogger.logError(
                "ExtraSecurity",
                "Falha ao ${if (enabled) "ativar" else "desativar"} restrição de fontes desconhecidas",
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
