package com.focusguard.security

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LauncherIndexRefreshPolicyTest {
    @Test
    fun `room cache pulses cannot enumerate every launcher label`() {
        val lastRequest = 10_000L

        assertThat(
            LauncherIndexRefreshPolicy.shouldRequest(
                force = false,
                lastRequestElapsed = lastRequest,
                nowElapsed = lastRequest + 5_000L
            )
        ).isFalse()
        assertThat(
            LauncherIndexRefreshPolicy.shouldRequest(
                force = false,
                lastRequestElapsed = lastRequest,
                nowElapsed = lastRequest + LauncherIndexRefreshPolicy.PERIODIC_REFRESH_MILLIS
            )
        ).isTrue()
    }

    @Test
    fun `package locale and launcher changes force immediate refresh`() {
        assertThat(
            LauncherIndexRefreshPolicy.shouldRequest(
                force = true,
                lastRequestElapsed = 50_000L,
                nowElapsed = 50_001L
            )
        ).isTrue()
    }

    @Test
    fun `failed refresh preserves a valid launcher snapshot`() {
        assertThat(
            LauncherIndexRefreshPolicy.shouldPublishCandidate(
                querySucceeded = false,
                candidateSize = 0,
                hasSuccessfulSnapshot = true
            )
        ).isFalse()
        assertThat(
            LauncherIndexRefreshPolicy.shouldPublishCandidate(
                querySucceeded = true,
                candidateSize = 0,
                hasSuccessfulSnapshot = true
            )
        ).isFalse()
        assertThat(
            LauncherIndexRefreshPolicy.shouldPublishCandidate(
                querySucceeded = true,
                candidateSize = 0,
                hasSuccessfulSnapshot = false
            )
        ).isTrue()
        assertThat(
            LauncherIndexRefreshPolicy.shouldPublishCandidate(
                querySucceeded = true,
                candidateSize = 12,
                hasSuccessfulSnapshot = true
            )
        ).isTrue()
    }
}
