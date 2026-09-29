package com.focusguard.sitesblocker

import android.content.Context
import java.net.URI
import java.util.Locale

/**
 * Endereço para onde o navegador é levado depois de um site bloqueado (Configurações >
 * Destino do redirecionamento). Sem escolha, é o Google.
 *
 * Guarda só a raiz do site ("https://exemplo.com"): é o que o redirecionamento digita na barra
 * de endereço, e a chegada é conferida pelo host.
 */
object RedirectDestinationStore {
    const val DEFAULT_URL = "https://google.com"

    enum class SaveResult { SAVED, INVALID_URL, BLOCKED }

    private const val PREFS = "redirect_destination"
    private const val KEY_URL = "url"

    @JvmStatic
    fun url(context: Context): String =
        preferences(context).getString(KEY_URL, null)
            ?.let(::normalize)
            ?: DEFAULT_URL

    /**
     * Salva o destino digitado. Um destino bloqueado é recusado: o destino fica isento da
     * lista de bloqueio (para não entrar em loop), então aceitá-lo abriria uma brecha.
     *
     * @param isBlockedByFocusGuard se um bloqueio ativo do FocusGuard (sessão, senha) pega a URL.
     */
    fun save(
        context: Context,
        rawUrl: String,
        isBlockedByFocusGuard: (String) -> Boolean
    ): SaveResult {
        val url = normalize(rawUrl) ?: return SaveResult.INVALID_URL
        if (url != DEFAULT_URL &&
            (isBlockedBySitesList(BlockedSitesStore(context), url) || isBlockedByFocusGuard(url))
        ) {
            return SaveResult.BLOCKED
        }
        preferences(context).edit().putString(KEY_URL, url).apply()
        return SaveResult.SAVED
    }

    /** Lista e filtro de pornografia do Bloquear sites. */
    @JvmStatic
    fun isBlockedBySitesList(store: BlockedSitesStore, url: String): Boolean =
        DomainMatcher.findMatchedDomainNormalized(url, store.normalizedDomains) != null ||
            (store.isAdultFilterEnabled && AdultContentFilter.blocksUrl(url))

    /**
     * "exemplo.com", "https://www.exemplo.com/" → "https://exemplo.com". Só a raiz do site,
     * em HTTP ou HTTPS, sem credenciais, porta, caminho, consulta ou fragmento.
     */
    @JvmStatic
    fun normalize(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val candidate = if (SCHEME.containsMatchIn(trimmed)) trimmed else "https://$trimmed"
        val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
            ?.takeIf { it == "http" || it == "https" }
            ?: return null
        if (uri.rawUserInfo != null || uri.port != -1) return null
        if (!uri.rawPath.isNullOrEmpty() && uri.rawPath != "/") return null
        if (uri.rawQuery != null || uri.rawFragment != null) return null
        val host = DomainMatcher.extractHost(candidate) ?: return null
        return "$scheme://$host"
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")
}
