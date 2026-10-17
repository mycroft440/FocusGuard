package com.focusguard.ui.compose.screens

import com.google.common.truth.Truth.assertThat
import java.util.Locale
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

    @Test
    fun `system default is the selected option when the app has no language of its own`() {
        assertThat(selectedAppLanguageTag(null)).isEqualTo("")
    }

    @Test
    fun `chosen language is marked by its exact tag, script included`() {
        assertThat(selectedAppLanguageTag(Locale.forLanguageTag("zh-Hans"))).isEqualTo("zh-Hans")
        assertThat(selectedAppLanguageTag(Locale.forLanguageTag("pcm"))).isEqualTo("pcm")
        assertThat(selectedAppLanguageTag(Locale.forLanguageTag("pt"))).isEqualTo("pt")
    }

    @Test
    fun `regional variant set outside the app falls back to its language`() {
        assertThat(selectedAppLanguageTag(Locale.forLanguageTag("pt-BR"))).isEqualTo("pt")
        assertThat(selectedAppLanguageTag(Locale.forLanguageTag("it"))).isNull()
    }
}
