package com.focusguard.admin

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.UserManager
import com.focusguard.utils.FocusGuardLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Owns DevicePolicyManager identity and the Direct-Boot-safe policy state. */
@Singleton
class DeviceOwnerPolicyAccess @Inject constructor(
    @ApplicationContext private val context: Context
) {
    val dpm: DevicePolicyManager =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    val componentName: ComponentName = FocusGuardDeviceAdminReceiver.getComponentName(context)

    private val policyStateContext = runCatching {
        context.createDeviceProtectedStorageContext()
    }.getOrDefault(context)
    private val preferences = policyStateContext.getSharedPreferences(
        POLICY_STATE_PREFERENCES,
        Context.MODE_PRIVATE
    )

    fun isDeviceOwnerActive(): Boolean = runCatching {
        dpm.isDeviceOwnerApp(context.packageName)
    }.getOrDefault(false)

    fun isDeviceAdminActive(): Boolean = runCatching {
        dpm.isAdminActive(componentName)
    }.getOrDefault(false)

    fun usesDeviceProtectedPolicyState(): Boolean = policyStateContext.isDeviceProtectedStorage

    fun isBlockingProtectionArmed(): Boolean =
        preferences.getBoolean(BLOCKING_PROTECTION_ARMED_KEY, false)

    fun setBlockingProtectionArmed(armed: Boolean) {
        persistBoolean(
            BLOCKING_PROTECTION_ARMED_KEY,
            armed,
            "Não foi possível persistir o estado da proteção de bloqueio"
        )
    }

    fun isAdultContentProtectionArmed(): Boolean =
        preferences.getBoolean(ADULT_CONTENT_PROTECTION_ARMED_KEY, false)

    fun setAdultContentProtectionArmed(armed: Boolean) {
        persistBoolean(
            ADULT_CONTENT_PROTECTION_ARMED_KEY,
            armed,
            "Não foi possível persistir o estado da proteção de conteúdo adulto"
        )
    }

    fun isPornographyCategoryActive(): Boolean =
        preferences.getBoolean(PORNOGRAPHY_CATEGORY_ACTIVE_KEY, false)

    fun setPornographyCategoryActive(active: Boolean) {
        persistBoolean(
            PORNOGRAPHY_CATEGORY_ACTIVE_KEY,
            active,
            "Não foi possível persistir o estado da categoria Pornografia"
        )
    }

    fun hasCapturedPrivateDns(): Boolean =
        preferences.getBoolean(PREVIOUS_PRIVATE_DNS_CAPTURED_KEY, false)

    fun capturePrivateDns(mode: Int, host: String) {
        val saved = preferences.edit()
            .putBoolean(PREVIOUS_PRIVATE_DNS_CAPTURED_KEY, true)
            .putInt(PREVIOUS_PRIVATE_DNS_MODE_KEY, mode)
            .putString(PREVIOUS_PRIVATE_DNS_HOST_KEY, host)
            .commit()
        if (!saved) {
            FocusGuardLogger.log("DeviceOwner", "Não foi possível guardar o DNS anterior")
        }
    }

    fun capturedPrivateDnsMode(defaultValue: Int): Int =
        preferences.getInt(PREVIOUS_PRIVATE_DNS_MODE_KEY, defaultValue)

    fun capturedPrivateDnsHost(): String =
        preferences.getString(PREVIOUS_PRIVATE_DNS_HOST_KEY, "").orEmpty()

    fun clearCapturedPrivateDns() {
        preferences.edit()
            .remove(PREVIOUS_PRIVATE_DNS_CAPTURED_KEY)
            .remove(PREVIOUS_PRIVATE_DNS_MODE_KEY)
            .remove(PREVIOUS_PRIVATE_DNS_HOST_KEY)
            .apply()
    }

    fun migrateToDeviceProtectedStorage() {
        if (!policyStateContext.isDeviceProtectedStorage || !isUserUnlocked()) return
        if (preferences.getInt(
                POLICY_STATE_STORAGE_VERSION_KEY,
                0
            ) >= POLICY_STATE_STORAGE_VERSION
        ) {
            return
        }

        val legacy = context.getSharedPreferences(POLICY_STATE_PREFERENCES, Context.MODE_PRIVATE)
        val editor = preferences.edit()
        listOf(
            BLOCKING_PROTECTION_ARMED_KEY,
            ADULT_CONTENT_PROTECTION_ARMED_KEY,
            PORNOGRAPHY_CATEGORY_ACTIVE_KEY,
            PREVIOUS_PRIVATE_DNS_CAPTURED_KEY
        ).forEach { key ->
            if (!preferences.contains(key) && legacy.contains(key)) {
                editor.putBoolean(key, legacy.getBoolean(key, false))
            }
        }
        if (!preferences.contains(PREVIOUS_PRIVATE_DNS_MODE_KEY) &&
            legacy.contains(PREVIOUS_PRIVATE_DNS_MODE_KEY)
        ) {
            editor.putInt(
                PREVIOUS_PRIVATE_DNS_MODE_KEY,
                legacy.getInt(PREVIOUS_PRIVATE_DNS_MODE_KEY, 0)
            )
        }
        if (!preferences.contains(PREVIOUS_PRIVATE_DNS_HOST_KEY) &&
            legacy.contains(PREVIOUS_PRIVATE_DNS_HOST_KEY)
        ) {
            editor.putString(
                PREVIOUS_PRIVATE_DNS_HOST_KEY,
                legacy.getString(PREVIOUS_PRIVATE_DNS_HOST_KEY, null)
            )
        }
        val saved = editor
            .putInt(POLICY_STATE_STORAGE_VERSION_KEY, POLICY_STATE_STORAGE_VERSION)
            .commit()
        if (!saved) {
            FocusGuardLogger.log(
                "DeviceOwner",
                "Não foi possível migrar o estado de política para o Direct Boot"
            )
        }
    }

    inline fun applyPolicySafely(name: String, operation: () -> Unit): Boolean =
        runCatching {
            operation()
            true
        }.onFailure { error ->
            FocusGuardLogger.logError("DeviceOwner", "Falha na política $name", error)
        }.getOrDefault(false)

    private fun isUserUnlocked(): Boolean = runCatching {
        (context.getSystemService(Context.USER_SERVICE) as UserManager).isUserUnlocked
    }.getOrDefault(true)

    private fun persistBoolean(key: String, value: Boolean, failureMessage: String) {
        if (!preferences.edit().putBoolean(key, value).commit()) {
            FocusGuardLogger.log("DeviceOwner", failureMessage)
        }
    }

    private companion object {
        const val POLICY_STATE_PREFERENCES = "focusguard_device_owner_policy_state"
        const val POLICY_STATE_STORAGE_VERSION_KEY = "device_protected_storage_version"
        const val POLICY_STATE_STORAGE_VERSION = 1
        const val BLOCKING_PROTECTION_ARMED_KEY = "blocking_protection_armed"
        const val ADULT_CONTENT_PROTECTION_ARMED_KEY = "adult_content_protection_armed"
        const val PORNOGRAPHY_CATEGORY_ACTIVE_KEY = "pornography_category_active"
        const val PREVIOUS_PRIVATE_DNS_CAPTURED_KEY = "previous_private_dns_captured"
        const val PREVIOUS_PRIVATE_DNS_MODE_KEY = "previous_private_dns_mode"
        const val PREVIOUS_PRIVATE_DNS_HOST_KEY = "previous_private_dns_host"
    }
}
