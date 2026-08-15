package com.focusguard.focusmode

import android.app.ActivityOptions
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.UserManager
import com.focusguard.MainActivity
import com.focusguard.admin.DeviceOwnerManager
import com.focusguard.admin.FocusGuardDeviceAdminReceiver
import com.focusguard.utils.FocusGuardLogger

/**
 * Owns the system-shell part of Focus Mode.
 *
 * The strongest path is available when FocusGuard is Device Owner: Android's
 * Lock Task mode removes Home/Overview and keeps non-allowlisted tasks out of
 * the kiosk. While that session is active we also reject third-party overlay
 * windows, preventing another app from drawing an escape surface above the
 * FocusGuard/allowlisted task.
 *
 * Global power actions remain enabled by [DeviceOwnerManager]; this class does
 * not interfere with shutdown, restart, the keyguard used during boot, or the
 * emergency/phone packages intentionally allowlisted by Focus Mode.
 */
object FocusModeKioskController {
    const val EXTRA_RESTORE_FOCUS_MODE = "com.focusguard.extra.RESTORE_FOCUS_MODE"

    /**
     * Reconciles the Focus-Mode-only window restriction against persisted state.
     * This is Direct-Boot safe because [FocusModeStore] uses device-protected storage.
     */
    fun reconcileSystemRestrictions(context: Context): Boolean {
        val appContext = context.applicationContext
        val ownerManager = DeviceOwnerManager.getInstance(appContext)
        if (!ownerManager.isDeviceOwnerActive() || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return true
        }

        val dpm = appContext.getSystemService(DevicePolicyManager::class.java) ?: return false
        val admin = FocusGuardDeviceAdminReceiver.getComponentName(appContext)
        val shouldBlockOverlays = FocusModeStore.isActive(appContext)

        return runCatching {
            if (shouldBlockOverlays) {
                dpm.addUserRestriction(admin, UserManager.DISALLOW_CREATE_WINDOWS)
            } else {
                dpm.clearUserRestriction(admin, UserManager.DISALLOW_CREATE_WINDOWS)
            }

            val actual = dpm.getUserRestrictions(admin).getBoolean(
                UserManager.DISALLOW_CREATE_WINDOWS,
                false
            )
            actual == shouldBlockOverlays
        }.onFailure { error ->
            FocusGuardLogger.logError(
                "FocusMode",
                "Falha ao reconciliar bloqueio de janelas do modo quiosque",
                error
            )
        }.getOrDefault(false)
    }

    /**
     * Brings the FocusGuard shell to the foreground. On Android 9+ Device Owner
     * devices, the activity is launched directly into Lock Task mode, avoiding
     * the short Home-screen window that would otherwise exist after a reboot or
     * process recreation.
     */
    fun launchFocusGuardHome(context: Context): Boolean {
        val appContext = context.applicationContext
        if (!FocusModeStore.isActive(appContext)) return false

        reconcileSystemRestrictions(appContext)

        val intent = Intent(appContext, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            putExtra(EXTRA_RESTORE_FOCUS_MODE, true)
        }

        return runCatching {
            val ownerManager = DeviceOwnerManager.getInstance(appContext)
            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                ownerManager.isDeviceOwnerActive() &&
                ownerManager.isFocusModeLockTaskPermitted()
            ) {
                val options = ActivityOptions.makeBasic().apply {
                    setLockTaskEnabled(true)
                }
                appContext.startActivity(intent, options.toBundle())
            } else {
                // Consumer mode cannot obtain true kiosk privileges. Accessibility
                // remains responsible for bouncing Home/blocked apps back here.
                appContext.startActivity(intent)
            }
            true
        }.onFailure { error ->
            FocusGuardLogger.logError(
                "FocusMode",
                "Falha ao restaurar a tela principal do Modo Foco",
                error
            )
        }.getOrDefault(false)
    }
}
