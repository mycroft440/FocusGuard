package com.focusguard.utils

import android.icu.text.IDNA as AndroidIdna
import android.text.InputType
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.focusguard.data.PredefinedWebsites
import java.net.IDN
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.URLDecoder
import java.text.Normalizer
import java.util.Locale

/**
 * Normaliza regras de domínio e extrai a URL exposta pela barra de endereço do
 * navegador. Esta é a camada de compatibilidade usada quando uma política
 * gerenciada do navegador não está disponível.
 *
 * A busca nunca trata texto arbitrário da página como URL: o nó precisa ter um
 * id/descrição de barra de endereço ou declarar input do tipo URI. Isso evita
 * bloquear uma página apenas porque um formulário ou notícia menciona um
 * domínio bloqueado.
 */
object WebsiteBlocker {

    private const val TAG = "WebsiteBlocker"
    private const val MAX_TREE_DEPTH = 12
    private const val MAX_TREE_NODES = 256
    private const val KEYWORD_RULE_PREFIX = "keyword:"
    private const val CATEGORY_RULE_PREFIX = "category:"
    private const val MIN_KEYWORD_LENGTH = 3
    private const val MAX_KEYWORD_LENGTH = 63

    private val strongAddressBarEntryNames = listOf(
        "url_bar",
        "url_bar_edit_text",
        "url_text",
        "location_bar_edit_text",
        "location_bar",
        "url_field",
        "url_edit_text",
        "omnibarTextInput",
        "omnibox_text",
        "mozac_browser_toolbar_url_view",
        "mozac_browser_toolbar_edit_url_view",
        "browser_toolbar_url_view",
        "address_bar"
    )

    private val weakAddressBarEntryNames = setOf(
        "search_box_text",
        "line_1"
    )

    private val addressBarDescriptions = setOf(
        "address and search bar",
        "endereço e barra de pesquisa",
        "barra de endereço e pesquisa",
        "search or type web address",
        "pesquisar ou digitar endereço web",
        "url bar",
        "barra de url",
        "address bar",
        "barra de endereços",
        "barra de endereço",
        "endereço da página"
    )

    private val nestedUrlPrefixes = listOf(
        "view-source:",
        "blob:",
        "filesystem:"
    )

    private val domainAliases = mapOf(
        "youtube.com" to setOf("youtu.be", "youtube-nocookie.com"),
        "twitter.com" to setOf("x.com", "t.co")
    )

    private val domainAppPackages = mapOf(
        "youtube.com" to setOf("com.google.android.youtube")
    )

    private val pornographyBlockingRules: Set<String> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        normalizeRules(
            PredefinedWebsites.ADULT_DOMAINS + PredefinedWebsites.PORNOGRAPHY_KEYWORDS
        )
    }

    private val uts46Idna: AndroidIdna? by lazy(LazyThreadSafetyMode.PUBLICATION) {
        runCatching {
            AndroidIdna.getUTS46Instance(
                AndroidIdna.USE_STD3_RULES or
                    AndroidIdna.CHECK_BIDI or
                    AndroidIdna.CHECK_CONTEXTJ or
                    AndroidIdna.NONTRANSITIONAL_TO_ASCII
            )
        }.getOrNull()
    }

    /** Mantido por compatibilidade; o matcher atual não conserva estado. */
    fun clearCache() = Unit

    fun isValidUrl(text: String): Boolean = extractDomain(text).isNotEmpty()

    /**
     * Normaliza uma regra informada pelo usuário. Endereços válidos são
     * armazenados como domínio; uma palavra isolada é armazenada com prefixo
     * interno para não ser confundida com um hostname.
     */
    fun normalizeRule(value: String): String {
        val sanitized = sanitizeText(value)
        if (sanitized.isEmpty()) return ""

        if (sanitized.equals(PredefinedWebsites.PORNOGRAPHY_RULE, ignoreCase = true)) {
            return PredefinedWebsites.PORNOGRAPHY_RULE
        }
        // Não aceita categorias arbitrárias digitadas pelo usuário.
        if (sanitized.startsWith(CATEGORY_RULE_PREFIX, ignoreCase = true)) return ""

        if (sanitized.startsWith(KEYWORD_RULE_PREFIX, ignoreCase = true)) {
            return normalizeKeyword(sanitized.substring(KEYWORD_RULE_PREFIX.length))
        }

        extractDomain(sanitized).takeIf(String::isNotEmpty)?.let { return it }
        return normalizeKeyword(sanitized)
    }

    fun isValidRule(value: String): Boolean = normalizeRule(value).isNotEmpty()

    fun isKeywordRule(rule: String): Boolean {
        return rule.startsWith(KEYWORD_RULE_PREFIX, ignoreCase = true)
    }

    fun isPornographyRule(rule: String): Boolean {
        return rule.equals(PredefinedWebsites.PORNOGRAPHY_RULE, ignoreCase = true)
    }

    fun containsPornographyRule(rules: Collection<String>): Boolean {
        return normalizeRules(rules).any(::isPornographyRule)
    }

    /** Formato amigável usado na UI sem expor o prefixo de persistência. */
    fun displayRule(rule: String): String {
        val normalized = normalizeRule(rule)
        if (isPornographyRule(normalized)) return PredefinedWebsites.PORNOGRAPHY_NAME
        return keywordValue(normalized)?.let { "*$it*" } ?: normalized
    }

    /**
     * Normaliza URL ou domínio para um host ASCII em minúsculas, sem `www.`,
     * credenciais, porta, ponto final, caminho, query ou fragmento.
     */
    fun extractDomain(url: String): String {
        var raw = sanitizeText(url)
        if (raw.isEmpty()) return ""

        while (true) {
            val prefix = nestedUrlPrefixes.firstOrNull {
                raw.startsWith(it, ignoreCase = true)
            } ?: break
            raw = raw.substring(prefix.length).trim()
        }

        if ('@' in raw && !SCHEME_REGEX.containsMatchIn(raw)) return ""

        if (raw.count { it == ':' } >= 2 &&
            !SCHEME_REGEX.containsMatchIn(raw) &&
            raw.none { it == '/' || it == '?' || it == '#' || it == '@' }
        ) {
            canonicalizeIpv6(raw.removeSurrounding("[", "]"))
                .takeIf(String::isNotEmpty)
                ?.let { return it }
        }

        SCHEME_COLON_REGEX.find(raw)?.let { match ->
            if (!SCHEME_REGEX.containsMatchIn(raw)) {
                val scheme = match.groupValues[1].lowercase(Locale.ROOT)
                val valueAfterColon = raw.substring(match.value.length)
                    .substringBefore('/')
                    .substringBefore('?')
                    .substringBefore('#')
                if ('.' in scheme && valueAfterColon.toIntOrNull() != null) return@let
                if (scheme != "http" && scheme != "https") return ""
                raw = raw.substring(match.value.length).trimStart('/', '\\')
            }
        }

        val candidate = if (SCHEME_REGEX.containsMatchIn(raw)) raw else "https://$raw"
        val uri = runCatching { URI(candidate) }.getOrNull()
        val host = uri?.host
            ?: authorityHost(uri?.rawAuthority)
            ?: fallbackHost(raw)

        return normalizeHost(host)
    }

    fun normalizeRules(rules: Collection<String>): Set<String> {
        return rules.asSequence()
            .map(::normalizeRule)
            .filter(String::isNotEmpty)
            .toCollection(linkedSetOf())
    }

    /** Mantido como alias para os chamadores de versões anteriores. */
    fun normalizeDomains(domains: Collection<String>): Set<String> = normalizeRules(domains)

    /**
     * Expande aliases e categorias para os domínios aceitos por URLBlocklist.
     * Palavras-chave continuam na camada de acessibilidade, pois a política do
     * Chromium não aceita curingas parciais no host.
     */
    fun expandDomainAliases(normalizedDomains: Collection<String>): Set<String> {
        val normalized = normalizeRules(normalizedDomains)
        val domains = linkedSetOf<String>()
        normalized.forEach { rule ->
            when {
                isPornographyRule(rule) -> domains.addAll(
                    normalizeRules(PredefinedWebsites.ADULT_DOMAINS)
                )
                !isKeywordRule(rule) -> domains += rule
            }
        }
        val expanded = linkedSetOf<String>()
        expanded.addAll(domains)
        domains.forEach { rule ->
            domainAliases[rule]?.let { aliases -> expanded.addAll(aliases) }
        }
        return expanded
    }

    /**
     * Produz os filtros enviados aos navegadores Chromium gerenciados. Além dos
     * domínios, a categoria de pornografia inclui consultas iniciadas pelas
     * palavras configuradas. O sufixo `*` é uma correspondência de prefixo de
     * token de query suportada oficialmente por URLBlocklist.
     *
     * A camada de acessibilidade continua sendo a regra principal para buscas:
     * ela também reconhece o termo em qualquer posição da consulta e atende os
     * navegadores que não aceitam políticas gerenciadas.
     */
    fun managedBrowserFiltersFor(normalizedRules: Collection<String>): Set<String> {
        val normalized = normalizeRules(normalizedRules)
        return linkedSetOf<String>().apply {
            addAll(expandDomainAliases(normalized))
            if (normalized.any(::isPornographyRule)) {
                addAll(GOOGLE_IMAGES_MANAGED_FILTERS)
                PredefinedWebsites.PORNOGRAPHY_KEYWORDS.forEach { keyword ->
                    queryCaseVariants(keyword).forEach { variant ->
                        add("*?q=$variant*")
                    }
                }
            }
        }
    }

    /**
     * Relaciona sites a apps que normalmente abrem seus links no Android.
     * Isso evita que um link bloqueado seja desviado para o app nativo.
     */
    fun appPackageDomainsFor(domains: Collection<String>): Map<String, String> {
        val result = linkedMapOf<String, String>()
        normalizeRules(domains)
            .filterNot { isKeywordRule(it) || isPornographyRule(it) }
            .forEach { rule ->
                val canonical = domainAliases.entries.firstOrNull { (domain, aliases) ->
                    rule == domain || rule.endsWith(".$domain") || aliases.any { alias ->
                        rule == alias || rule.endsWith(".$alias")
                    }
                }?.key ?: rule

                domainAppPackages[canonical].orEmpty().forEach { packageName ->
                    result.putIfAbsent(packageName, canonical)
                }
            }
        return result
    }

    fun isUrlBlocked(url: String, blockedDomains: Collection<String>): Boolean {
        return findMatchingRule(url, normalizeRules(blockedDomains)) != null
    }

    /**
     * Retorna todas as regras que cobrem o domínio, da mais específica para a
     * mais ampla. Isso permite que limites sobrepostos acumulem o mesmo uso
     * sem alterar a precedência usada para decidir qual regra exibir.
     */
    fun findMatchingRules(
        urlOrDomain: String,
        normalizedBlockedDomains: Set<String>
    ): Set<String> {
        if (normalizedBlockedDomains.isEmpty()) return emptySet()

        val directRules = normalizedBlockedDomains
            .filterNot(::isPornographyRule)
            .toSet()
        val matches = findDirectMatchingRules(urlOrDomain, directRules).toMutableSet()
        if (normalizedBlockedDomains.any(::isPornographyRule)) {
            val normalizedCandidate = normalizeRule(urlOrDomain)
            val pornographyMatched = isPornographyRule(normalizedCandidate) ||
                findDirectMatchingRules(urlOrDomain, pornographyBlockingRules).isNotEmpty() ||
                isPornographyGoogleSearchUrl(urlOrDomain) ||
                isGoogleImagesUrl(urlOrDomain)
            if (pornographyMatched) matches += PredefinedWebsites.PORNOGRAPHY_RULE
        }
        return matches
    }

    private fun findDirectMatchingRules(
        urlOrDomain: String,
        normalizedBlockedDomains: Set<String>
    ): Set<String> {
        if (normalizedBlockedDomains.isEmpty()) return emptySet()

        val matches = linkedSetOf<String>()
        val domain = extractDomain(urlOrDomain)
        if (domain.isEmpty()) {
            // Identificadores de uso persistidos e rótulos da UI podem ser a
            // própria regra, sem representar uma URL navegável.
            val directRule = normalizeRule(urlOrDomain)
            if (directRule in normalizedBlockedDomains) matches += directRule
            return matches
        }

        if (isIpAddress(domain)) {
            if (domain in normalizedBlockedDomains) matches += domain
            return matches
        }

        var candidate = domain
        while (true) {
            if (candidate in normalizedBlockedDomains) matches += candidate
            val separator = candidate.indexOf('.')
            if (separator < 0) break
            candidate = candidate.substring(separator + 1)
        }

        domainAliases.forEach { (canonical, aliases) ->
            if (canonical in normalizedBlockedDomains &&
                aliases.any { alias -> domain == alias || domain.endsWith(".$alias") }
            ) {
                matches += canonical
            }
        }

        normalizedBlockedDomains.asSequence()
            .mapNotNull { rule -> keywordValue(rule)?.let { keyword -> rule to keyword } }
            .filter { (_, keyword) -> domain.contains(keyword) }
            .sortedByDescending { (_, keyword) -> keyword.length }
            .forEach { (rule, _) -> matches += rule }

        return matches
    }

    /**
     * Retorna a regra normalizada responsável pelo bloqueio. O conjunto
     * recebido deve ter sido produzido por [normalizeRules]. A regra de
     * domínio mais específica vence; na ausência dela, a palavra-chave mais
     * longa que estiver contida no host é usada.
     */
    fun findMatchingRule(
        urlOrDomain: String,
        normalizedBlockedDomains: Set<String>
    ): String? = findMatchingRules(urlOrDomain, normalizedBlockedDomains).firstOrNull()

    /** Caminho rápido para eventos originados diretamente na barra de URL. */
    fun extractUrlFromEvent(
        event: AccessibilityEvent,
        browserPackageName: String
    ): String? {
        val source = event.source ?: return null
        return try {
            if (!isAddressBarNode(source, browserPackageName)) return null

            extractCandidateFromNode(source)
                ?: event.text.orEmpty().firstNotNullOfOrNull { value ->
                    extractUrlCandidate(value?.toString().orEmpty())
                }
        } catch (error: RuntimeException) {
            FocusGuardLogger.logError(TAG, "Falha ao ler URL do evento", error)
            null
        } finally {
            recycleSafely(source)
        }
    }

    /** Texto cru da barra, inclusive quando ainda é uma consulta sem URL. */
    fun extractAddressBarTextFromEvent(
        event: AccessibilityEvent,
        browserPackageName: String
    ): String? {
        val source = event.source ?: return null
        return try {
            if (!isAddressBarNode(source, browserPackageName)) return null
            sanitizeText(source.text?.toString().orEmpty()).takeIf(String::isNotEmpty)
                ?: event.text.orEmpty().firstNotNullOfOrNull { value ->
                    sanitizeText(value?.toString().orEmpty()).takeIf(String::isNotEmpty)
                }
                ?: sanitizeText(source.contentDescription?.toString().orEmpty())
                    .takeIf(String::isNotEmpty)
                ?: sanitizeText(source.hintText?.toString().orEmpty())
                    .takeIf(String::isNotEmpty)
        } catch (error: RuntimeException) {
            FocusGuardLogger.logError(TAG, "Falha ao ler texto da barra de endereço", error)
            null
        } finally {
            recycleSafely(source)
        }
    }

    /**
     * Lê somente um campo editável que originou o evento. O chamador ainda
     * precisa confirmar que a página atual é do Google antes de interpretar o
     * texto como consulta; assim conteúdo arbitrário da página não é varrido.
     */
    fun extractEditableTextFromEvent(event: AccessibilityEvent): String? {
        val source = event.source ?: return null
        return try {
            if (!source.isVisibleToUser || !source.isEditable) return null
            sanitizeText(source.text?.toString().orEmpty()).takeIf(String::isNotEmpty)
                ?: event.text.orEmpty().firstNotNullOfOrNull { value ->
                    sanitizeText(value?.toString().orEmpty()).takeIf(String::isNotEmpty)
                }
        } catch (error: RuntimeException) {
            FocusGuardLogger.logError(TAG, "Falha ao ler campo editável do navegador", error)
            null
        } finally {
            recycleSafely(source)
        }
    }

    /**
     * Procura a barra de endereço usando primeiro ids específicos do pacote e,
     * como fallback, uma única travessia limitada da árvore de acessibilidade.
     */
    fun extractUrlFromRoot(
        root: AccessibilityNodeInfo?,
        browserPackageName: String
    ): String? {
        if (root == null || browserPackageName.isBlank()) return null

        strongAddressBarEntryNames.forEach { entryName ->
            val fullId = "$browserPackageName:id/$entryName"
            val nodes = runCatching { root.findAccessibilityNodeInfosByViewId(fullId) }
                .getOrNull()
                .orEmpty()
            try {
                nodes.forEach { node ->
                    extractCandidateFromNode(node)?.let { return it }
                }
            } finally {
                nodes.forEach(::recycleSafely)
            }
        }

        val startedAt = System.currentTimeMillis()
        val result = findAddressBarValue(root, browserPackageName, 0, intArrayOf(0))
        val elapsed = System.currentTimeMillis() - startedAt
        if (elapsed > 50L) {
            FocusGuardLogger.log(TAG, "Busca da barra de URL demorou ${elapsed}ms")
        }
        return result
    }

    /** Fallback para texto ainda digitado quando o evento não veio da barra. */
    fun extractAddressBarTextFromRoot(
        root: AccessibilityNodeInfo?,
        browserPackageName: String
    ): String? {
        if (root == null || browserPackageName.isBlank()) return null

        strongAddressBarEntryNames.forEach { entryName ->
            val fullId = "$browserPackageName:id/$entryName"
            val nodes = runCatching { root.findAccessibilityNodeInfosByViewId(fullId) }
                .getOrNull()
                .orEmpty()
            try {
                nodes.forEach { node ->
                    extractTextFromNode(node)?.let { return it }
                }
            } finally {
                nodes.forEach(::recycleSafely)
            }
        }

        return findAddressBarText(root, browserPackageName, 0, intArrayOf(0))
    }

    /**
     * Retorna verdadeiro para uma consulta digitada na omnibox. URLs comuns
     * não são examinadas pelo caminho; uma URL só entra aqui quando é uma
     * pesquisa do Google, evitando falsos positivos como example.com/sex.
     */
    fun isPornographySearchInput(text: String): Boolean {
        val candidateUrl = extractUrlCandidate(text)
        return if (candidateUrl != null) {
            isPornographyGoogleSearchUrl(candidateUrl) || isGoogleImagesUrl(candidateUrl)
        } else {
            containsPornographySearchTerm(text)
        }
    }

    /** Detecta os termos da categoria em `q=` de qualquer domínio regional do Google. */
    fun isPornographyGoogleSearchUrl(url: String): Boolean {
        if (!isGoogleUrl(url)) return false
        return searchQueryValues(url).any(::containsPornographySearchTerm)
    }

    /**
     * No modo estrito, qualquer superfície de pesquisa visual do Google fica
     * indisponível enquanto a categoria Pornografia estiver ativa. Isso inclui
     * a página inicial, resultados antigos e atuais, busca reversa e Lens.
     */
    fun isGoogleImagesUrl(url: String): Boolean {
        if (!isGoogleUrl(url)) return false
        val domain = extractDomain(url)
        if (domain.startsWith("images.google.") || domain.startsWith("lens.google.")) {
            return true
        }

        val path = parseUriCandidate(url)?.path.orEmpty().lowercase(Locale.ROOT)
        if (GOOGLE_IMAGE_PATH_PREFIXES.any { prefix ->
                path == prefix || path.startsWith("$prefix/")
            }
        ) return true

        return urlQueryTokens(url).any { (key, value) ->
            (key == "tbm" && value.equals("isch", ignoreCase = true)) ||
                (key == "udm" && value == "2")
        }
    }

    /** Usado para limitar a leitura de campos de página ao Google verificado pela URL. */
    fun isGoogleUrl(url: String): Boolean {
        val domain = extractDomain(url)
        return domain.isNotEmpty() && GOOGLE_HOST_REGEX.matches(domain)
    }

    internal fun containsPornographySearchTerm(text: String): Boolean {
        val decoded = decodeSearchComponent(text)
        val normalized = Normalizer.normalize(decoded, Normalizer.Form.NFKD)
            .replace(COMBINING_MARKS_REGEX, "")
            .lowercase(Locale.ROOT)
        return PORNOGRAPHY_SEARCH_TERM_REGEX.containsMatchIn(normalized)
    }

    /**
     * Extrai uma URL de textos compostos usados em contentDescription, como
     * "Barra de endereços, example.com". Só deve ser chamado após o nó ter
     * sido identificado como barra do navegador.
     */
    internal fun extractUrlCandidate(text: String): String? {
        val sanitized = sanitizeText(text)
        if (sanitized.isEmpty()) return null

        val candidates = sequence {
            yield(sanitized)
            for (segment in sanitized.split(SEGMENT_SEPARATOR_REGEX)) yield(segment)
            for (segment in sanitized.split(WHITESPACE_REGEX)) yield(segment)
        }

        return candidates
            .map { it.trim().trim(*CANDIDATE_TRIM_CHARS) }
            .filter(String::isNotEmpty)
            .distinct()
            .firstOrNull(::isValidUrl)
    }

    private fun extractCandidateFromNode(node: AccessibilityNodeInfo): String? {
        return extractUrlCandidate(node.text?.toString().orEmpty())
            ?: extractUrlCandidate(node.contentDescription?.toString().orEmpty())
            ?: extractUrlCandidate(node.hintText?.toString().orEmpty())
    }

    private fun extractTextFromNode(node: AccessibilityNodeInfo): String? {
        return sequenceOf(node.text, node.contentDescription, node.hintText)
            .map { value -> sanitizeText(value?.toString().orEmpty()) }
            .firstOrNull(String::isNotEmpty)
    }

    private fun findAddressBarValue(
        node: AccessibilityNodeInfo?,
        browserPackageName: String,
        depth: Int,
        visitedNodes: IntArray
    ): String? {
        if (node == null || depth > MAX_TREE_DEPTH) return null
        visitedNodes[0] += 1
        if (visitedNodes[0] > MAX_TREE_NODES) return null

        return try {
            if (isAddressBarNode(node, browserPackageName)) {
                extractCandidateFromNode(node)?.let { return it }
            }

            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                try {
                    findAddressBarValue(
                        child,
                        browserPackageName,
                        depth + 1,
                        visitedNodes
                    )?.let { return it }
                } finally {
                    recycleSafely(child)
                }
            }
            null
        } catch (error: RuntimeException) {
            FocusGuardLogger.logError(TAG, "Falha ao percorrer árvore de acessibilidade", error)
            null
        }
    }

    private fun findAddressBarText(
        node: AccessibilityNodeInfo?,
        browserPackageName: String,
        depth: Int,
        visitedNodes: IntArray
    ): String? {
        if (node == null || depth > MAX_TREE_DEPTH) return null
        visitedNodes[0] += 1
        if (visitedNodes[0] > MAX_TREE_NODES) return null

        return try {
            if (isAddressBarNode(node, browserPackageName)) {
                extractTextFromNode(node)?.let { return it }
            }
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                try {
                    findAddressBarText(
                        child,
                        browserPackageName,
                        depth + 1,
                        visitedNodes
                    )?.let { return it }
                } finally {
                    recycleSafely(child)
                }
            }
            null
        } catch (error: RuntimeException) {
            FocusGuardLogger.logError(TAG, "Falha ao procurar texto da barra", error)
            null
        }
    }

    private fun isAddressBarNode(
        node: AccessibilityNodeInfo,
        browserPackageName: String
    ): Boolean {
        if (!node.isVisibleToUser) return false

        val viewId = node.viewIdResourceName.orEmpty()
        val resourcePackage = viewId.substringBefore(":id/", missingDelimiterValue = "")
        val entryName = viewId.substringAfter(":id/", missingDelimiterValue = "")
        val packageMatches = resourcePackage.isEmpty() || resourcePackage == browserPackageName

        if (packageMatches && entryName in strongAddressBarEntryNames) return true
        if (packageMatches && entryName in weakAddressBarEntryNames && node.isEditable) return true

        val description = node.contentDescription?.toString()
            ?.let(::sanitizeText)
            ?.lowercase(Locale.ROOT)
            .orEmpty()
        if (addressBarDescriptions.any { label ->
                description == label ||
                    description.startsWith("$label,") ||
                    description.startsWith("$label.") ||
                    description.startsWith("$label ")
            }
        ) return true

        val variation = node.inputType and InputType.TYPE_MASK_VARIATION
        val uriInput = variation == InputType.TYPE_TEXT_VARIATION_URI
        val hasBrowserResourceId = resourcePackage == browserPackageName
        val idSuggestsAddressBar = entryName.lowercase(Locale.ROOT).let { id ->
            id.contains("url") || id.contains("omnibox") ||
                id.contains("address") || id.contains("location_bar")
        }
        return node.isEditable && packageMatches &&
            (idSuggestsAddressBar || uriInput && hasBrowserResourceId)
    }

    private fun authorityHost(authority: String?): String? {
        if (authority.isNullOrBlank()) return null
        val withoutUserInfo = authority.substringAfterLast('@')
        return if (withoutUserInfo.startsWith('[')) {
            withoutUserInfo.substringAfter('[').substringBefore(']').takeIf(String::isNotBlank)
        } else {
            withoutUserInfo.substringBefore(':').takeIf(String::isNotBlank)
        }
    }

    private fun fallbackHost(raw: String): String {
        val authority = raw
            .replace(SCHEME_PREFIX_REGEX, "")
            .replace('\\', '/')
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .substringAfterLast('@')

        return if (authority.startsWith('[')) {
            authority.substringAfter('[').substringBefore(']')
        } else {
            authority.substringBefore(':')
        }
    }

    private fun normalizeHost(host: String): String {
        val prepared = decodePercentEncodedHost(sanitizeText(host))
            .replace('\u3002', '.')
            .replace('\uFF0E', '.')
            .replace('\uFF61', '.')
            .trim()
            .removeSurrounding("[", "]")
            .trimEnd('.')
            .lowercase(Locale.ROOT)
            .removePrefix("www.")
        if (prepared.isEmpty()) return ""

        if (prepared.contains(':')) {
            return canonicalizeIpv6(prepared)
        }

        val ascii = toAsciiDomain(prepared)
        if (ascii.isEmpty() || ascii.length > 253) return ""

        if (endsInIpv4Number(ascii)) {
            return canonicalizeIpv4(ascii)
        }

        if (!ascii.contains('.')) return ""
        val labels = ascii.split('.')
        if (labels.any { label ->
                label.isEmpty() || label.length > 63 ||
                    label.first() == '-' || label.last() == '-' ||
                    label.any { !it.isLetterOrDigit() && it != '-' }
            }
        ) return ""
        return ascii
    }

    private fun toAsciiDomain(host: String): String {
        val processor = uts46Idna
        if (processor != null) {
            return runCatching {
                val info = AndroidIdna.Info()
                val output = processor.nameToASCII(host, StringBuilder(), info)
                if (info.hasErrors()) "" else output.toString().lowercase(Locale.ROOT)
            }.getOrDefault("")
        }

        // Fallback para testes JVM sem a implementação ICU do Android.
        return runCatching {
            IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
        }.getOrDefault("")
    }

    private fun decodePercentEncodedHost(host: String): String {
        if ('%' !in host) return host
        return runCatching {
            // URLDecoder trata '+' como espaço; em host ele é literal e será
            // rejeitado depois pela validação STD3.
            URLDecoder.decode(host.replace("+", "%2B"), "UTF-8")
        }.getOrDefault(host)
    }

    private fun searchQueryValues(url: String): List<String> {
        return urlQueryTokens(url)
            .filter { (key, _) -> key in SEARCH_QUERY_KEYS }
            .map { (_, value) -> value }
    }

    private fun urlQueryTokens(url: String): List<Pair<String, String>> {
        val sanitized = sanitizeText(url)
        if (sanitized.isEmpty()) return emptyList()
        val uri = parseUriCandidate(sanitized)
        val querySections = buildList {
            uri?.rawQuery?.let(::add)
            uri?.rawFragment
                ?.takeIf { '=' in it }
                ?.let(::add)
            if (isEmpty() && '?' in sanitized) {
                add(sanitized.substringAfter('?').substringBefore('#'))
            }
        }
        return querySections.flatMap { query ->
            query.split('&').mapNotNull { token ->
                val rawKey = token.substringBefore('=', missingDelimiterValue = token)
                val key = decodeSearchComponent(rawKey).lowercase(Locale.ROOT)
                if (key.isEmpty() || '=' !in token) return@mapNotNull null
                key to decodeSearchComponent(token.substringAfter('='))
            }
        }
    }

    private fun parseUriCandidate(value: String): URI? {
        var sanitized = sanitizeText(value)
        while (true) {
            val prefix = nestedUrlPrefixes.firstOrNull {
                sanitized.startsWith(it, ignoreCase = true)
            } ?: break
            sanitized = sanitized.substring(prefix.length).trim()
        }
        val candidate = if (SCHEME_REGEX.containsMatchIn(sanitized)) {
            sanitized
        } else {
            "https://$sanitized"
        }
        return runCatching { URI(candidate) }.getOrNull()
    }

    private fun decodeSearchComponent(value: String): String {
        var decoded = sanitizeText(value)
        repeat(MAX_QUERY_DECODE_PASSES) {
            val next = runCatching { URLDecoder.decode(decoded, "UTF-8") }
                .getOrDefault(decoded)
            if (next == decoded) return decoded
            decoded = next
        }
        return decoded
    }

    private fun queryCaseVariants(keyword: String): Set<String> {
        val lower = keyword.lowercase(Locale.ROOT)
        return linkedSetOf(
            lower,
            lower.replaceFirstChar { first -> first.titlecase(Locale.ROOT) },
            lower.uppercase(Locale.ROOT)
        )
    }

    private fun isIpAddress(host: String): Boolean {
        return canonicalizeIpv4(host).isNotEmpty() || canonicalizeIpv6(host).isNotEmpty()
    }

    private fun canonicalizeIpv4(host: String): String {
        if (!endsInIpv4Number(host)) return ""
        val parts = host.split('.').toMutableList().apply {
            if (lastOrNull().isNullOrEmpty() && size > 1) removeAt(lastIndex)
        }
        if (parts.isEmpty() || parts.size > 4) return ""

        val numbers = mutableListOf<Long>()
        for (part in parts) {
            numbers += parseIpv4Number(part) ?: return ""
        }
        if (numbers.dropLast(1).any { it > 255L }) return ""

        val lastLimit = 1L shl (8 * (5 - numbers.size))
        val last = numbers.last()
        if (last >= lastLimit) return ""

        var address = last
        numbers.dropLast(1).forEachIndexed { index, number ->
            address += number shl (8 * (3 - index))
        }
        return (3 downTo 0).joinToString(".") { shift ->
            ((address shr (shift * 8)) and 0xFFL).toString()
        }
    }

    private fun endsInIpv4Number(host: String): Boolean {
        val last = host.trimEnd('.').substringAfterLast('.', missingDelimiterValue = host)
        if (last.isEmpty()) return false
        if (last.all(Char::isDigit)) return true
        return last.startsWith("0x", ignoreCase = true) &&
            last.drop(2).all { it.isHexDigit() }
    }

    private fun parseIpv4Number(part: String): Long? {
        if (part.isEmpty()) return null
        val (digits, radix) = when {
            part.startsWith("0x", ignoreCase = true) -> part.drop(2) to 16
            part.length >= 2 && part.startsWith('0') -> part.drop(1) to 8
            else -> part to 10
        }
        if (digits.isEmpty()) return 0L
        val valid = when (radix) {
            8 -> digits.all { it in '0'..'7' }
            10 -> digits.all(Char::isDigit)
            else -> digits.all { it.isHexDigit() }
        }
        return if (valid) digits.toLongOrNull(radix) else null
    }

    private fun canonicalizeIpv6(host: String): String {
        if (!host.contains(':') || host.contains('%')) return ""
        return runCatching {
            (InetAddress.getByName(host) as? Inet6Address)
                ?.hostAddress
                ?.substringBefore('%')
                ?.lowercase(Locale.ROOT)
                .orEmpty()
        }.getOrDefault("")
    }

    private fun sanitizeText(value: String): String {
        return value.replace(INVISIBLE_CHARACTER_REGEX, "").trim()
    }

    private fun normalizeKeyword(value: String): String {
        val candidate = sanitizeText(value).trim('*')
        if (candidate.length !in MIN_KEYWORD_LENGTH..MAX_KEYWORD_LENGTH) return ""

        val ascii = toAsciiDomain(candidate)
        if (ascii.length !in MIN_KEYWORD_LENGTH..MAX_KEYWORD_LENGTH ||
            !KEYWORD_REGEX.matches(ascii)
        ) return ""
        return "$KEYWORD_RULE_PREFIX$ascii"
    }

    private fun keywordValue(rule: String): String? {
        if (!isKeywordRule(rule)) return null
        return rule.substring(KEYWORD_RULE_PREFIX.length).takeIf(String::isNotEmpty)
    }

    private fun Char.isHexDigit(): Boolean {
        return isDigit() || lowercaseChar() in 'a'..'f'
    }

    @Suppress("DEPRECATION")
    private fun recycleSafely(node: AccessibilityNodeInfo) {
        runCatching { node.recycle() }
    }

    private val SCHEME_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")
    private val SCHEME_COLON_REGEX = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):")
    private val SCHEME_PREFIX_REGEX = Regex(
        "^[a-zA-Z][a-zA-Z0-9+.-]*://",
        RegexOption.IGNORE_CASE
    )
    private val INVISIBLE_CHARACTER_REGEX = Regex("[\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u206F\\uFEFF]")
    private val SEGMENT_SEPARATOR_REGEX = Regex("[,;|\\n\\t]")
    private val WHITESPACE_REGEX = Regex("\\s+")
    private val KEYWORD_REGEX = Regex("^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$")
    private const val MAX_QUERY_DECODE_PASSES = 3
    private val SEARCH_QUERY_KEYS = setOf("q", "query", "oq")
    private val GOOGLE_IMAGE_PATH_PREFIXES = setOf(
        "/imghp",
        "/imgres",
        "/advanced_image_search",
        "/searchbyimage",
        "/lens"
    )
    private val GOOGLE_IMAGES_MANAGED_FILTERS = listOf(
        "images.google.com",
        "lens.google.com",
        "*/imghp",
        "*/imgres",
        "*/advanced_image_search",
        "*/searchbyimage",
        "*/lens",
        "*?tbm=isch",
        "*?udm=2"
    )
    private val GOOGLE_HOST_REGEX = Regex(
        "^(?:[a-z0-9-]+\\.)*google\\.(?:[a-z]{2,}|(?:co|com|net|org)\\.[a-z]{2})$",
        RegexOption.IGNORE_CASE
    )
    private val COMBINING_MARKS_REGEX = Regex("\\p{M}+")
    private val PORNOGRAPHY_SEARCH_TERM_REGEX: Regex by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val alternatives = PredefinedWebsites.PORNOGRAPHY_KEYWORDS
            .sortedByDescending(String::length)
            .joinToString("|") { keyword ->
                keyword.lowercase(Locale.ROOT)
                    .map { character -> Regex.escape(character.toString()) }
                    .joinToString("[^\\p{L}\\p{N}]*")
            }
        Regex("(?<![\\p{L}\\p{N}])(?:$alternatives)")
    }
    private val CANDIDATE_TRIM_CHARS = charArrayOf(
        '"', '\'', '(', ')', '[', ']', '{', '}', '<', '>', ',', ';'
    )
}
