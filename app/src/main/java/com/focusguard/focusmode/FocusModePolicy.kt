package com.focusguard.focusmode

import java.util.concurrent.TimeUnit

/** Pure rules shared by the Focus Mode UI, enforcement and unit tests. */
object FocusModePolicy {
    const val MAX_DAYS = 120
    const val MAX_DURATION_MILLIS = 120L * 24L * 60L * 60L * 1_000L

    enum class DurationUnit {
        MINUTES,
        HOURS,
        DAYS
    }

    enum class NavigationKeyDecision {
        PASS,
        CONSUME,
        RETURN_TO_FOCUS_GUARD
    }

    fun resolveDurationMillis(unit: DurationUnit, amount: Int?): Long? {
        val value = amount ?: return null
        if (value <= 0 || value > maxAmountFor(unit)) return null

        return when (unit) {
            DurationUnit.MINUTES -> TimeUnit.MINUTES.toMillis(value.toLong())
            DurationUnit.HOURS -> TimeUnit.HOURS.toMillis(value.toLong())
            DurationUnit.DAYS -> TimeUnit.DAYS.toMillis(value.toLong())
        }
    }

    fun maxAmountFor(unit: DurationUnit): Int = when (unit) {
        DurationUnit.MINUTES -> MAX_DAYS * 24 * 60
        DurationUnit.HOURS -> MAX_DAYS * 24
        DurationUnit.DAYS -> MAX_DAYS
    }

    fun buildAllowedPackages(
        focusGuardPackage: String,
        mandatoryPackages: Collection<String>,
        selectedPackages: Collection<String>
    ): Set<String> = (mandatoryPackages + selectedPackages + focusGuardPackage)
        .asSequence()
        .filter(String::isNotBlank)
        .toSet()

    fun packagesToBlock(
        launchablePackages: Collection<String>,
        allowedPackages: Collection<String>
    ): Set<String> = launchablePackages
        .asSequence()
        .filter(String::isNotBlank)
        .filterNot(allowedPackages.toSet()::contains)
        .toSet()

    fun visibleAllowedPackages(
        launchablePackages: Collection<String>,
        allowedPackages: Collection<String>,
        mandatoryPackages: Collection<String>
    ): Set<String> {
        val allowed = allowedPackages.toSet()
        val mandatory = mandatoryPackages.toSet()
        return launchablePackages
            .asSequence()
            .filter(String::isNotBlank)
            .filter(allowed::contains)
            .filterNot(mandatory::contains)
            .toSet()
    }

    fun packagesToEnforce(
        configuredBlockedPackages: Collection<String>,
        focusModeBlockedPackages: Collection<String>,
        focusModeAllowedPackages: Collection<String>
    ): Set<String> = (configuredBlockedPackages + focusModeBlockedPackages)
        .asSequence()
        .filter(String::isNotBlank)
        .filterNot(focusModeAllowedPackages.toSet()::contains)
        .toSet()

    fun packagesForAccessibility(
        enforcedPackages: Collection<String>,
        focusModeBlockedPackages: Collection<String>,
        nativeFocusLockdownActive: Boolean
    ): Set<String> = enforcedPackages
        .asSequence()
        .filterNot {
            nativeFocusLockdownActive && it in focusModeBlockedPackages
        }
        .toSet()

    fun usesNativeFocusLockdown(
        deviceOwnerActive: Boolean,
        systemLockdownSupported: Boolean
    ): Boolean = deviceOwnerActive && systemLockdownSupported

    fun shouldRedirectToFocusGuard(
        focusModeActive: Boolean,
        focusModeFallbackActive: Boolean,
        foregroundPackage: String,
        focusGuardPackage: String,
        launcherPackage: String?,
        focusModeBlockedPackages: Collection<String>
    ): Boolean = focusModeActive &&
        foregroundPackage.isNotBlank() &&
        foregroundPackage != focusGuardPackage &&
        (
            foregroundPackage == launcherPackage ||
                (focusModeFallbackActive && foregroundPackage in focusModeBlockedPackages)
            )

    fun focusNavigationKeyDecision(
        focusModeActive: Boolean,
        focusGuardForeground: Boolean,
        powerMenuVisible: Boolean,
        isBackOrHomeKey: Boolean,
        actionDown: Boolean,
        repeatCount: Int
    ): NavigationKeyDecision = when {
        !focusModeActive ||
            focusGuardForeground ||
            !isBackOrHomeKey ||
            powerMenuVisible -> NavigationKeyDecision.PASS
        actionDown && repeatCount == 0 -> NavigationKeyDecision.RETURN_TO_FOCUS_GUARD
        else -> NavigationKeyDecision.CONSUME
    }

    fun shouldSuppressNotification(
        focusModeActive: Boolean,
        notificationPackage: String,
        focusGuardPackage: String,
        blockedPackages: Collection<String>,
        exemptPackages: Collection<String> = emptySet()
    ): Boolean = focusModeActive &&
        notificationPackage != focusGuardPackage &&
        notificationPackage in blockedPackages &&
        notificationPackage !in exemptPackages

    fun canPomodoroReleaseKiosk(focusModeActive: Boolean): Boolean = !focusModeActive
}

data class FocusModeSession(
    val startedAtMillis: Long,
    val endTimeMillis: Long,
    val durationMillis: Long,
    val allowedPackages: Set<String>,
    val blockedPackages: Set<String>,
    val nonSuspendablePackages: Set<String> = emptySet(),
    val grayscaleEnabled: Boolean = false
) {
    fun isActive(nowMillis: Long = System.currentTimeMillis()): Boolean =
        endTimeMillis > nowMillis && durationMillis > 0L

    fun remainingMillis(nowMillis: Long = System.currentTimeMillis()): Long =
        (endTimeMillis - nowMillis).coerceAtLeast(0L)
}

data class FocusModeSelectableApp(
    val packageName: String,
    val appName: String
)
