package com.focusguard.security

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SettingsInterceptionRootSignalsTest {

    @Test
    fun repeatedSignalReadsEvaluateUnderlyingTreeQueryOnlyOnce() {
        var focusGuardCalls = 0
        var accessibilityCalls = 0

        val signals = SettingsInterceptionPolicy.RootSignals(
            mentionsAccessibility = {
                accessibilityCalls++
                false
            },
            mentionsDeviceAdmin = { false },
            mentionsFocusGuard = {
                focusGuardCalls++
                true
            },
            mentionsDestructiveControl = { false },
            mentionsEssentialSpecialAccess = { false }
        )

        assertThat(signals.mentionsFocusGuard()).isTrue()
        assertThat(signals.mentionsFocusGuard()).isTrue()
        assertThat(signals.mentionsAccessibility()).isFalse()
        assertThat(signals.mentionsAccessibility()).isFalse()

        assertThat(focusGuardCalls).isEqualTo(1)
        assertThat(accessibilityCalls).isEqualTo(1)
    }
}
