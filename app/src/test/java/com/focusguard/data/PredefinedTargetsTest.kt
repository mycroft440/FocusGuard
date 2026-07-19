package com.focusguard.data

import com.google.common.truth.Truth.assertThat
import com.focusguard.utils.WebsiteBlocker
import org.junit.Test

class PredefinedTargetsTest {
    @Test
    fun appPickerPresetsContainOnlyAndroidPackages() {
        assertThat(PredefinedApps.PREVENTIVE_APPS).isNotEmpty()
        assertThat(PredefinedApps.PREVENTIVE_APPS.none { it.packageName.startsWith("site:") })
            .isTrue()
    }

    @Test
    fun websitePickerPresetsContainUniqueValidDomains() {
        val domains = PredefinedWebsites.POPULAR.map { it.domain }

        assertThat(domains).containsNoDuplicates()
        assertThat(domains.all { WebsiteBlocker.normalizeRule(it) == it }).isTrue()
        assertThat(PredefinedWebsites.POPULAR.all { it.iconDomain.isNotBlank() }).isTrue()
        assertThat(domains).containsAtLeast("youtube.com", "facebook.com", "reddit.com")
    }

    @Test
    fun adultFilterDomainsRemainAvailableOutsideTheAppPicker() {
        assertThat(PredefinedWebsites.ADULT_DOMAINS).hasSize(8)
        assertThat(PredefinedWebsites.ADULT_DOMAINS).contains("rule34.xxx")
    }
}
