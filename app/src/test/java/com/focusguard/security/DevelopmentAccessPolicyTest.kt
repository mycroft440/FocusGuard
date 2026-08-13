package com.focusguard.security

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DevelopmentAccessPolicyTest {

    @Test
    fun `debug password must match Dev00 exactly`() {
        assertThat(
            DevelopmentAccessPolicy.acceptsPassword(
                input = "Dev00",
                isDebugBuild = true,
                configuredPassword = "Dev00"
            )
        ).isTrue()

        listOf("dev00", "DEV00", "Dev0", "Dev000", "Dev00extra").forEach { input ->
            assertThat(
                DevelopmentAccessPolicy.acceptsPassword(
                    input = input,
                    isDebugBuild = true,
                    configuredPassword = "Dev00"
                )
            ).isFalse()
        }
    }

    @Test
    fun `release build never accepts the development password`() {
        assertThat(
            DevelopmentAccessPolicy.acceptsPassword(
                input = "Dev00",
                isDebugBuild = false,
                configuredPassword = "Dev00"
            )
        ).isFalse()
    }

    @Test
    fun `empty configured password disables the area`() {
        assertThat(
            DevelopmentAccessPolicy.isAvailable(
                isDebugBuild = true,
                configuredPassword = ""
            )
        ).isFalse()
    }
}
