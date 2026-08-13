package com.focusguard.security

import android.content.Context
import android.os.Build

/**
 * Synchronous, device-protected snapshot used by the accessibility hot path.
 *
 * Room remains the source of truth for configured blocks. This tiny snapshot is
 * the fail-closed bridge across process death and AccessibilityService startup:
 * the first Settings event must not wait for an asynchronous Room query before
 * knowing that FocusGuard is currently protecting a consented block.
 */
object SelfProtectionStateStore {
    private const val PREFERENCES_NAME = "focusguard_self_protection_state"
    private const val KEY_ARMED = "armed"

    fun isArmed(context: Context): Boolean =
        preferences(context).getBoolean(KEY_ARMED, false)

    /** Uses commit deliberately: callers must know the snapshot is durable now. */
    fun setArmed(context: Context, armed: Boolean): Boolean =
        preferences(context).edit().putBoolean(KEY_ARMED, armed).commit()

    internal fun usesDeviceProtectedStorage(context: Context): Boolean =
        storageContext(context).isDeviceProtectedStorage

    private fun preferences(context: Context) = storageContext(context)
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private fun storageContext(context: Context): Context {
        val appContext = context.applicationContext
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching { appContext.createDeviceProtectedStorageContext() }
                .getOrDefault(appContext)
        } else {
            appContext
        }
    }
}
