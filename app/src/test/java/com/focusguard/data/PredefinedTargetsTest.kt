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
    fun allPresetsExtendPopularWithTheAppCatalogueDomains() {
        val domains = PredefinedWebsites.ALL_PRESETS.map { it.domain }

        assertThat(domains).containsNoDuplicates()
        assertThat(domains.all { WebsiteBlocker.normalizeRule(it) == it }).isTrue()
        assertThat(PredefinedWebsites.ALL_PRESETS.all { it.name.isNotBlank() }).isTrue()
        // POPULAR abre a lista, na ordem, para os sites mais pedidos ficarem no topo.
        assertThat(domains.take(PredefinedWebsites.POPULAR.size))
            .containsExactlyElementsIn(PredefinedWebsites.POPULAR.map { it.domain })
            .inOrder()
        // E o catálogo de apps entra com os que faltavam: apostas, namoro, jogos.
        assertThat(domains).containsAtLeast(
            "bet365.com",
            "tinder.com",
            "netflix.com",
            "roblox.com"
        )
    }

    @Test
    fun pornographyPresetCombinesKeywordsAndFallbackDomains() {
        assertThat(WebsiteBlocker.normalizeRule(PredefinedWebsites.PORNOGRAPHY_RULE))
            .isEqualTo(PredefinedWebsites.PORNOGRAPHY_RULE)
        assertThat(PredefinedWebsites.PORNOGRAPHY_KEYWORDS)
            .containsExactly("porn", "xxx", "sex", "xvideos")
            .inOrder()
        assertThat(PredefinedWebsites.ADULT_DOMAINS).containsAtLeast(
            "xvideos.com",
            "xhamster.com",
            "rule34.xxx",
            "onlyfans.com"
        )
        assertThat(PredefinedWebsites.ADULT_DOMAINS).containsNoDuplicates()
    }
}
