package com.focusguard.security

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DevelopmentAccessPolicyTest {

    @Test
    fun `configured password must match Dev00 exactly`() {
        assertThat(
            DevelopmentAccessPolicy.acceptsPassword(
                input = "Dev00",
                configuredPassword = "Dev00"
            )
        ).isTrue()

        listOf("dev00", "DEV00", "Dev0", "Dev000", "Dev00extra").forEach { input ->
            assertThat(
                DevelopmentAccessPolicy.acceptsPassword(
                    input = input,
                    configuredPassword = "Dev00"
                )
            ).isFalse()
        }
    }

    @Test
    fun `empty configured password disables the area`() {
        assertThat(
            DevelopmentAccessPolicy.isAvailable(
                configuredPassword = ""
            )
        ).isFalse()
    }
}
