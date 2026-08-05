package com.focusguard.security

import com.focusguard.security.UsageAccessPausePolicy.State
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UsageAccessPausePolicyTest {

    @Test
    fun `granted permission means limits are being measured`() {
        val state = UsageAccessPausePolicy.evaluate(
            usageAccessGranted = true,
            enabledAppLimitCount = 3
        )

        assertThat(state).isEqualTo(State.MEASURING)
        assertThat(UsageAccessPausePolicy.shouldWarn(state)).isFalse()
    }

    @Test
    fun `granted permission with no limits still means measuring`() {
        val state = UsageAccessPausePolicy.evaluate(
            usageAccessGranted = true,
            enabledAppLimitCount = 0
        )

        assertThat(state).isEqualTo(State.MEASURING)
    }

    @Test
    fun `revoked permission with active limits raises the warning`() {
        // The silent failure this policy exists for: limits stop enforcing and the
        // app looks like it is still working.
        val state = UsageAccessPausePolicy.evaluate(
            usageAccessGranted = false,
            enabledAppLimitCount = 1
        )

        assertThat(state).isEqualTo(State.REVOKED_WITH_LIMITS_AT_RISK)
        assertThat(UsageAccessPausePolicy.shouldWarn(state)).isTrue()
    }

    @Test
    fun `revoked permission without limits stays quiet`() {
        // Warning here would be noise, and noise trains people to dismiss the
        // warning that matters.
        val state = UsageAccessPausePolicy.evaluate(
            usageAccessGranted = false,
            enabledAppLimitCount = 0
        )

        assertThat(state).isEqualTo(State.REVOKED_WITHOUT_IMPACT)
        assertThat(UsageAccessPausePolicy.shouldWarn(state)).isFalse()
    }

    @Test
    fun `convenience overload agrees with the two-step form`() {
        listOf(true, false).forEach { granted ->
            listOf(0, 1, 5).forEach { count ->
                assertThat(
                    UsageAccessPausePolicy.shouldWarn(granted, count)
                ).isEqualTo(
                    UsageAccessPausePolicy.shouldWarn(
                        UsageAccessPausePolicy.evaluate(granted, count)
                    )
                )
            }
        }
    }

    @Test
    fun `measurement is unavailable only when limits exist and permission is gone`() {
        assertThat(
            UsageAccessPausePolicy.measurementIsUnavailable(
                usageAccessGranted = false,
                enabledAppLimitCount = 2
            )
        ).isTrue()

        // Nothing to measure: an empty usage map is honest here.
        assertThat(
            UsageAccessPausePolicy.measurementIsUnavailable(
                usageAccessGranted = false,
                enabledAppLimitCount = 0
            )
        ).isFalse()

        // Permission held: an empty usage map really means "not used yet".
        assertThat(
            UsageAccessPausePolicy.measurementIsUnavailable(
                usageAccessGranted = true,
                enabledAppLimitCount = 2
            )
        ).isFalse()
    }

    @Test
    fun `only one state warrants a warning`() {
        // Guards against a new state silently defaulting to quiet or to noisy.
        val warning = State.entries.filter(UsageAccessPausePolicy::shouldWarn)

        assertThat(warning).containsExactly(State.REVOKED_WITH_LIMITS_AT_RISK)
    }
}
