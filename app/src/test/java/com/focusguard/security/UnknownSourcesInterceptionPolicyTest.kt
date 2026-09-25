package com.focusguard.security

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UnknownSourcesInterceptionPolicyTest {

    @Test
    fun `AOSP external sources activities are protected`() {
        assertThat(
            UnknownSourcesInterceptionPolicy.shouldProtect(
                packageName = "com.android.settings",
                className = "com.android.settings.Settings$ManageExternalSourcesActivity",
                values = emptyList()
            )
        ).isTrue()
        assertThat(
            UnknownSourcesInterceptionPolicy.shouldProtect(
                packageName = "com.android.settings",
                className = "com.android.settings.Settings$ManageAppExternalSourcesActivity",
                values = emptyList()
            )
        ).isTrue()
        assertThat(
            UnknownSourcesInterceptionPolicy.shouldProtect(
                packageName = "com.android.settings",
                className = "com.android.settings.applications.appinfo.ExternalSourcesDetails",
                values = emptyList()
            )
        ).isTrue()
    }

    @Test
    fun `localized allow from source text is protected`() {
        assertThat(
            UnknownSourcesInterceptionPolicy.textTargetsUnknownSources(
                listOf("Permitir desta fonte")
            )
        ).isTrue()
        assertThat(
            UnknownSourcesInterceptionPolicy.textTargetsUnknownSources(
                listOf("Allow from this source")
            )
        ).isTrue()
        assertThat(
            UnknownSourcesInterceptionPolicy.textTargetsUnknownSources(
                listOf("Permitir desde esta fuente")
            )
        ).isTrue()
    }

    @Test
    fun `ordinary settings surface is not protected`() {
        assertThat(
            UnknownSourcesInterceptionPolicy.shouldProtect(
                packageName = "com.android.settings",
                className = "com.android.settings.Settings$ManageApplicationsActivity",
                values = listOf("Aplicativos")
            )
        ).isFalse()
    }

    @Test
    fun `package installer is outside this protection surface`() {
        assertThat(
            UnknownSourcesInterceptionPolicy.shouldProtect(
                packageName = "com.google.android.packageinstaller",
                className = "com.android.packageinstaller.InstallStart",
                values = listOf("Install unknown apps")
            )
        ).isFalse()
    }
}
