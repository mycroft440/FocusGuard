package com.focusguard.data

/** Sites offered as shortcuts in the website picker, kept separate from app packages. */
object PredefinedWebsites {
    /**
     * Regra persistida para o atalho único de pornografia. Ela é expandida
     * somente durante a fiscalização, mantendo a categoria como um único item
     * nas sessões, limites e telas de configuração.
     */
    const val PORNOGRAPHY_RULE = "category:pornography"
    const val PORNOGRAPHY_NAME = "Pornografia"

    data class WebsiteInfo(
        val name: String,
        val domain: String,
        val iconDomain: String = domain
    )

    val POPULAR = listOf(
        WebsiteInfo("YouTube", "youtube.com"),
        WebsiteInfo("Instagram", "instagram.com"),
        WebsiteInfo("Facebook", "facebook.com"),
        WebsiteInfo("TikTok", "tiktok.com"),
        WebsiteInfo("X (Twitter)", "twitter.com", "x.com"),
        WebsiteInfo("Reddit", "reddit.com")
    )

    /**
     * Every site the app can offer as a shortcut: [POPULAR] first, then the
     * website of each preventive app that is not already on it.
     *
     * The app catalogue already knows the domain behind each distraction it
     * lists, so betting, dating and streaming sites are reachable without asking
     * the user to remember how each one is spelled. [POPULAR] stays a short list
     * for places too small to scroll — a dialog, a chip row.
     */
    val ALL_PRESETS: List<WebsiteInfo> by lazy {
        val seen = POPULAR.mapTo(linkedSetOf()) { it.domain }
        POPULAR + PredefinedApps.PREVENTIVE_APPS.mapNotNull { app ->
            app.domain
                ?.takeIf { it.isNotBlank() && seen.add(it) }
                ?.let { WebsiteInfo(app.appName, it) }
        }
    }

    /**
     * Palavras que bloqueiam qualquer domínio que as contenha.
     *
     * É a metade que alcança o que nenhuma lista alcança: domínio novo, espelho,
     * encurtador e o TLD inteiro — `.xxx`, `.porn` e `.sex` caem por conterem a
     * palavra no host. Por isso a lista mistura termos genéricos com o nome dos
     * maiores sites: o nome pega o espelho que trocou de TLD, e o termo pega o
     * site que ninguém catalogou ainda.
     *
     * `xvideo` no singular de propósito: como a comparação é por trecho do
     * domínio, ele cobre `xvideos` e também `xvideo2`, `xvideo.red` e afins.
     *
     * O preço de uma palavra curta é o falso positivo — `sex` derruba
     * `essex.ac.uk` — e ele é aceito de olho aberto: este é um filtro que quem
     * ligou quer largo. Termos com uso legítimo comum ficam de fora por isso;
     * `cam` e `adult`, por exemplo, pegariam câmera e educação de adultos.
     */
    val PORNOGRAPHY_KEYWORDS = listOf(
        // Termos genéricos.
        "porn",
        "xxx",
        "sex",
        "hentai",
        "nude",
        "erotic",
        "fetish",
        "milf",
        "bdsm",
        "boobs",
        "camgirl",
        "putaria",
        // Nomes que só existem no contexto adulto, para alcançar espelhos.
        "xvideo",
        "xnxx",
        "xhamster",
        "redtube",
        "youjizz",
        "spankbang",
        "brazzers",
        "onlyfans",
        "chaturbate",
        "stripchat",
        "bongacams",
        "fapello",
        "rule34",
        "nhentai"
    )

    /**
     * Fallback local para domínios adultos conhecidos. O filtro DNS familiar
     * do Device Owner continua sendo a fonte ampla e atualizada por categoria;
     * esta lista mantém cobertura quando DNS gerenciado não está disponível.
     *
     * Também é a metade que chega ao Chrome gerenciado: a política
     * `URLBlocklist` do Chromium não aceita curinga no meio do host, então lá
     * uma palavra não vale — só domínio. Por isso a lista inclui nomes que
     * [PORNOGRAPHY_KEYWORDS] já cobriria na camada de acessibilidade: os dois
     * caminhos precisam de cobertura própria.
     *
     * Vale para o domínio e para todos os seus subdomínios, então basta o
     * registrável.
     */
    val ADULT_DOMAINS = listOf(
        // Tubes
        "pornhub.com",
        "xvideos.com",
        "xnxx.com",
        "redtube.com",
        "spankbang.com",
        "eporner.com",
        "xhamster.com",
        "youporn.com",
        "tube8.com",
        "beeg.com",
        "tnaflix.com",
        "motherless.com",
        "hclips.com",
        "drtuber.com",
        "pornmd.com",
        "hqporner.com",
        "fuq.com",
        "4tube.com",
        "porntrex.com",
        "pornone.com",
        "thumbzilla.com",
        "keezmovies.com",
        "nuvid.com",
        "empflix.com",
        "sunporno.com",
        "youjizz.com",
        "txxx.com",
        "upornia.com",
        "hdzog.com",
        "vjav.com",
        "porngo.com",
        "pornhd.com",
        "porn300.com",
        "gotporn.com",
        "analdin.com",
        "faphouse.com",
        "sexvid.xxx",
        "pornoxo.com",
        "tubegalore.com",
        "porntube.com",
        "yourporn.sexy",
        "sxyprn.com",
        "netfapx.com",
        "watchmdh.to",
        "pornolab.net",
        "porn.com",
        "sex.com",

        // Câmeras ao vivo
        "chaturbate.com",
        "stripchat.com",
        "bongacams.com",
        "livejasmin.com",
        "cam4.com",
        "myfreecams.com",
        "camsoda.com",
        "flirt4free.com",
        "streamate.com",
        "jerkmate.com",
        "imlive.com",
        "xcams.com",
        "cherry.tv",

        // Conteúdo por assinatura e vazamentos
        "onlyfans.com",
        "fansly.com",
        "manyvids.com",
        "fapello.com",
        "clips4sale.com",
        "iwantclips.com",
        "fancentro.com",
        "loyalfans.com",
        "justfor.fans",
        "coomer.su",
        "kemono.su",
        "thothub.to",
        "leakedzone.com",
        "erome.com",
        "imagefap.com",
        "scrolller.com",

        // Hentai, anime e desenho
        "rule34.xxx",
        "nhentai.net",
        "hanime.tv",
        "hentaihaven.xxx",
        "e-hentai.org",
        "exhentai.org",
        "hitomi.la",
        "hentaifox.com",
        "hentai2read.com",
        "simply-hentai.com",
        "multporn.net",
        "luscious.net",
        "gelbooru.com",
        "donmai.us",
        "sankakucomplex.com",
        "r34.app",

        // JAV e asiáticos
        "missav.com",
        "javguru.com",
        "supjav.com",
        "javmost.com",
        "javhd.com",
        "jable.tv",
        "avgle.com",

        // Texto, jogos e fóruns adultos
        "literotica.com",
        "asstr.org",
        "f95zone.to",
        "adultgamesworld.com",

        // Encontros e acompanhantes
        "adultfriendfinder.com",
        "ashleymadison.com",
        "skokka.com",
        "fatalmodel.com",

        // Brasil
        "sexlog.com",
        "privacy.com.br",
        "brasileirinhas.com.br",
        "xvideosbrasil.blog"
    )
}
