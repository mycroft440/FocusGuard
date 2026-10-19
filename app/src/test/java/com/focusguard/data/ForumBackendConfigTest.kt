package com.focusguard.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ForumBackendConfigTest {
    private val clientId = "123-abc.apps.googleusercontent.com"

    @Test
    fun `deployed Apps Script URL and web client ID turn the shared forum on`() {
        val config = ForumBackendConfig(
            endpointUrl = "https://script.google.com/macros/s/AKfycbx123/exec",
            googleWebClientId = clientId
        )
        assertThat(config.isConfigured).isTrue()
    }

    @Test
    fun `missing or wrong values keep the forum on this device`() {
        listOf(
            ForumBackendConfig("", clientId),
            ForumBackendConfig("https://script.google.com/macros/s/AKfycbx123/exec", ""),
            // /dev only answers to the script owner.
            ForumBackendConfig("https://script.google.com/macros/s/AKfycbx123/dev", clientId),
            ForumBackendConfig("http://script.google.com/macros/s/AKfycbx123/exec", clientId),
            ForumBackendConfig("https://example.com/macros/s/AKfycbx123/exec", clientId),
            ForumBackendConfig(
                "https://script.google.com/macros/s/AKfycbx123/exec",
                ".apps.googleusercontent.com"
            )
        ).forEach { config ->
            assertThat(config.isConfigured).isFalse()
        }
    }
}
