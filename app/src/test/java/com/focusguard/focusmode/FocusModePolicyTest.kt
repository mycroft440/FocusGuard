package com.focusguard.focusmode

import com.focusguard.domain.model.FocusModeSession
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.TimeUnit
import org.junit.Test

class FocusModePolicyTest {

    @Test
    fun `duration accepts minutes hours and days up to one hundred twenty days`() {
        assertThat(
            FocusModePolicy.resolveDurationMillis(
                FocusModePolicy.DurationUnit.MINUTES,
                1
            )
        ).isEqualTo(TimeUnit.MINUTES.toMillis(1))
        assertThat(
            FocusModePolicy.resolveDurationMillis(
                FocusModePolicy.DurationUnit.HOURS,
                2
            )
        ).isEqualTo(TimeUnit.HOURS.toMillis(2))
        assertThat(
            FocusModePolicy.resolveDurationMillis(
                FocusModePolicy.DurationUnit.DAYS,
                FocusModePolicy.MAX_DAYS
            )
        ).isEqualTo(TimeUnit.DAYS.toMillis(FocusModePolicy.MAX_DAYS.toLong()))
    }

    @Test
    fun `duration rejects empty zero negative and values beyond the cap`() {
        assertThat(
            FocusModePolicy.resolveDurationMillis(
                FocusModePolicy.DurationUnit.MINUTES,
                null
            )
        ).isNull()
        assertThat(
            FocusModePolicy.resolveDurationMillis(
                FocusModePolicy.DurationUnit.HOURS,
                0
            )
        ).isNull()
        assertThat(
            FocusModePolicy.resolveDurationMillis(
                FocusModePolicy.DurationUnit.DAYS,
                -1
            )
        ).isNull()
        assertThat(
            FocusModePolicy.resolveDurationMillis(
                FocusModePolicy.DurationUnit.DAYS,
                FocusModePolicy.MAX_DAYS + 1
            )
        ).isNull()
    }

    @Test
    fun `allowlist always contains FocusGuard mandatory and selected apps`() {
        val allowed = FocusModePolicy.buildAllowedPackages(
            focusGuardPackage = "com.focusguard.v2",
            mandatoryPackages = setOf("com.phone", "com.sms"),
            selectedPackages = setOf("com.whatsapp", "")
        )

        assertThat(allowed).containsExactly(
            "com.focusguard.v2",
            "com.phone",
            "com.sms",
            "com.whatsapp"
        )
    }

    @Test
    fun `only launchable apps outside the allowlist are blocked`() {
        val blocked = FocusModePolicy.packagesToBlock(
            launchablePackages = listOf("com.phone", "com.social", "com.game", ""),
            allowedPackages = setOf("com.phone", "com.focusguard.v2")
        )

        assertThat(blocked).containsExactly("com.social", "com.game")
    }

    @Test
    fun `launcher grid contains only chosen launchable non essential apps`() {
        val visible = FocusModePolicy.visibleAllowedPackages(
            launchablePackages = listOf(
                "com.phone",
                "com.sms",
                "com.whatsapp",
                "com.music",
                "com.blocked"
            ),
            allowedPackages = setOf(
                "com.focusguard.v2",
                "com.phone",
                "com.sms",
                "com.whatsapp",
                "com.music"
            ),
            mandatoryPackages = setOf("com.focusguard.v2", "com.phone", "com.sms")
        )

        assertThat(visible).containsExactly("com.whatsapp", "com.music")
    }

    @Test
    fun `temporary allowlist overrides older blocks for phone sms and chosen apps`() {
        val enforced = FocusModePolicy.packagesToEnforce(
            configuredBlockedPackages = setOf("com.phone", "com.sms", "com.whatsapp"),
            focusModeBlockedPackages = setOf("com.social", "com.game"),
            focusModeAllowedPackages = setOf("com.phone", "com.sms", "com.whatsapp")
        )

        assertThat(enforced).containsExactly("com.social", "com.game")
    }

    @Test
    fun `native focus mode packages do not enter the accessibility overlay list`() {
        val accessibilityPackages = FocusModePolicy.packagesForAccessibility(
            enforcedPackages = setOf("com.password.block", "com.social", "com.game"),
            focusModeBlockedPackages = setOf("com.social", "com.game"),
            nativeFocusLockdownActive = true
        )

        assertThat(accessibilityPackages).containsExactly("com.password.block")
    }

    @Test
    fun `consumer focus mode sends blocked packages to accessibility`() {
        val accessibilityPackages = FocusModePolicy.packagesForAccessibility(
            enforcedPackages = setOf("com.password.block", "com.social", "com.game"),
            focusModeBlockedPackages = setOf("com.social", "com.game"),
            nativeFocusLockdownActive = false
        )

        assertThat(accessibilityPackages).containsExactly(
            "com.password.block",
            "com.social",
            "com.game"
        )
    }

    @Test
    fun `consumer focus redirects blocked apps and the launcher`() {
        assertThat(
            FocusModePolicy.shouldRedirectToFocusGuard(
                focusModeFallbackActive = true,
                foregroundPackage = "com.social",
                focusGuardPackage = "com.focusguard.v2",
                launcherPackage = "com.launcher",
                focusModeBlockedPackages = setOf("com.social")
            )
        ).isTrue()
        assertThat(
            FocusModePolicy.shouldRedirectToFocusGuard(
                focusModeFallbackActive = true,
                foregroundPackage = "com.launcher",
                focusGuardPackage = "com.focusguard.v2",
                launcherPackage = "com.launcher",
                focusModeBlockedPackages = emptySet()
            )
        ).isTrue()
        assertThat(
            FocusModePolicy.shouldRedirectToFocusGuard(
                focusModeFallbackActive = true,
                foregroundPackage = "com.whatsapp",
                focusGuardPackage = "com.focusguard.v2",
                launcherPackage = "com.launcher",
                focusModeBlockedPackages = setOf("com.social")
            )
        ).isFalse()
    }

    @Test
    fun `notifications are removed only for blocked suspendable apps`() {
        assertThat(
            FocusModePolicy.shouldSuppressNotification(
                focusModeActive = true,
                notificationPackage = "com.social",
                focusGuardPackage = "com.focusguard.v2",
                blockedPackages = setOf("com.social")
            )
        ).isTrue()
        assertThat(
            FocusModePolicy.shouldSuppressNotification(
                focusModeActive = false,
                notificationPackage = "com.social",
                focusGuardPackage = "com.focusguard.v2",
                blockedPackages = setOf("com.social")
            )
        ).isFalse()
        assertThat(
            FocusModePolicy.shouldSuppressNotification(
                focusModeActive = true,
                notificationPackage = "com.phone",
                focusGuardPackage = "com.focusguard.v2",
                blockedPackages = setOf("com.social")
            )
        ).isFalse()
        assertThat(
            FocusModePolicy.shouldSuppressNotification(
                focusModeActive = true,
                notificationPackage = "com.system.launcher",
                focusGuardPackage = "com.focusguard.v2",
                blockedPackages = setOf("com.system.launcher"),
                exemptPackages = setOf("com.system.launcher")
            )
        ).isFalse()
    }

    @Test
    fun `session becomes inactive exactly at its deadline`() {
        val session = FocusModeSession(
            startedAtMillis = 1_000L,
            endTimeMillis = 61_000L,
            durationMillis = 60_000L,
            allowedPackages = setOf("com.focusguard.v2"),
            blockedPackages = setOf("com.social")
        )

        assertThat(session.isActive(60_999L)).isTrue()
        assertThat(session.isActive(61_000L)).isFalse()
        assertThat(session.remainingMillis(70_000L)).isEqualTo(0L)
    }

    @Test
    fun `pomodoro cleanup never releases an active focus mode kiosk`() {
        assertThat(FocusModePolicy.canPomodoroReleaseKiosk(focusModeActive = true)).isFalse()
        assertThat(FocusModePolicy.canPomodoroReleaseKiosk(focusModeActive = false)).isTrue()
    }
}
