package com.focusguard.utils

import android.icu.text.IDNA as AndroidIdna
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.focusguard.accessibility.website.compatibility.BrowserActivationMethod
import com.focusguard.accessibility.website.compatibility.BrowserCompatibilityStore
import com.focusguard.accessibility.website.compatibility.BrowserIdentificationMethod
import com.focusguard.accessibility.website.compatibility.BrowserSubmitMethod
import com.focusguard.accessibility.website.compatibility.BrowserWriteMethod
import com.focusguard.accessibility.website.redirection.AddressBarRedirectionActions
import com.focusguard.accessibility.website.redirection.ClipboardPasteFallback
import com.focusguard.data.PredefinedApps
import com.focusguard.data.PredefinedWebsites
import com.focusguard.security.PasswordTargetAccessGrant
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
    // Browser toolbars can be nested below a virtualized WebView/GeckoView.
    // Keep the walk bounded, but large enough to reach browser-owned chrome after
    // entering those containers without turning arbitrary page text into URL evidence.
    private const val MAX_TREE_DEPTH = 24
    private const val MAX_TREE_NODES = 512
    private const val KEYWORD_RULE_PREFIX = "keyword:"
    private const val CATEGORY_RULE_PREFIX = "category:"
    private const val MIN_KEYWORD_LENGTH = 3
    private const val MAX_KEYWORD_LENGTH = 63

    private val strongAddressBarEntryNames =
        BrowserUiCapabilityPolicy.strongAddressBarEntryNames

    internal enum class AddressBarActionStatus {
        ACCEPTED,
        NOT_FOUND,
        AMBIGUOUS,
        REJECTED
    }

    internal data class AddressBarActionResult(
        val status: AddressBarActionStatus,
        val selectedViewId: String? = null
    ) {
        val accepted: Boolean
            get() = status == AddressBarActionStatus.ACCEPTED
    }

    private val nestedUrlPrefixes = listOf(
        "view-source:",
        "blob:",
        "filesystem:"
    )

    private val domainAliases = mapOf(
        "youtube.com" to setOf("youtu.be", "youtube-nocookie.com"),
        "twitter.com" to setOf("x.com", "t.co"),
        "instagram.com" to setOf("instagr.am"),
        "facebook.com" to setOf("fb.com", "fb.watch"),
        "reddit.com" to setOf("redd.it"),
        "pinterest.com" to setOf("pin.it"),
        "telegram.org" to setOf("t.me", "telegram.me"),
        "discord.com" to setOf("discord.gg", "discordapp.com"),
        "spotify.com" to setOf("spotify.link"),
        "snapchat.com" to setOf("snap.com")
    )

    private val domainAppPackages: Map<String, Set<String>> by lazy(
        LazyThreadSafetyMode.PUBLICATION
    ) {
        PredefinedApps.PREVENTIVE_APPS
            .mapNotNull { app ->
                app.domain?.let(::normalizeRule)
                    ?.takeIf(String::isNotEmpty)
                    ?.let { domain -> domain to app.packageName }
            }
            .groupBy(keySelector = { it.first }, valueTransform = { it.second })
            .mapValues { (_, packages) -> packages.toSet() }
    }

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
            if (isPornographyRule(rule)) {
                domains.addAll(normalizeRules(PredefinedWebsites.ADULT_DOMAINS))
            } else if (!isKeywordRule(rule)) {
                domains.add(rule)
            }
        }
        val expanded = linkedSetOf<String>()
        expanded.addAll(domains)
        domains.forEach { rule ->
            val canonical = canonicalDomainFor(rule)
            if (canonical != rule) expanded += canonical
            domainAliases[canonical]?.let { aliases -> expanded.addAll(aliases) }
        }
        return expanded
    }

    /**
     * Produz filtros de navegador gerenciado a partir das regras que estão
     * efetivamente fechadas agora. Uma concessão PASSWORD remove somente sua
     * regra durante a visita autenticada; a regra persistida permanece intacta.
     */
    fun managedBrowserFiltersFor(normalizedRules: Collection<String>): Set<String> {
        val normalized = normalizeRules(normalizedRules)
            .filterNot(PasswordTargetAccessGrant::isWebsiteRuleGranted)
            .toSet()
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
     * Uma regra temporariamente autenticada também deixa de redirecionar seu app
     * nativo até a visita terminar.
     */
    fun appPackageDomainsFor(domains: Collection<String>): Map<String, String> {
        val result = linkedMapOf<String, String>()
        normalizeRules(domains)
            .filterNot(PasswordTargetAccessGrant::isWebsiteRuleGranted)
            .filterNot { isKeywordRule(it) || isPornographyRule(it) }
            .forEach { rule ->
                val canonical = canonicalDomainFor(rule)

                domainAppPackages[canonical].orEmpty().forEach { packageName ->
                    result.putIfAbsent(packageName, canonical)
                }
            }
        return result
    }

    /** Adds the web surface of every predefined native app selected for blocking. */
    fun domainRulesForAppPackages(packageNames: Collection<String>): Set<String> {
        val selected = packageNames.filter(String::isNotBlank).toSet()
        if (selected.isEmpty()) return emptySet()

        return PredefinedApps.PREVENTIVE_APPS.asSequence()
            .filter { it.packageName in selected }
            .mapNotNull { it.domain }
            .map(::normalizeRule)
            .filter(String::isNotEmpty)
            .toCollection(linkedSetOf())
    }

    fun isUrlBlocked(url: String, blockedDomains: Collection<String>): Boolean {
        return findMatchingRule(url, normalizeRules(blockedDomains)) != null
    }

    /**
     * Retorna todas as regras que cobrem o domínio, da mais específica para a
     * mais ampla. Antes de decidir, uma concessão PASSWORD observa a URL atual:
     * permanecer no mesmo alvo mantém a visita aberta; navegar para fora revoga
     * a concessão e restaura a proteção imediatamente.
     */
    fun findMatchingRules(
        urlOrDomain: String,
        normalizedBlockedDomains: Set<String>
    ): Set<String> {
        if (normalizedBlockedDomains.isEmpty()) return emptySet()

        PasswordTargetAccessGrant.onWebsiteCandidateObserved(
            urlOrDomain = urlOrDomain,
            configuredRules = normalizedBlockedDomains
        )
        val effectiveBlockedDomains = normalizedBlockedDomains
            .filterNot(PasswordTargetAccessGrant::isWebsiteRuleGranted)
            .toSet()
        if (effectiveBlockedDomains.isEmpty()) return emptySet()

        val directRules = effectiveBlockedDomains
            .filterNot(::isPornographyRule)
            .toSet()
        val matches = findDirectMatchingRules(urlOrDomain, directRules).toMutableSet()
        if (effectiveBlockedDomains.any(::isPornographyRule)) {
            val normalizedCandidate = normalizeRule(urlOrDomain)
            val pornographyMatched = isPornographyRule(normalizedCandidate) ||
                findDirectMatchingRules(urlOrDomain, pornographyBlockingRules).isNotEmpty() ||
                isPornographySearchUrl(urlOrDomain) ||
                isGoogleImagesUrl(urlOrDomain)
            if (pornographyMatched) matches += PredefinedWebsites.PORNOGRAPHY_RULE
        }
        return matches
    }

    /**
     * Pure matcher used by the grant lifecycle itself. It deliberately ignores
     * temporary grants to avoid recursion while deciding whether navigation left
     * the authenticated website.
     */
    internal fun matchesRuleIgnoringGrants(
        urlOrDomain: String,
        normalizedRule: String
    ): Boolean {
        val rule = normalizeRule(normalizedRule)
        if (rule.isBlank()) return false
        if (isPornographyRule(rule)) {
            val normalizedCandidate = normalizeRule(urlOrDomain)
            return isPornographyRule(normalizedCandidate) ||
                findDirectMatchingRules(urlOrDomain, pornographyBlockingRules).isNotEmpty() ||
                isPornographySearchUrl(urlOrDomain) ||
                isGoogleImagesUrl(urlOrDomain)
        }
        return findDirectMatchingRules(urlOrDomain, setOf(rule)).isNotEmpty()
    }

    /**
     * Matches configured rules without applying a temporary PASSWORD visit grant.
     *
     * Blocking decisions must use [findMatchingRules], which deliberately honors
     * an authenticated visit. Usage accounting and hierarchy ownership are
     * different: they must keep seeing the rule while PASSWORD is temporarily
     * released so the daily allowance can continue advancing underneath it.
     */
    fun findMatchingRulesIgnoringGrants(
        urlOrDomain: String,
        configuredRules: Collection<String>
    ): Set<String> {
        val normalizedRules = normalizeRules(configuredRules)
        if (normalizedRules.isEmpty()) return emptySet()
        return normalizedRules.filterTo(linkedSetOf()) { rule ->
            matchesRuleIgnoringGrants(urlOrDomain, rule)
        }
    }

    private fun findDirectMatchingRules(
        urlOrDomain: String,
        normalizedBlockedDomains: Set<String>
    ): Set<String> {
        if (normalizedBlockedDomains.isEmpty()) return emptySet()

        val matches = linkedSetOf<String>()
        val domain = extractDomain(urlOrDomain)
        if (domain.isEmpty()) {
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
            val family = aliases + canonical
            val candidateIsInFamily = family.any { member ->
                domain == member || domain.endsWith(".$member")
            }
            if (candidateIsInFamily) {
                normalizedBlockedDomains.filterTo(matches) { configuredRule ->
                    configuredRule in family
                }
            }
        }

        normalizedBlockedDomains.asSequence()
            .mapNotNull { rule -> keywordValue(rule)?.let { keyword -> rule to keyword } }
            .filter { (_, keyword) -> domain.contains(keyword) }
            .sortedByDescending { (_, keyword) -> keyword.length }
            .forEach { (rule, _) -> matches += rule }

        return matches
    }

    private fun canonicalDomainFor(rule: String): String {
        return domainAliases.entries.firstOrNull { (domain, aliases) ->
            rule == domain || rule.endsWith(".$domain") || aliases.any { alias ->
                rule == alias || rule.endsWith(".$alias")
            }
        }?.key ?: rule
    }

    fun findMatchingRule(
        urlOrDomain: String,
        normalizedBlockedDomains: Set<String>
    ): String? = findMatchingRules(urlOrDomain, normalizedBlockedDomains).firstOrNull()

    /** Caminho rápido para eventos originados diretamente na barra de URL. */
    fun extractUrlFromEvent(
        event: AccessibilityEvent,
        browserPackageName: String,
        httpsHandlerRecognized: Boolean = false
    ): String? {
        val source = event.source ?: return null
        return try {
            if (!isAddressBarNode(
                    source,
                    browserPackageName,
                    event.windowId,
                    httpsHandlerRecognized
                )
            ) return null

            val candidate = extractCandidateFromNode(source)
                ?: event.text.orEmpty().firstNotNullOfOrNull { value ->
                    extractUrlCandidate(value?.toString().orEmpty())
                }
            if (candidate != null) {
                BrowserCompatibilityStore.recordIdentificationSuccess(
                    packageName = browserPackageName,
                    viewIdResourceName = source.viewIdResourceName,
                    method = BrowserIdentificationMethod.EVENT_SOURCE,
                    observedValue = candidate
                )
            }
            candidate
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
        browserPackageName: String,
        httpsHandlerRecognized: Boolean = false
    ): String? {
        val source = event.source ?: return null
        return try {
            if (!isAddressBarNode(
                    source,
                    browserPackageName,
                    event.windowId,
                    httpsHandlerRecognized
                )
            ) return null
            val text = sanitizeText(source.text?.toString().orEmpty()).takeIf(String::isNotEmpty)
                ?: event.text.orEmpty().firstNotNullOfOrNull { value ->
                    sanitizeText(value?.toString().orEmpty()).takeIf(String::isNotEmpty)
                }
                ?: sanitizeText(source.contentDescription?.toString().orEmpty())
                    .takeIf(String::isNotEmpty)

            if (text != null) {
                BrowserCompatibilityStore.recordIdentificationSuccess(
                    packageName = browserPackageName,
                    viewIdResourceName = source.viewIdResourceName,
                    method = BrowserIdentificationMethod.EVENT_SOURCE,
                    observedValue = text
                )
            }
            text
        } catch (error: RuntimeException) {
            FocusGuardLogger.logError(TAG, "Falha ao ler texto da barra de endereço", error)
            null
        } finally {
            recycleSafely(source)
        }
    }

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

    fun extractUrlFromRoot(
        root: AccessibilityNodeInfo?,
        browserPackageName: String,
        httpsHandlerRecognized: Boolean = false
    ): String? {
        if (root == null || browserPackageName.isBlank()) return null
        return inspectAddressBarRoot(root, browserPackageName, httpsHandlerRecognized).url
    }

    fun hasAddressBarNode(
        root: AccessibilityNodeInfo?,
        browserPackageName: String,
        httpsHandlerRecognized: Boolean = false
    ): Boolean {
        if (root == null || browserPackageName.isBlank()) return false
        val inspection = inspectAddressBarRoot(root, browserPackageName, httpsHandlerRecognized)
        if (inspection.isCurrent() && !inspection.addressBarObservable &&
            inspection.surface == BrowserSurfaceInspector.Surface.WEB_CONTENT
        ) {
            BrowserCompatibilityStore.recordUnobservableFailure(browserPackageName)
        }
        return inspection.addressBarObservable
    }

    fun extractAddressBarTextFromRoot(
        root: AccessibilityNodeInfo?,
        browserPackageName: String,
        httpsHandlerRecognized: Boolean = false
    ): String? {
        if (root == null || browserPackageName.isBlank()) return null
        return inspectAddressBarRoot(root, browserPackageName, httpsHandlerRecognized).addressText
    }

    private fun inspectAddressBarRoot(
        root: AccessibilityNodeInfo,
        browserPackageName: String,
        httpsHandlerRecognized: Boolean
    ): BrowserInspectionSession {
        val session = BrowserInspectionSessionStore.sessionFor(root, browserPackageName)
        if (session.addressComplete &&
            (!httpsHandlerRecognized || session.addressHttpsHandlerRecognized)
        ) {
            return session
        }

        if (session.addressComplete) {
            session.addressComplete = false
            session.url = null
            session.addressText = null
            session.addressBarObservable = false
            session.focusedAddressEditor = false
        }
        session.addressHttpsHandlerRecognized = httpsHandlerRecognized

        val expectedWindowId = runCatching { root.windowId }.getOrDefault(-1)
        if (browserPackageName.isBlank() || expectedWindowId < 0 ||
            root.packageName?.toString() != browserPackageName
        ) {
            session.addressComplete = true
            if (session.surface == null) {
                session.surface = BrowserSurfaceInspector.inspect(root, browserPackageName)
            }
            return session
        }

        val budget = session.budget
        val directEntries = buildList {
            addAll(addressBarEntryNamesFor(browserPackageName))
            if (httpsHandlerRecognized) {
                BrowserUiCapabilityPolicy.weakReadOnlyAddressBarEntryNames.forEach { entry ->
                    if (entry !in this) add(entry)
                }
            }
        }

        fun recordEvidence(
            node: AccessibilityNodeInfo,
            method: BrowserIdentificationMethod
        ) {
            val text = extractTextFromNode(node)
            val candidate = extractCandidateFromNode(node)
            session.addressBarObservable = true
            session.strongAddressBarObserved = session.strongAddressBarObserved ||
                BrowserUiCapabilityPolicy.isStrongAddressBarResource(
                    node.viewIdResourceName.orEmpty(), browserPackageName
                )
            session.focusedAddressEditor = session.focusedAddressEditor ||
                (node.isEditable && node.isFocused &&
                    BrowserUiCapabilityPolicy.isActionableAddressBarNode(
                        node.toBrowserUiNode(inspectionBudget = budget), browserPackageName, expectedWindowId,
                        httpsHandlerRecognized
                    ))
            if (session.addressText == null) session.addressText = text
            if (session.url == null) session.url = candidate
            session.identificationMethod = method
            if (session.isCurrent()) BrowserCompatibilityStore.recordIdentificationSuccess(
                packageName = browserPackageName,
                viewIdResourceName = node.viewIdResourceName,
                method = method,
                observedValue = candidate ?: text
            )
        }

        for (entryName in directEntries) {
            if (!budget.tryIdQuery()) break
            val nodes = runCatching {
                root.findAccessibilityNodeInfosByViewId("$browserPackageName:id/$entryName")
            }.getOrDefault(emptyList())
            try {
                nodes.forEach { node ->
                    if (!budget.isExhausted &&
                        isAddressBarNode(
                            node = node,
                            browserPackageName = browserPackageName,
                            expectedWindowId = expectedWindowId,
                            httpsHandlerRecognized = httpsHandlerRecognized,
                            inspectionBudget = budget
                        )
                    ) {
                        recordEvidence(
                            node,
                            identificationMethodFor(browserPackageName, entryName)
                        )
                    }
                }
            } finally {
                nodes.forEach(::recycleSafely)
            }
            if (session.addressBarObservable &&
                (session.url != null || session.addressText != null)
            ) break
        }

        if ((!session.addressBarObservable ||
                (session.url == null && session.addressText == null)) &&
            !budget.isExhausted
        ) {
            var foundSemanticAddressBar = false

            fun visit(node: AccessibilityNodeInfo, depth: Int) {
                if (foundSemanticAddressBar || !budget.tryVisitNode(depth, MAX_TREE_DEPTH)) return
                if (!node.isVisibleToUser ||
                    node.packageName?.toString() != browserPackageName ||
                    node.windowId != expectedWindowId ||
                    BrowserSurfaceInspector.isWebContainer(node)
                ) return

                val variation = node.inputType and InputType.TYPE_MASK_VARIATION
                val facts = node.toBrowserUiNode(
                    uriInput = variation == InputType.TYPE_TEXT_VARIATION_URI,
                    inWebContentOverride = false,
                    inspectionBudget = budget
                )
                if (BrowserUiCapabilityPolicy.isReadOnlyAddressBarNode(
                        node = facts,
                        expectedBrowserPackage = browserPackageName,
                        expectedWindowId = expectedWindowId,
                        httpsHandlerRecognized = httpsHandlerRecognized
                    )
                ) {
                    recordEvidence(node, BrowserIdentificationMethod.SEMANTIC_TREE)
                    foundSemanticAddressBar = true
                    return
                }

                for (index in 0 until node.childCount) {
                    if (foundSemanticAddressBar || !budget.tryChildQuery()) break
                    val child = node.getChild(index) ?: continue
                    try {
                        visit(child, depth + 1)
                    } finally {
                        recycleSafely(child)
                    }
                }
            }

            runCatching { visit(root, 0) }
                .onFailure { error ->
                    if (error is RuntimeException) {
                        FocusGuardLogger.logError(
                            TAG,
                            "Falha na inspeção consolidada da barra de endereço",
                            error
                        )
                    }
                }
        }

        session.addressComplete = true
        if (session.surface == null) {
            session.surface = BrowserSurfaceInspector.inspect(root, browserPackageName)
        }
        BrowserInspectionSessionStore.logIfNeeded(session, "address")
        return session
    }

    fun isPornographySearchInput(text: String): Boolean {
        val candidateUrl = extractUrlCandidate(text)
        if (candidateUrl != null &&
            (isPornographySearchUrl(candidateUrl) || isGoogleImagesUrl(candidateUrl))
        ) {
            return true
        }

        if (isValidUrl(text)) return false

        return containsPornographySearchTerm(text)
    }

    fun isPornographyGoogleSearchUrl(url: String): Boolean {
        if (!isGoogleUrl(url)) return false
        return searchQueryValues(url).any(::containsPornographySearchTerm)
    }

    fun isPornographySearchUrl(url: String): Boolean {
        val domain = extractDomain(url)
        if (domain.isEmpty() || !isKnownSearchEngineDomain(domain)) return false
        return searchQueryValues(url).any(::containsPornographySearchTerm)
    }

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
    }

    private fun extractTextFromNode(node: AccessibilityNodeInfo): String? {
        return sequenceOf(node.text, node.contentDescription)
            .map { value -> sanitizeText(value?.toString().orEmpty()) }
            .firstOrNull(String::isNotEmpty)
    }

    /**
     * Resolves the current tree once and acts only when exactly one browser-owned
     * address bar advertises the requested action. Callers intentionally invoke
     * this again before focus, replacement and submission so stale node handles
     * are never reused across an asynchronous UI change.
     */
    internal fun performUniqueAddressBarAction(
        root: AccessibilityNodeInfo,
        browserPackageName: String,
        expectedWindowId: Int,
        requiredAction: BrowserUiCapabilityPolicy.NodeAction,
        arguments: Bundle? = null,
        textPredicate: ((String?) -> Boolean)? = null,
        httpsHandlerRecognized: Boolean = false,
        allowFallbacks: Boolean = true,
        isCurrent: () -> Boolean
    ): AddressBarActionResult {
        if (!isCurrent()) return AddressBarActionResult(AddressBarActionStatus.REJECTED)
        if (root.packageName?.toString() != browserPackageName ||
            root.windowId != expectedWindowId
        ) return AddressBarActionResult(AddressBarActionStatus.NOT_FOUND)

        if (allowFallbacks && requiredAction == BrowserUiCapabilityPolicy.NodeAction.SET_TEXT &&
            BrowserCompatibilityStore.preferredWriteMethod(browserPackageName) ==
            BrowserWriteMethod.PASTE
        ) {
            val cachedPaste = attemptPasteFallback(
                root = root,
                browserPackageName = browserPackageName,
                expectedWindowId = expectedWindowId,
                arguments = arguments,
                httpsHandlerRecognized = httpsHandlerRecognized,
                isCurrent = isCurrent
            )
            if (!isCurrent()) return AddressBarActionResult(AddressBarActionStatus.REJECTED)
            if (cachedPaste.accepted || cachedPaste.status == AddressBarActionStatus.AMBIGUOUS) {
                return cachedPaste
            }
        }
        if (allowFallbacks && requiredAction == BrowserUiCapabilityPolicy.NodeAction.IME_ENTER) {
            val cachedSubmit = attemptCachedSubmitFallback(
                root = root,
                browserPackageName = browserPackageName,
                expectedWindowId = expectedWindowId,
                textPredicate = textPredicate,
                httpsHandlerRecognized = httpsHandlerRecognized,
                isCurrent = isCurrent
            )
            if (!isCurrent()) return AddressBarActionResult(AddressBarActionStatus.REJECTED)
            if (cachedSubmit?.accepted == true ||
                cachedSubmit?.status == AddressBarActionStatus.AMBIGUOUS
            ) {
                return cachedSubmit
            }
        }

        val nodes = mutableListOf<AccessibilityNodeInfo>()
        addressBarEntryNamesFor(browserPackageName).forEach { entryName ->
            val expectedId = "$browserPackageName:id/$entryName"
            val matches = runCatching {
                root.findAccessibilityNodeInfosByViewId(expectedId)
            }.getOrDefault(emptyList())
            matches.forEach { candidate ->
                if (!retainDistinctActionNode(nodes, candidate)) recycleSafely(candidate)
            }
        }
        // Some browsers expose a genuine native toolbar below their web container,
        // while findAccessibilityNodeInfosByViewId() does not return that descendant.
        // Traverse through the container only for exact strong browser resources.
        // Semantic page fields never enter this action candidate list.
        collectStrongActionAddressBarNodes(
            node = root,
            browserPackageName = browserPackageName,
            expectedWindowId = expectedWindowId,
            output = nodes,
            depth = 0,
            visitedNodes = intArrayOf(0)
        )
        if (httpsHandlerRecognized) {
            collectSemanticActionAddressBarNodes(
                node = root,
                browserPackageName = browserPackageName,
                expectedWindowId = expectedWindowId,
                output = nodes,
                depth = 0,
                visitedNodes = intArrayOf(0)
            )
        }
        return try {
            val facts = runCatching { nodes.map { it.toBrowserUiNode() } }
                .getOrElse {
                    return AddressBarActionResult(AddressBarActionStatus.REJECTED)
                }
            val selection = BrowserUiCapabilityPolicy.resolveUniqueAddressBarNode(
                nodes = facts,
                expectedBrowserPackage = browserPackageName,
                expectedWindowId = expectedWindowId,
                requiredAction = requiredAction,
                textPredicate = textPredicate,
                httpsHandlerRecognized = httpsHandlerRecognized
            )
            val selectedIndex = selection.index
            if (selectedIndex == null) {
                if (selection.status == BrowserUiCapabilityPolicy.SelectionStatus.AMBIGUOUS) {
                    return AddressBarActionResult(AddressBarActionStatus.AMBIGUOUS)
                }
                if (!allowFallbacks) return AddressBarActionResult(AddressBarActionStatus.NOT_FOUND)
                val fallback = when (requiredAction) {
                    BrowserUiCapabilityPolicy.NodeAction.SET_TEXT -> attemptPasteFallback(
                        root = root,
                        browserPackageName = browserPackageName,
                        expectedWindowId = expectedWindowId,
                        arguments = arguments,
                        httpsHandlerRecognized = httpsHandlerRecognized,
                        isCurrent = isCurrent
                    )
                    BrowserUiCapabilityPolicy.NodeAction.IME_ENTER -> attemptSubmitFallback(
                        root = root,
                        browserPackageName = browserPackageName,
                        expectedWindowId = expectedWindowId,
                        textPredicate = textPredicate,
                        httpsHandlerRecognized = httpsHandlerRecognized,
                        isCurrent = isCurrent
                    )
                    else -> AddressBarActionResult(AddressBarActionStatus.NOT_FOUND)
                }
                if (isCurrent() && !fallback.accepted &&
                    (requiredAction == BrowserUiCapabilityPolicy.NodeAction.SET_TEXT ||
                        requiredAction == BrowserUiCapabilityPolicy.NodeAction.IME_ENTER)
                ) {
                    BrowserCompatibilityStore.recordRedirectionFailure(browserPackageName)
                }
                return fallback
            }

            val selected = nodes[selectedIndex]
            if (!isCurrent()) return AddressBarActionResult(AddressBarActionStatus.REJECTED)
            if (allowFallbacks && requiredAction == BrowserUiCapabilityPolicy.NodeAction.SET_TEXT) {
                val selectionResult = AddressBarRedirectionActions.selectAll(
                    root = root,
                    browserPackageName = browserPackageName,
                    expectedWindowId = expectedWindowId,
                    httpsHandlerRecognized = httpsHandlerRecognized,
                    isCurrent = isCurrent
                )
                if (selectionResult.status == AddressBarRedirectionActions.Status.AMBIGUOUS) {
                    return AddressBarActionResult(AddressBarActionStatus.AMBIGUOUS)
                }
            }

            val accepted = if (
                requiredAction == BrowserUiCapabilityPolicy.NodeAction.FOCUS &&
                selected.isFocused
            ) {
                true
            } else {
                val androidAction = requiredAction.androidActionId()
                if (androidAction == null) {
                    if (!allowFallbacks) return AddressBarActionResult(AddressBarActionStatus.NOT_FOUND)
                    val fallback = when (requiredAction) {
                        BrowserUiCapabilityPolicy.NodeAction.IME_ENTER -> attemptSubmitFallback(
                            root = root,
                            browserPackageName = browserPackageName,
                            expectedWindowId = expectedWindowId,
                            textPredicate = textPredicate,
                            httpsHandlerRecognized = httpsHandlerRecognized,
                            isCurrent = isCurrent
                        )
                        else -> AddressBarActionResult(AddressBarActionStatus.REJECTED)
                    }
                    if (isCurrent() && !fallback.accepted &&
                        requiredAction == BrowserUiCapabilityPolicy.NodeAction.IME_ENTER
                    ) {
                        BrowserCompatibilityStore.recordRedirectionFailure(browserPackageName)
                    }
                    return fallback
                }
                if (!isCurrent()) return AddressBarActionResult(AddressBarActionStatus.REJECTED)
                val actionAccepted = runCatching {
                    selected.performAction(androidAction, arguments)
                }.getOrDefault(false)
                if (!isCurrent()) return AddressBarActionResult(AddressBarActionStatus.REJECTED)
                actionAccepted
            }

            if (!isCurrent()) return AddressBarActionResult(AddressBarActionStatus.REJECTED)
            if (accepted) {
                if (allowFallbacks) recordAddressBarActionSuccess(
                    browserPackageName = browserPackageName,
                    selectedViewId = selected.viewIdResourceName,
                    requiredAction = requiredAction,
                    arguments = arguments,
                    isCurrent = isCurrent
                )
                return AddressBarActionResult(
                    status = AddressBarActionStatus.ACCEPTED,
                    selectedViewId = selected.viewIdResourceName
                )
            }

            if (!allowFallbacks) return AddressBarActionResult(AddressBarActionStatus.REJECTED)
            val fallback = when (requiredAction) {
                BrowserUiCapabilityPolicy.NodeAction.SET_TEXT -> attemptPasteFallback(
                    root = root,
                    browserPackageName = browserPackageName,
                    expectedWindowId = expectedWindowId,
                    arguments = arguments,
                    httpsHandlerRecognized = httpsHandlerRecognized,
                    isCurrent = isCurrent
                )
                BrowserUiCapabilityPolicy.NodeAction.IME_ENTER -> attemptSubmitFallback(
                    root = root,
                    browserPackageName = browserPackageName,
                    expectedWindowId = expectedWindowId,
                    textPredicate = textPredicate,
                    httpsHandlerRecognized = httpsHandlerRecognized,
                    isCurrent = isCurrent
                )
                else -> AddressBarActionResult(
                    status = AddressBarActionStatus.REJECTED,
                    selectedViewId = selected.viewIdResourceName
                )
            }
            if (isCurrent() && !fallback.accepted &&
                (requiredAction == BrowserUiCapabilityPolicy.NodeAction.SET_TEXT ||
                    requiredAction == BrowserUiCapabilityPolicy.NodeAction.IME_ENTER)
            ) {
                BrowserCompatibilityStore.recordRedirectionFailure(browserPackageName)
            }
            fallback
        } finally {
            nodes.forEach(::recycleSafely)
        }
    }

    private fun attemptPasteFallback(
        root: AccessibilityNodeInfo,
        browserPackageName: String,
        expectedWindowId: Int,
        arguments: Bundle?,
        httpsHandlerRecognized: Boolean,
        isCurrent: () -> Boolean
    ): AddressBarActionResult {
        if (!isCurrent()) return AddressBarActionResult(AddressBarActionStatus.REJECTED)
        val replacement = arguments?.getCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE
        )?.toString()?.takeIf(String::isNotBlank)
            ?: return AddressBarActionResult(AddressBarActionStatus.REJECTED)

        if (!isCurrent()) return AddressBarActionResult(AddressBarActionStatus.REJECTED)
        val result = ClipboardPasteFallback.pasteSafely(replacement) {
            AddressBarRedirectionActions.paste(
                root = root,
                browserPackageName = browserPackageName,
                expectedWindowId = expectedWindowId,
                httpsHandlerRecognized = httpsHandlerRecognized,
                isCurrent = isCurrent
            )
        }.toLegacyAddressBarActionResult()
        if (!isCurrent()) return AddressBarActionResult(AddressBarActionStatus.REJECTED)
        if (result.accepted) {
            BrowserCompatibilityStore.recordWriteSuccess(
                packageName = browserPackageName,
                viewIdResourceName = result.selectedViewId,
                method = BrowserWriteMethod.PASTE,
                replacementText = replacement
            )
        }
        return result
    }

    private fun attemptCachedSubmitFallback(
        root: AccessibilityNodeInfo,
        browserPackageName: String,
        expectedWindowId: Int,
        textPredicate: ((String?) -> Boolean)?,
        httpsHandlerRecognized: Boolean,
        isCurrent: () -> Boolean
    ): AddressBarActionResult? = when (
        BrowserCompatibilityStore.preferredSubmitMethod(browserPackageName)
    ) {
        BrowserSubmitMethod.ANNOUNCED_EDITOR_ACTION -> performAnnouncedSubmit(
            root,
            browserPackageName,
            expectedWindowId,
            textPredicate,
            httpsHandlerRecognized,
            isCurrent
        )
        BrowserSubmitMethod.CERTIFIED_GO_BUTTON -> performGoButtonSubmit(
            root,
            browserPackageName,
            expectedWindowId,
            isCurrent
        )
        BrowserSubmitMethod.IME_ENTER,
        null -> null
    }

    private fun attemptSubmitFallback(
        root: AccessibilityNodeInfo,
        browserPackageName: String,
        expectedWindowId: Int,
        textPredicate: ((String?) -> Boolean)?,
        httpsHandlerRecognized: Boolean,
        isCurrent: () -> Boolean
    ): AddressBarActionResult {
        val preferred = BrowserCompatibilityStore.preferredSubmitMethod(browserPackageName)
        val order = when (preferred) {
            BrowserSubmitMethod.CERTIFIED_GO_BUTTON -> listOf(
                BrowserSubmitMethod.CERTIFIED_GO_BUTTON,
                BrowserSubmitMethod.ANNOUNCED_EDITOR_ACTION
            )
            else -> listOf(
                BrowserSubmitMethod.ANNOUNCED_EDITOR_ACTION,
                BrowserSubmitMethod.CERTIFIED_GO_BUTTON
            )
        }
        order.forEach { method ->
            if (!isCurrent()) return AddressBarActionResult(AddressBarActionStatus.REJECTED)
            val result = when (method) {
                BrowserSubmitMethod.ANNOUNCED_EDITOR_ACTION -> performAnnouncedSubmit(
                    root,
                    browserPackageName,
                    expectedWindowId,
                    textPredicate,
                    httpsHandlerRecognized,
                    isCurrent
                )
                BrowserSubmitMethod.CERTIFIED_GO_BUTTON -> performGoButtonSubmit(
                    root,
                    browserPackageName,
                    expectedWindowId,
                    isCurrent
                )
                BrowserSubmitMethod.IME_ENTER -> null
            } ?: return@forEach
            if (!isCurrent()) return AddressBarActionResult(AddressBarActionStatus.REJECTED)
            if (result.accepted || result.status == AddressBarActionStatus.AMBIGUOUS) {
                return result
            }
        }
        return AddressBarActionResult(AddressBarActionStatus.NOT_FOUND)
    }

    private fun performAnnouncedSubmit(
        root: AccessibilityNodeInfo,
        browserPackageName: String,
        expectedWindowId: Int,
        textPredicate: ((String?) -> Boolean)?,
        httpsHandlerRecognized: Boolean,
        isCurrent: () -> Boolean
    ): AddressBarActionResult {
        if (!isCurrent()) return AddressBarActionResult(AddressBarActionStatus.REJECTED)
        val announced = AddressBarRedirectionActions.submitAnnouncedEditorAction(
            root = root,
            browserPackageName = browserPackageName,
            expectedWindowId = expectedWindowId,
            textPredicate = textPredicate,
            httpsHandlerRecognized = httpsHandlerRecognized,
            isCurrent = isCurrent
        ).toLegacyAddressBarActionResult()
        if (!isCurrent()) return AddressBarActionResult(AddressBarActionStatus.REJECTED)
        if (announced.accepted) {
            BrowserCompatibilityStore.recordSubmitAccepted(
                packageName = browserPackageName,
                viewIdResourceName = announced.selectedViewId,
                method = BrowserSubmitMethod.ANNOUNCED_EDITOR_ACTION
            )
        }
        return announced
    }

    private fun performGoButtonSubmit(
        root: AccessibilityNodeInfo,
        browserPackageName: String,
        expectedWindowId: Int,
        isCurrent: () -> Boolean
    ): AddressBarActionResult {
        if (!isCurrent()) return AddressBarActionResult(AddressBarActionStatus.REJECTED)
        val go = AddressBarRedirectionActions.clickCertifiedGoButton(
            root = root,
            browserPackageName = browserPackageName,
            expectedWindowId = expectedWindowId,
            isCurrent = isCurrent
        ).toLegacyAddressBarActionResult()
        if (!isCurrent()) return AddressBarActionResult(AddressBarActionStatus.REJECTED)
        if (go.accepted) {
            BrowserCompatibilityStore.recordSubmitAccepted(
                packageName = browserPackageName,
                viewIdResourceName = null,
                method = BrowserSubmitMethod.CERTIFIED_GO_BUTTON
            )
        }
        return go
    }

    private fun AddressBarRedirectionActions.Result.toLegacyAddressBarActionResult(): AddressBarActionResult =
        AddressBarActionResult(
            status = when (status) {
                AddressBarRedirectionActions.Status.ACCEPTED -> AddressBarActionStatus.ACCEPTED
                AddressBarRedirectionActions.Status.NOT_FOUND -> AddressBarActionStatus.NOT_FOUND
                AddressBarRedirectionActions.Status.AMBIGUOUS -> AddressBarActionStatus.AMBIGUOUS
                AddressBarRedirectionActions.Status.REJECTED -> AddressBarActionStatus.REJECTED
            },
            selectedViewId = selectedViewId
        )

    private fun recordAddressBarActionSuccess(
        browserPackageName: String,
        selectedViewId: String?,
        requiredAction: BrowserUiCapabilityPolicy.NodeAction,
        arguments: Bundle?,
        isCurrent: () -> Boolean
    ) {
        if (!isCurrent()) return
        when (requiredAction) {
            BrowserUiCapabilityPolicy.NodeAction.FOCUS ->
                BrowserCompatibilityStore.recordActivationSuccess(
                    browserPackageName,
                    selectedViewId,
                    BrowserActivationMethod.FOCUS
                )
            BrowserUiCapabilityPolicy.NodeAction.CLICK ->
                BrowserCompatibilityStore.recordActivationSuccess(
                    browserPackageName,
                    selectedViewId,
                    BrowserActivationMethod.CLICK
                )
            BrowserUiCapabilityPolicy.NodeAction.SET_TEXT ->
                BrowserCompatibilityStore.recordWriteSuccess(
                    packageName = browserPackageName,
                    viewIdResourceName = selectedViewId,
                    method = BrowserWriteMethod.SET_TEXT,
                    replacementText = arguments?.getCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE
                    )?.toString()
                )
            BrowserUiCapabilityPolicy.NodeAction.IME_ENTER ->
                BrowserCompatibilityStore.recordSubmitAccepted(
                    packageName = browserPackageName,
                    viewIdResourceName = selectedViewId,
                    method = BrowserSubmitMethod.IME_ENTER
                )
            BrowserUiCapabilityPolicy.NodeAction.LONG_CLICK -> Unit
        }
    }

    private fun collectStrongActionAddressBarNodes(
        node: AccessibilityNodeInfo,
        browserPackageName: String,
        expectedWindowId: Int,
        output: MutableList<AccessibilityNodeInfo>,
        depth: Int,
        visitedNodes: IntArray
    ) {
        if (depth > MAX_TREE_DEPTH || visitedNodes[0] >= MAX_TREE_NODES ||
            !node.isVisibleToUser || node.windowId != expectedWindowId
        ) return
        visitedNodes[0] += 1

        val belongsToBrowser = node.packageName?.toString() == browserPackageName
        val viewId = node.viewIdResourceName.orEmpty()
        if (belongsToBrowser && BrowserUiCapabilityPolicy.isStrongAddressBarResource(
                viewId,
                browserPackageName
            )
        ) {
            if (output.none { existing -> existing == node }) {
                @Suppress("DEPRECATION")
                output += AccessibilityNodeInfo.obtain(node)
            }
            if (!BrowserUiCapabilityPolicy.shouldSearchAddressBarDescendants(
                    browserPackageName,
                    viewId
                )
            ) return
        }

        for (index in 0 until node.childCount) {
            if (visitedNodes[0] >= MAX_TREE_NODES) break
            val child = node.getChild(index) ?: continue
            try {
                collectStrongActionAddressBarNodes(
                    node = child,
                    browserPackageName = browserPackageName,
                    expectedWindowId = expectedWindowId,
                    output = output,
                    depth = depth + 1,
                    visitedNodes = visitedNodes
                )
            } finally {
                recycleSafely(child)
            }
        }
    }

    private fun collectSemanticActionAddressBarNodes(
        node: AccessibilityNodeInfo,
        browserPackageName: String,
        expectedWindowId: Int,
        output: MutableList<AccessibilityNodeInfo>,
        depth: Int,
        visitedNodes: IntArray
    ) {
        if (depth >= MAX_TREE_DEPTH || visitedNodes[0] >= MAX_TREE_NODES ||
            BrowserSurfaceInspector.isWebContainer(node)
        ) return
        for (index in 0 until node.childCount) {
            if (visitedNodes[0] >= MAX_TREE_NODES) return
            val child = node.getChild(index) ?: continue
            var retained = false
            try {
                visitedNodes[0] += 1
                val facts = runCatching { child.toBrowserUiNode() }.getOrNull()
                val semanticCandidate = facts != null &&
                    !BrowserUiCapabilityPolicy.isStrongAddressBarResource(
                        facts.viewIdResourceName,
                        browserPackageName
                    ) &&
                    BrowserUiCapabilityPolicy.isSemanticActionableAddressBarNode(
                        node = facts,
                        expectedBrowserPackage = browserPackageName,
                        expectedWindowId = expectedWindowId,
                        httpsHandlerRecognized = true
                    )
                if (semanticCandidate) {
                    retained = retainDistinctActionNode(output, child)
                } else {
                    collectSemanticActionAddressBarNodes(
                        node = child,
                        browserPackageName = browserPackageName,
                        expectedWindowId = expectedWindowId,
                        output = output,
                        depth = depth + 1,
                        visitedNodes = visitedNodes
                    )
                }
            } catch (error: RuntimeException) {
                FocusGuardLogger.logError(
                    TAG,
                    "Falha ao procurar campo URI acionável do navegador",
                    error
                )
            } finally {
                if (!retained) recycleSafely(child)
            }
        }
    }

    private fun retainDistinctActionNode(
        output: MutableList<AccessibilityNodeInfo>,
        candidate: AccessibilityNodeInfo
    ): Boolean {
        if (output.any { existing -> existing == candidate }) return false
        output += candidate
        return true
    }

    private fun AccessibilityNodeInfo.toBrowserUiNode(
        uriInput: Boolean =
            (inputType and InputType.TYPE_MASK_VARIATION) ==
                InputType.TYPE_TEXT_VARIATION_URI,
        inWebContentOverride: Boolean? = null,
        inspectionBudget: BrowserInspectionBudget? = null
    ): BrowserUiCapabilityPolicy.Node = BrowserUiCapabilityPolicy.Node(
        packageName = packageName?.toString().orEmpty(),
        windowId = windowId,
        viewIdResourceName = viewIdResourceName.orEmpty(),
        visible = isVisibleToUser,
        editable = isEditable,
        focused = isFocused,
        focusable = isFocusable,
        uriInput = uriInput,
        text = text?.toString(),
        contentDescription = contentDescription?.toString(),
        actions = actionList.mapNotNull { action ->
            when {
                action.id == AccessibilityNodeInfo.ACTION_FOCUS ->
                    BrowserUiCapabilityPolicy.NodeAction.FOCUS
                action.id == AccessibilityNodeInfo.ACTION_SET_TEXT ->
                    BrowserUiCapabilityPolicy.NodeAction.SET_TEXT
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                    action.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id ->
                    BrowserUiCapabilityPolicy.NodeAction.IME_ENTER
                action.id == AccessibilityNodeInfo.ACTION_LONG_CLICK ->
                    BrowserUiCapabilityPolicy.NodeAction.LONG_CLICK
                action.id == AccessibilityNodeInfo.ACTION_CLICK ->
                    BrowserUiCapabilityPolicy.NodeAction.CLICK
                else -> null
            }
        }.toSet(),
        hintText = hintText?.toString(),
        inWebContent = inWebContentOverride
            ?: !BrowserSurfaceInspector.isNativeNode(this, inspectionBudget)
    )

    private fun BrowserUiCapabilityPolicy.NodeAction.androidActionId(): Int? = when (this) {
        BrowserUiCapabilityPolicy.NodeAction.FOCUS -> AccessibilityNodeInfo.ACTION_FOCUS
        BrowserUiCapabilityPolicy.NodeAction.SET_TEXT -> AccessibilityNodeInfo.ACTION_SET_TEXT
        BrowserUiCapabilityPolicy.NodeAction.IME_ENTER -> if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        ) {
            AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
        } else {
            null
        }
        BrowserUiCapabilityPolicy.NodeAction.LONG_CLICK -> AccessibilityNodeInfo.ACTION_LONG_CLICK
        BrowserUiCapabilityPolicy.NodeAction.CLICK -> AccessibilityNodeInfo.ACTION_CLICK
    }

    private fun findAddressBarNode(
        node: AccessibilityNodeInfo?,
        browserPackageName: String,
        expectedWindowId: Int,
        httpsHandlerRecognized: Boolean,
        depth: Int,
        visitedNodes: IntArray
    ): String? {
        if (node == null || depth > MAX_TREE_DEPTH) return null
        visitedNodes[0] += 1
        if (visitedNodes[0] > MAX_TREE_NODES) return null
        return try {
            if (isAddressBarNode(
                    node,
                    browserPackageName,
                    expectedWindowId,
                    httpsHandlerRecognized
                )
            ) return node.viewIdResourceName.orEmpty()
            for (index in 0 until node.childCount) {
                if (visitedNodes[0] >= MAX_TREE_NODES) break
                val child = node.getChild(index) ?: continue
                try {
                    findAddressBarNode(
                        child,
                        browserPackageName,
                        expectedWindowId,
                        httpsHandlerRecognized,
                        depth + 1,
                        visitedNodes
                    )?.let { return it }
                } finally {
                    recycleSafely(child)
                }
            }
            null
        } catch (error: RuntimeException) {
            FocusGuardLogger.logError(TAG, "Falha ao localizar barra de endereço", error)
            null
        }
    }

    private fun findAddressBarValue(
        node: AccessibilityNodeInfo?,
        browserPackageName: String,
        expectedWindowId: Int,
        httpsHandlerRecognized: Boolean,
        depth: Int,
        visitedNodes: IntArray
    ): String? {
        if (node == null || depth > MAX_TREE_DEPTH) return null
        visitedNodes[0] += 1
        if (visitedNodes[0] > MAX_TREE_NODES) return null

        return try {
            if (isAddressBarNode(
                    node,
                    browserPackageName,
                    expectedWindowId,
                    httpsHandlerRecognized
                )
            ) {
                extractCandidateFromNode(node)?.let { candidate ->
                    BrowserCompatibilityStore.recordIdentificationSuccess(
                        packageName = browserPackageName,
                        viewIdResourceName = node.viewIdResourceName,
                        method = BrowserIdentificationMethod.SEMANTIC_TREE,
                        observedValue = candidate
                    )
                    return candidate
                }
            }

            for (index in 0 until node.childCount) {
                if (visitedNodes[0] >= MAX_TREE_NODES) break
                val child = node.getChild(index) ?: continue
                try {
                    findAddressBarValue(
                        child,
                        browserPackageName,
                        expectedWindowId,
                        httpsHandlerRecognized,
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
        expectedWindowId: Int,
        httpsHandlerRecognized: Boolean,
        depth: Int,
        visitedNodes: IntArray
    ): String? {
        if (node == null || depth > MAX_TREE_DEPTH) return null
        visitedNodes[0] += 1
        if (visitedNodes[0] > MAX_TREE_NODES) return null

        return try {
            if (isAddressBarNode(
                    node,
                    browserPackageName,
                    expectedWindowId,
                    httpsHandlerRecognized
                )
            ) {
                extractTextFromNode(node)?.let { text ->
                    BrowserCompatibilityStore.recordIdentificationSuccess(
                        packageName = browserPackageName,
                        viewIdResourceName = node.viewIdResourceName,
                        method = BrowserIdentificationMethod.SEMANTIC_TREE,
                        observedValue = text
                    )
                    return text
                }
            }
            for (index in 0 until node.childCount) {
                if (visitedNodes[0] >= MAX_TREE_NODES) break
                val child = node.getChild(index) ?: continue
                try {
                    findAddressBarText(
                        child,
                        browserPackageName,
                        expectedWindowId,
                        httpsHandlerRecognized,
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
        browserPackageName: String,
        expectedWindowId: Int,
        httpsHandlerRecognized: Boolean,
        inspectionBudget: BrowserInspectionBudget? = null
    ): Boolean {
        val variation = node.inputType and InputType.TYPE_MASK_VARIATION
        return BrowserUiCapabilityPolicy.isReadOnlyAddressBarNode(
            node = node.toBrowserUiNode(
                uriInput = variation == InputType.TYPE_TEXT_VARIATION_URI,
                inspectionBudget = inspectionBudget
            ),
            expectedBrowserPackage = browserPackageName,
            expectedWindowId = expectedWindowId,
            httpsHandlerRecognized = httpsHandlerRecognized
        )
    }

    private fun addressBarEntryNamesFor(browserPackageName: String): List<String> =
        BrowserCompatibilityStore.prioritizeAddressBarEntryNames(
            packageName = browserPackageName,
            defaults = strongAddressBarEntryNames
        )

    private fun identificationMethodFor(
        browserPackageName: String,
        entryName: String
    ): BrowserIdentificationMethod = when {
        BrowserCompatibilityStore.preferredAddressBarEntryName(browserPackageName) == entryName ->
            BrowserIdentificationMethod.CACHED_RESOURCE_ID
        entryName in strongAddressBarEntryNames -> BrowserIdentificationMethod.STRONG_RESOURCE_ID
        else -> BrowserIdentificationMethod.SEMANTIC_TREE
    }

    private fun isKnownSearchEngineDomain(domain: String): Boolean {
        if (GOOGLE_HOST_REGEX.matches(domain)) return true
        return SEARCH_ENGINE_DOMAIN_SUFFIXES.any { suffix ->
            domain == suffix || domain.endsWith(".$suffix")
        }
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

        return runCatching {
            IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
        }.getOrDefault("")
    }

    private fun decodePercentEncodedHost(host: String): String {
        if ('%' !in host) return host
        return runCatching {
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
    private val SEARCH_QUERY_KEYS = setOf("q", "query", "oq", "p", "text", "search", "keyword", "wd")
    private val SEARCH_ENGINE_DOMAIN_SUFFIXES = setOf(
        "bing.com", "duckduckgo.com", "search.brave.com", "ecosia.org",
        "startpage.com", "qwant.com", "yahoo.com", "yandex.com", "yandex.ru",
        "baidu.com", "aol.com", "ask.com", "naver.com"
    )
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
