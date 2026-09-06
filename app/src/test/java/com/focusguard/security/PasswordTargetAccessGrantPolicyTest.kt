package com.focusguard.security

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PasswordTargetAccessGrantPolicyTest {

    @Test
    fun `grant stays alive before target ever reaches foreground`() {
        val observation = PasswordTargetAccessGrant.AppVisitObservation(
            latestForegroundPackage = "com.example.launcher",
            latestTargetForegroundAt = Long.MIN_VALUE,
            latestTargetBackgroundAt = Long.MIN_VALUE
        )

        assertThat(
            PasswordTargetAccessGrant.shouldRevokeAppGrant(
                target = "com.example.target",
                targetSeenForeground = false,
                observation = observation
            )
        ).isFalse()
    }

    @Test
    fun `grant stays alive while target remains the latest foreground app`() {
        val observation = PasswordTargetAccessGrant.AppVisitObservation(
            latestForegroundPackage = "com.example.target",
            latestTargetForegroundAt = 200L,
            latestTargetBackgroundAt = 100L
        )

        assertThat(
            PasswordTargetAccessGrant.shouldRevokeAppGrant(
                target = "com.example.target",
                targetSeenForeground = true,
                observation = observation
            )
        ).isFalse()
    }

    @Test
    fun `leaving target revokes grant even if launcher foreground event is delayed`() {
        val observation = PasswordTargetAccessGrant.AppVisitObservation(
            latestForegroundPackage = "com.example.target",
            latestTargetForegroundAt = 100L,
            latestTargetBackgroundAt = 200L
        )

        assertThat(
            PasswordTargetAccessGrant.shouldRevokeAppGrant(
                target = "com.example.target",
                targetSeenForeground = true,
                observation = observation
            )
        ).isTrue()
    }

    @Test
    fun `another foreground app revokes the one visit grant`() {
        val observation = PasswordTargetAccessGrant.AppVisitObservation(
            latestForegroundPackage = "com.example.other",
            latestTargetForegroundAt = 100L,
            latestTargetBackgroundAt = 90L
        )

        assertThat(
            PasswordTargetAccessGrant.shouldRevokeAppGrant(
                target = "com.example.target",
                targetSeenForeground = true,
                observation = observation
            )
        ).isTrue()
    }

    @Test
    fun `newer target foreground activity keeps intra app navigation authorized`() {
        val observation = PasswordTargetAccessGrant.AppVisitObservation(
            latestForegroundPackage = "com.example.target",
            latestTargetForegroundAt = 300L,
            latestTargetBackgroundAt = 200L
        )

        assertThat(
            PasswordTargetAccessGrant.shouldRevokeAppGrant(
                target = "com.example.target",
                targetSeenForeground = true,
                observation = observation
            )
        ).isFalse()
    }
}
