package com.focusguard.security

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PasswordTargetAccessGrantPolicyTest {

    private val target = "com.example.target"

    @Test
    fun `grant stays alive before target ever reaches foreground`() {
        val observation = observation(
            latestForegroundPackage = "com.example.launcher",
            latestTargetForegroundAt = Long.MIN_VALUE,
            latestNonTargetForegroundAt = 50L
        )

        assertThat(
            PasswordTargetAccessGrant.shouldRevokeAppGrant(
                target = target,
                targetSeenForeground = false,
                visitStartedAt = Long.MIN_VALUE,
                observation = observation
            )
        ).isFalse()
    }

    @Test
    fun `grant stays alive while target remains the latest foreground app`() {
        val observation = observation(
            latestForegroundPackage = target,
            latestTargetForegroundAt = 200L,
            latestNonTargetForegroundAt = 50L
        )

        assertThat(
            PasswordTargetAccessGrant.shouldRevokeAppGrant(
                target = target,
                targetSeenForeground = true,
                visitStartedAt = 100L,
                observation = observation
            )
        ).isFalse()
    }

    @Test
    fun `package background revokes grant even if launcher foreground event is delayed`() {
        val observation = observation(
            latestForegroundPackage = target,
            latestTargetForegroundAt = 100L,
            latestTargetPackageBackgroundAt = 200L
        )

        assertThat(
            PasswordTargetAccessGrant.shouldRevokeAppGrant(
                target = target,
                targetSeenForeground = true,
                visitStartedAt = 100L,
                observation = observation
            )
        ).isTrue()
    }

    @Test
    fun `another foreground app revokes the one visit grant`() {
        val observation = observation(
            latestForegroundPackage = "com.example.other",
            latestTargetForegroundAt = 100L,
            latestNonTargetForegroundAt = 200L
        )

        assertThat(
            PasswordTargetAccessGrant.shouldRevokeAppGrant(
                target = target,
                targetSeenForeground = true,
                visitStartedAt = 100L,
                observation = observation
            )
        ).isTrue()
    }

    @Test
    fun `rapid leave and reopen still revokes original visit`() {
        // The polling loop can observe all three events at once: target opened,
        // target went to background, then target was immediately opened again.
        // The package-background boundary must still end the original grant.
        val observation = observation(
            latestForegroundPackage = target,
            latestTargetForegroundAt = 300L,
            latestTargetPackageBackgroundAt = 200L
        )

        assertThat(
            PasswordTargetAccessGrant.shouldRevokeAppGrant(
                target = target,
                targetSeenForeground = true,
                visitStartedAt = 100L,
                observation = observation
            )
        ).isTrue()
    }

    @Test
    fun `rapid switch away and back still revokes original visit`() {
        val observation = observation(
            latestForegroundPackage = target,
            latestTargetForegroundAt = 300L,
            latestNonTargetForegroundAt = 200L
        )

        assertThat(
            PasswordTargetAccessGrant.shouldRevokeAppGrant(
                target = target,
                targetSeenForeground = true,
                visitStartedAt = 100L,
                observation = observation
            )
        ).isTrue()
    }

    @Test
    fun `newer target activity keeps intra app navigation authorized`() {
        val observation = observation(
            latestForegroundPackage = target,
            latestTargetForegroundAt = 300L,
            latestTargetStoppedAt = 200L
        )

        assertThat(
            PasswordTargetAccessGrant.shouldRevokeAppGrant(
                target = target,
                targetSeenForeground = true,
                visitStartedAt = 100L,
                observation = observation
            )
        ).isFalse()
    }

    @Test
    fun `stopped target with no newer target foreground revokes as OEM fallback`() {
        val observation = observation(
            latestForegroundPackage = target,
            latestTargetForegroundAt = 100L,
            latestTargetStoppedAt = 200L
        )

        assertThat(
            PasswordTargetAccessGrant.shouldRevokeAppGrant(
                target = target,
                targetSeenForeground = true,
                visitStartedAt = 100L,
                observation = observation
            )
        ).isTrue()
    }

    private fun observation(
        latestForegroundPackage: String?,
        latestTargetForegroundAt: Long = Long.MIN_VALUE,
        latestNonTargetForegroundAt: Long = Long.MIN_VALUE,
        latestTargetPackageBackgroundAt: Long = Long.MIN_VALUE,
        latestTargetStoppedAt: Long = Long.MIN_VALUE
    ) = PasswordTargetAccessGrant.AppVisitObservation(
        latestForegroundPackage = latestForegroundPackage,
        latestTargetForegroundAt = latestTargetForegroundAt,
        latestNonTargetForegroundAt = latestNonTargetForegroundAt,
        latestTargetPackageBackgroundAt = latestTargetPackageBackgroundAt,
        latestTargetStoppedAt = latestTargetStoppedAt
    )
}
