package com.focusguard.security

import java.text.Normalizer
import java.util.Locale

/**
 * Reconhece as telas do Android que concedem a permissão "Instalar apps desconhecidos"
 * (a lista geral e a página de cada app). Com o bloqueio de fontes desconhecidas ligado,
 * o serviço de acessibilidade fecha essas telas, como faz com a desativação da própria
 * acessibilidade.
 */
object UnknownSourcesInterceptionPolicy {

    /** Trechos do nome da Activity das telas de fontes desconhecidas (AOSP, One UI, MIUI). */
    private val classMarkers = listOf(
        "externalsources",
        "unknownsources",
        "unknownapp",
        "installunknown"
    )

    /** Títulos e rótulos das telas, já sem acento e em minúsculas. */
    private val textMarkers = listOf(
        "instalar apps desconhecidos",
        "instalar aplicativos desconhecidos",
        "instalar apps de fontes desconhecidas",
        "fontes desconhecidas",
        "permitir desta fonte",
        "permitir a partir desta fonte",
        "install unknown apps",
        "unknown sources",
        "allow from this source",
        "instalar aplicaciones desconocidas",
        "origenes desconocidos",
        "permitir de esta fuente"
    )

    /** Textos procurados na árvore quando o evento não traz o título. */
    val nodeSearchTerms = listOf(
        "Instalar apps desconhecidos",
        "Instalar aplicativos desconhecidos",
        "Permitir desta fonte",
        "Install unknown apps",
        "Allow from this source"
    )

    fun matchesClass(className: CharSequence?): Boolean {
        val normalized = className?.toString()?.lowercase(Locale.ROOT) ?: return false
        return classMarkers.any(normalized::contains)
    }

    fun matchesText(values: Iterable<CharSequence?>): Boolean =
        values.any { value -> value != null && matchesText(value) }

    fun matchesText(value: CharSequence): Boolean {
        val normalized = normalize(value)
        return textMarkers.any(normalized::contains)
    }

    private fun normalize(value: CharSequence): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT)
}
