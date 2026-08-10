package com.focusguard.ui.compose.screens

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class ProfileAvatarAssetsTest {
    private val drawableDirectory = File("src/main/res/drawable-nodpi")
    private val expectedAssets = listOf(
        "avatar_focus_guardian.webp",
        "avatar_solar_inventor.webp",
        "avatar_forest_explorer.webp",
        "avatar_galactic_traveler.webp",
        "avatar_energy_runner.webp"
    )

    @Test
    fun `all five transparent character assets are bundled and optimized`() {
        expectedAssets.forEach { fileName ->
            val asset = File(drawableDirectory, fileName)
            assertThat(asset.isFile).isTrue()

            val bytes = asset.readBytes()
            val container = bytes.toString(Charsets.ISO_8859_1)

            assertThat(asset.length()).isAtLeast(5_000L)
            assertThat(asset.length()).isAtMost(200_000L)
            assertThat(bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII))
                .isEqualTo("RIFF")
            assertThat(bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII))
                .isEqualTo("WEBP")
            assertThat(container).contains("ALPH")
        }
    }

    @Test
    fun `creator Instagram destination is exact and uses the installed app first`() {
        assertThat(CREATOR_INSTAGRAM_PROFILE_URL)
            .isEqualTo("https://www.instagram.com/jose_gustavo55/")
        assertThat(INSTAGRAM_PACKAGE_NAME).isEqualTo("com.instagram.android")
    }
}
