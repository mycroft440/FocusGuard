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
        assertThat(PredefinedWebsites.PORNOGRAPHY_KEYWORDS).containsAtLeast(
            "porn",
            "xxx",
            "xvideo",
            "sex"
        )
        assertThat(PredefinedWebsites.ADULT_DOMAINS).containsAtLeast(
            "xvideos.com",
            "xhamster.com",
            "rule34.xxx",
            "onlyfans.com"
        )
        assertThat(PredefinedWebsites.ADULT_DOMAINS).containsNoDuplicates()
    }

    @Test
    fun everyPornographyKeywordIsAStorableRule() {
        assertThat(PredefinedWebsites.PORNOGRAPHY_KEYWORDS).containsNoDuplicates()
        PredefinedWebsites.PORNOGRAPHY_KEYWORDS.forEach { keyword ->
            assertThat(WebsiteBlocker.normalizeRule(keyword)).isEqualTo("keyword:$keyword")
        }
    }

    @Test
    fun everyAdultDomainIsAlreadyNormalized() {
        PredefinedWebsites.ADULT_DOMAINS.forEach { domain ->
            assertThat(WebsiteBlocker.normalizeRule(domain)).isEqualTo(domain)
        }
    }

    @Test
    fun theSingularXvideoKeywordAlsoCoversTheOriginalSpelling() {
        val category = listOf(PredefinedWebsites.PORNOGRAPHY_RULE)

        // "xvideo" no singular é o que faz o plural e os espelhos caírem juntos.
        assertThat(WebsiteBlocker.isUrlBlocked("https://xvideos.com", category)).isTrue()
        assertThat(WebsiteBlocker.isUrlBlocked("https://xvideo2.example", category)).isTrue()
        assertThat(WebsiteBlocker.isUrlBlocked("https://br.xvideo.red/x", category)).isTrue()
    }

    @Test
    fun thePornographyCategoryCoversWholeAdultTopLevelDomains() {
        val category = listOf(PredefinedWebsites.PORNOGRAPHY_RULE)

        // O TLD inteiro cai porque a palavra aparece no host.
        assertThat(WebsiteBlocker.isUrlBlocked("https://qualquercoisa.xxx", category)).isTrue()
        assertThat(WebsiteBlocker.isUrlBlocked("https://site-novo.porn", category)).isTrue()
        assertThat(WebsiteBlocker.isUrlBlocked("https://site-novo.sex", category)).isTrue()
        assertThat(WebsiteBlocker.isUrlBlocked("https://noticias.example.com", category)).isFalse()
    }

    @Test
    fun thePornographyCategoryReachesMirrorsOfTheBigSites() {
        val category = listOf(PredefinedWebsites.PORNOGRAPHY_RULE)

        // Nome do site como palavra: o espelho que troca de TLD continua caindo.
        assertThat(WebsiteBlocker.isUrlBlocked("https://xhamster-mirror.example", category))
            .isTrue()
        assertThat(WebsiteBlocker.isUrlBlocked("https://onlyfans-free.example", category))
            .isTrue()
        assertThat(WebsiteBlocker.isUrlBlocked("https://nhentai.example", category)).isTrue()
    }

    @Test
    fun managedBrowserFiltersCarryTheAdultDomainsAndSearchTerms() {
        val filters = WebsiteBlocker.managedBrowserFiltersFor(
            listOf(PredefinedWebsites.PORNOGRAPHY_RULE)
        )

        // A política do Chromium não aceita curinga no host, então lá cada
        // domínio precisa estar listado; as palavras entram como busca.
        assertThat(filters).containsAtLeast("pornhub.com", "chaturbate.com", "nhentai.net")
        assertThat(filters).contains("*?q=porn*")
        assertThat(filters).contains("*?q=xvideo*")
        assertThat(filters).contains("images.google.com")
    }
}
