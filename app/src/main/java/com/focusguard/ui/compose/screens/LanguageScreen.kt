package com.focusguard.ui.compose.screens

import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.os.LocaleListCompat
import com.focusguard.R
import com.focusguard.ui.compose.theme.AccentCyan
import com.focusguard.ui.compose.theme.CardBorder
import com.focusguard.ui.compose.theme.DarkBg
import com.focusguard.ui.compose.theme.DarkSurface
import com.focusguard.ui.compose.theme.TextPrimary
import java.util.Locale

internal data class AppLanguageOption(@StringRes val labelRes: Int, val languageTag: String)

internal val SUPPORTED_APP_LANGUAGES = listOf(
    AppLanguageOption(R.string.fg_language_english, "en"),
    AppLanguageOption(R.string.fg_language_chinese_simplified, "zh-Hans"),
    AppLanguageOption(R.string.fg_language_hindi, "hi"),
    AppLanguageOption(R.string.fg_language_spanish, "es"),
    AppLanguageOption(R.string.fg_language_arabic, "ar"),
    AppLanguageOption(R.string.fg_language_french, "fr"),
    AppLanguageOption(R.string.fg_language_bengali, "bn"),
    AppLanguageOption(R.string.fg_language_portuguese, "pt"),
    AppLanguageOption(R.string.fg_language_indonesian, "id"),
    AppLanguageOption(R.string.fg_language_urdu, "ur"),
    AppLanguageOption(R.string.fg_language_russian, "ru"),
    AppLanguageOption(R.string.fg_language_german, "de"),
    AppLanguageOption(R.string.fg_language_japanese, "ja"),
    AppLanguageOption(R.string.fg_language_nigerian_pidgin, "pcm"),
    AppLanguageOption(R.string.fg_language_egyptian_arabic, "arz"),
    AppLanguageOption(R.string.fg_language_marathi, "mr"),
    AppLanguageOption(R.string.fg_language_vietnamese, "vi"),
    AppLanguageOption(R.string.fg_language_telugu, "te"),
    AppLanguageOption(R.string.fg_language_swahili, "sw"),
    AppLanguageOption(R.string.fg_language_hausa, "ha")
)

/**
 * A opção da lista que corresponde ao idioma escolhido no app: "" quando ele segue
 * o aparelho e null quando o idioma salvo não está na lista (escolhido nas
 * configurações do Android, por exemplo). Sem tag exata, vale a mesma língua.
 */
internal fun selectedAppLanguageTag(
    current: Locale?,
    options: List<String> = SUPPORTED_APP_LANGUAGES.map { it.languageTag }
): String? {
    if (current == null) return ""
    val tag = current.toLanguageTag()
    return options.firstOrNull { it.equals(tag, ignoreCase = true) }
        ?: options.firstOrNull { it.equals(current.language, ignoreCase = true) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguageScreen(onBack: () -> Unit) {
    // Trocar o idioma recria a tela, então a leitura feita ao abri-la já é a atual.
    val selectedTag = remember {
        selectedAppLanguageTag(AppCompatDelegate.getApplicationLocales()[0])
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.idioma_language), color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                            tint = TextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkSurface)
            )
        },
        containerColor = DarkBg
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
        ) {
            LanguageItem(
                stringResource(R.string.fg_language_system_default),
                "",
                selected = selectedTag == ""
            )
            SUPPORTED_APP_LANGUAGES.forEach { language ->
                HorizontalDivider(color = CardBorder)
                LanguageItem(
                    stringResource(language.labelRes),
                    language.languageTag,
                    selected = selectedTag == language.languageTag
                )
            }
            HorizontalDivider(color = CardBorder)
        }
    }
}

@Composable
fun LanguageItem(label: String, langCode: String, selected: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton) {
                if (langCode.isEmpty()) {
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
                } else {
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(langCode))
                }
            }
            .padding(24.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            fontSize = 16.sp,
            color = if (selected) AccentCyan else TextPrimary,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
        )
        if (selected) {
            Icon(Icons.Default.Check, contentDescription = null, tint = AccentCyan)
        }
    }
}
