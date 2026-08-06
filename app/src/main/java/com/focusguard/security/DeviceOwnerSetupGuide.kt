package com.focusguard.security

/** Values shared by the Device Owner setup screen and its unit tests. */
object DeviceOwnerSetupGuide {
    private const val ADMIN_RECEIVER =
        "com.focusguard.admin.FocusGuardDeviceAdminReceiver"

    fun buildAdbCommand(packageName: String): String =
        "adb shell dpm set-device-owner $packageName/$ADMIN_RECEIVER"

    /**
     * The provisioning conditions the phone can answer about itself.
     *
     * Everything else the tutorial asks for — accounts removed, ADB installed, cable
     * plugged in — is invisible to an ordinary app, so the guide keeps asking for it
     * in writing instead of pretending to have checked.
     */
    data class Environment(
        val runningOnMainUser: Boolean,
        val usbDebuggingEnabled: Boolean,
        val setupWizardCompleted: Boolean
    )

    enum class Requirement { MAIN_USER, USB_DEBUGGING, FRESH_DEVICE }

    enum class Status {
        /** Already satisfied; nothing to do. */
        READY,

        /** The person still has to do something, and can do it right now. */
        PENDING,

        /** Android will refuse the command while this holds. */
        BLOCKING,

        /** May be refused — the outcome depends on the manufacturer. */
        UNCERTAIN
    }

    data class Check(val requirement: Requirement, val status: Status)

    /**
     * Reads the environment into one row per requirement, always in the same order.
     *
     * A missing condition is only reported as [Status.BLOCKING] when Android is known
     * to refuse: running outside the main user means the command would provision a
     * different install than the one on screen. A device that has finished its setup
     * wizard is [Status.UNCERTAIN] instead — plenty of models still accept the command
     * once every account is gone, and telling everyone else to factory reset would
     * cost them the device for nothing.
     */
    fun inspect(environment: Environment): List<Check> = listOf(
        Check(
            Requirement.MAIN_USER,
            if (environment.runningOnMainUser) Status.READY else Status.BLOCKING
        ),
        Check(
            Requirement.USB_DEBUGGING,
            if (environment.usbDebuggingEnabled) Status.READY else Status.PENDING
        ),
        Check(
            Requirement.FRESH_DEVICE,
            if (environment.setupWizardCompleted) Status.UNCERTAIN else Status.READY
        )
    )

    /** True when something the app can see will make the command fail outright. */
    fun hasBlockingCondition(environment: Environment): Boolean =
        inspect(environment).any { it.status == Status.BLOCKING }
}
