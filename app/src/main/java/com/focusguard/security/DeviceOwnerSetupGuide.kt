package com.focusguard.security

/** Values shared by the Device Owner setup screen and its unit tests. */
object DeviceOwnerSetupGuide {
    private const val ADMIN_RECEIVER =
        "com.focusguard.admin.FocusGuardDeviceAdminReceiver"

    fun buildAdbCommand(packageName: String): String =
        "adb shell dpm set-device-owner $packageName/$ADMIN_RECEIVER"
}
