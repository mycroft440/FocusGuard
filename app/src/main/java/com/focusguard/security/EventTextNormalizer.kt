package com.focusguard.security

import java.text.Normalizer
import java.util.Locale

/** Normalizes direct AccessibilityEvent text once for zero-tree policies. */
object EventTextNormalizer {
    data class Prepared(
        val raw: List<CharSequence?>,
        val normalized: List<String>
    ) {
        fun containsAny(terms: Set<String>): Boolean = normalized.any { value ->
            terms.any(value::contains)
        }
    }

    fun prepare(values: Iterable<CharSequence?>): Prepared {
        val raw = values.toList()
        return Prepared(raw, raw.mapNotNull { value ->
            normalize(value?.toString().orEmpty()).takeIf(String::isNotBlank)
        })
    }

    internal fun normalize(value: String): String {
        if (value.isBlank()) return ""
        val lowercase = value.trim().lowercase(Locale.ROOT)
        val withoutMarks = if (lowercase.all { it.code < 128 }) {
            lowercase
        } else {
            val decomposed = Normalizer.normalize(lowercase, Normalizer.Form.NFD)
            buildString(decomposed.length) {
                decomposed.forEach { char ->
                    when (Character.getType(char)) {
                        Character.NON_SPACING_MARK.toInt(),
                        Character.COMBINING_SPACING_MARK.toInt(),
                        Character.ENCLOSING_MARK.toInt() -> Unit
                        else -> append(char)
                    }
                }
            }
        }
        return buildString(withoutMarks.length) {
            var pendingSpace = false
            withoutMarks.forEach { char ->
                if (char.isWhitespace()) {
                    pendingSpace = isNotEmpty()
                } else {
                    if (pendingSpace) append(' ')
                    append(char)
                    pendingSpace = false
                }
            }
        }
    }
}
