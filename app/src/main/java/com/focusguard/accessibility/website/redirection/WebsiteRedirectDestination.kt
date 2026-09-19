package com.focusguard.accessibility.website.redirection

import com.focusguard.utils.WebsiteBlocker
import java.net.URI
import java.util.Locale

/**
 * Immutable description of the safe destination used after a website block.
 *
 * The accessibility service is deliberately not the owner of the target URL.
 * Switching away from Google must happen here (or in a future persisted selector),
 * while the same-tab redirection engine only consumes this contract.
 *
 * This class intentionally uses only JVM URL primitives so destination policy can
 * be tested without an Android runtime and remains independent from UI/platform code.
 */
internal data class WebsiteRedirectDestination(
    val url: String,
    val acceptedRootHosts: Set<String>,
    val acceptedRootQueryParameters: Set<String> = emptySet()
) {
    init {
        require(acceptedRootHosts.isNotEmpty()) { "At least one destination host is required" }
        val configured = runCatching { URI(url) }.getOrNull()
            ?: throw IllegalArgumentException("Website redirect destination must be a valid URI")
        val configuredHost = configured.host?.lowercase(Locale.US)?.removePrefix("www.")
            ?: throw IllegalArgumentException("Website redirect destination must have a host")
        require(configured.scheme.equals("https", ignoreCase = true)) {
            "Website redirect destination must use HTTPS"
        }
        require(configured.userInfo == null) { "Website redirect destination cannot contain user info" }
        require(configured.port == -1 || configured.port == 443) {
            "Website redirect destination must use the default HTTPS port"
        }
        require(configuredHost in acceptedRootHosts.map { it.lowercase(Locale.US).removePrefix("www.") }) {
            "Configured destination host must be accepted by its validation policy"
        }
        require(configured.rawFragment.isNullOrEmpty()) {
            "Website redirect destination cannot contain a fragment"
        }
    }

    fun matchesSurface(urlOrAddress: String?): Boolean {
        val raw = urlOrAddress?.trim()?.takeIf(String::isNotEmpty) ?: return false
        val candidate = WebsiteBlocker.extractUrlCandidate(raw) ?: raw
        val withScheme = if ("://" in candidate) candidate else "https://$candidate"
        val uri = runCatching { URI(withScheme) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.userInfo != null ||
            (uri.port != -1 && uri.port != 443)
        ) return false
        val host = uri.host?.lowercase(Locale.US)?.removePrefix("www.") ?: return false
        val queryParameterNames = uri.rawQuery
            ?.split('&')
            ?.asSequence()
            ?.filter(String::isNotBlank)
            ?.map { parameter -> parameter.substringBefore('=') }
            ?.toSet()
            .orEmpty()
        return host in acceptedRootHosts &&
            (uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") &&
            queryParameterNames.all(acceptedRootQueryParameters::contains) &&
            uri.rawFragment.isNullOrEmpty()
    }

    companion object {
        /** Exact hosts published by Google's supported-domains endpoint. */
        private val GOOGLE_ROOT_HOSTS = """
            google.com google.ad google.ae google.com.af google.com.ag google.al google.am
            google.co.ao google.com.ar google.as google.at google.com.au google.az google.ba
            google.com.bd google.be google.bf google.bg google.com.bh google.bi google.bj
            google.com.bn google.com.bo google.com.br google.bs google.bt google.co.bw google.by
            google.com.bz google.ca google.cd google.cf google.cg google.ch google.ci google.co.ck
            google.cl google.cm google.cn google.com.co google.co.cr google.com.cu google.cv
            google.com.cy google.cz google.de google.dj google.dk google.dm google.com.do google.dz
            google.com.ec google.ee google.com.eg google.es google.com.et google.fi google.com.fj
            google.fm google.fr google.ga google.ge google.gg google.com.gh google.com.gi google.gl
            google.gm google.gr google.com.gt google.gy google.com.hk google.hn google.hr google.ht
            google.hu google.co.id google.ie google.co.il google.im google.co.in google.iq google.is
            google.it google.je google.com.jm google.jo google.co.jp google.co.ke google.com.kh
            google.ki google.kg google.co.kr google.com.kw google.kz google.la google.com.lb google.li
            google.lk google.co.ls google.lt google.lu google.lv google.com.ly google.co.ma google.md
            google.me google.mg google.mk google.ml google.com.mm google.mn google.com.mt google.mu
            google.mv google.mw google.com.mx google.com.my google.co.mz google.com.na google.com.ng
            google.com.ni google.ne google.nl google.no google.com.np google.nr google.nu google.co.nz
            google.com.om google.com.pa google.com.pe google.com.pg google.com.ph google.com.pk
            google.pl google.pn google.com.pr google.ps google.pt google.com.py google.com.qa google.ro
            google.ru google.rw google.com.sa google.com.sb google.sc google.se google.com.sg google.sh
            google.si google.sk google.com.sl google.sn google.so google.sm google.sr google.st
            google.com.sv google.td google.tg google.co.th google.com.tj google.tl google.tm google.tn
            google.to google.com.tr google.tt google.com.tw google.co.tz google.com.ua google.co.ug
            google.co.uk google.com.uy google.co.uz google.com.vc google.co.ve google.co.vi google.com.vn
            google.vu google.ws google.rs google.co.za google.co.zm google.co.zw google.cat
        """.trimIndent().split(Regex("\\s+")).toSet()

        /** Initial FocusGuard destination. Future selection belongs in this layer. */
        val GOOGLE = WebsiteRedirectDestination(
            url = "https://www.google.com",
            acceptedRootHosts = GOOGLE_ROOT_HOSTS,
            acceptedRootQueryParameters = setOf("gl", "gws_rd", "hl")
        )

        /** Single source of truth consumed by the redirection pipeline. */
        val current: WebsiteRedirectDestination
            get() = GOOGLE
    }
}
