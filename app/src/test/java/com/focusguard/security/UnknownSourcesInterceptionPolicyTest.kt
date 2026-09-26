package com.focusguard.security

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UnknownSourcesInterceptionPolicyTest {

    @Test
    fun `unknown sources screens are recognized by activity`() {
        assertThat(
            UnknownSourcesInterceptionPolicy.matchesClass(
                "com.android.settings.Settings\$ManageExternalSourcesActivity"
            )
        ).isTrue()
        assertThat(
            UnknownSourcesInterceptionPolicy.matchesClass(
                "com.android.settings.Settings\$ManageAppExternalSourcesActivity"
            )
        ).isTrue()
        assertThat(
            UnknownSourcesInterceptionPolicy.matchesClass("com.android.settings.SubSettings")
        ).isFalse()
    }

    @Test
    fun `unknown sources screens are recognized by title`() {
        assertThat(UnknownSourcesInterceptionPolicy.matchesText("Instalar apps desconhecidos"))
            .isTrue()
        assertThat(UnknownSourcesInterceptionPolicy.matchesText("Permitir desta fonte")).isTrue()
        assertThat(UnknownSourcesInterceptionPolicy.matchesText("Install unknown apps")).isTrue()
        assertThat(UnknownSourcesInterceptionPolicy.matchesText("Wi-Fi")).isFalse()
    }
}
