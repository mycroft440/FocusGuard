package com.focusguard.ui.compose.screens

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LanguageOptionsTest {
    @Test
    fun `supported language list matches the twenty-language scope`() {
        val tags = SUPPORTED_APP_LANGUAGES.map { it.languageTag }
        assertThat(tags).containsExactly(
            "en", "zh-Hans", "hi", "es", "ar", "fr", "bn", "pt", "id", "ur",
            "ru", "de", "ja", "pcm", "arz", "mr", "vi", "te", "sw", "ha"
        ).inOrder()
        assertThat(tags.toSet()).hasSize(tags.size)
    }
}
