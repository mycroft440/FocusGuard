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
    fun `package lifecycle background without external foreground keeps grant`() {
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
        ).isFalse()
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
        // another package really reached foreground, then target was reopened.
        // That observed package transition is the one-visit boundary even though
        // the target is already foreground again by the time the monitor polls.
        val observation = observation(
            latestForegroundPackage = target,
            latestTargetForegroundAt = 300L,
            latestNonTargetForegroundAt = 200L,
            latestTargetPackageBackgroundAt = 180L
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
    fun `stopped target without external foreground keeps current visit`() {
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
        ).isFalse()
    }

    @Test
    fun `password accepted with the app out of front is undone`() {
        // A tela inicial voltou depois da senha e o app nunca chegou à frente.
        val observation = observation(
            latestForegroundPackage = "com.example.launcher",
            latestNonTargetForegroundAt = 50L,
            latestOtherAppForegroundAt = 50L
        )

        assertThat(
            PasswordTargetAccessGrant.isPendingGrantAbandoned(
                targetSeenForeground = false,
                observation = observation
            )
        ).isTrue()
    }

    @Test
    fun `pending grant waits while only FocusGuard or nothing came to front`() {
        // A tela de senha voltando depois da digital é o próprio FocusGuard.
        val observation = observation(
            latestForegroundPackage = "com.focusguard",
            latestNonTargetForegroundAt = 50L
        )

        assertThat(
            PasswordTargetAccessGrant.isPendingGrantAbandoned(
                targetSeenForeground = false,
                observation = observation
            )
        ).isFalse()
        assertThat(
            PasswordTargetAccessGrant.isPendingGrantAbandoned(
                targetSeenForeground = false,
                observation = observation(latestForegroundPackage = null)
            )
        ).isFalse()
    }

    @Test
    fun `pending grant keeps the app that came to front after another`() {
        val observation = observation(
            latestForegroundPackage = target,
            latestTargetForegroundAt = 200L,
            latestNonTargetForegroundAt = 50L,
            latestOtherAppForegroundAt = 50L
        )

        assertThat(
            PasswordTargetAccessGrant.isPendingGrantAbandoned(
                targetSeenForeground = false,
                observation = observation
            )
        ).isFalse()
    }

    @Test
    fun `a started visit is never treated as abandoned`() {
        val observation = observation(
            latestForegroundPackage = "com.example.launcher",
            latestTargetForegroundAt = 100L,
            latestNonTargetForegroundAt = 200L,
            latestOtherAppForegroundAt = 200L
        )

        assertThat(
            PasswordTargetAccessGrant.isPendingGrantAbandoned(
                targetSeenForeground = true,
                observation = observation
            )
        ).isFalse()
    }

    private fun observation(
        latestForegroundPackage: String?,
        latestTargetForegroundAt: Long = Long.MIN_VALUE,
        latestNonTargetForegroundAt: Long = Long.MIN_VALUE,
        latestTargetPackageBackgroundAt: Long = Long.MIN_VALUE,
        latestTargetStoppedAt: Long = Long.MIN_VALUE,
        latestOtherAppForegroundAt: Long = Long.MIN_VALUE
    ) = PasswordTargetAccessGrant.AppVisitObservation(
        latestForegroundPackage = latestForegroundPackage,
        latestTargetForegroundAt = latestTargetForegroundAt,
        latestNonTargetForegroundAt = latestNonTargetForegroundAt,
        latestTargetPackageBackgroundAt = latestTargetPackageBackgroundAt,
        latestTargetStoppedAt = latestTargetStoppedAt,
        latestOtherAppForegroundAt = latestOtherAppForegroundAt
    )
}
