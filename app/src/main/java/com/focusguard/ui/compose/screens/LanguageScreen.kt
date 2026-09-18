package com.focusguard.ui.compose.screens

import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.os.LocaleListCompat
import com.focusguard.R
import com.focusguard.ui.compose.theme.CardBorder
import com.focusguard.ui.compose.theme.DarkBg
import com.focusguard.ui.compose.theme.DarkSurface
import com.focusguard.ui.compose.theme.TextPrimary

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguageScreen(onBack: () -> Unit) {
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
            LanguageItem(stringResource(R.string.fg_language_system_default), "")
            SUPPORTED_APP_LANGUAGES.forEach { language ->
                Divider(color = CardBorder)
                LanguageItem(stringResource(language.labelRes), language.languageTag)
            }
            Divider(color = CardBorder)
        }
    }
}

@Composable
fun LanguageItem(label: String, langCode: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (langCode.isEmpty()) {
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
                } else {
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(langCode))
                }
            }
            .padding(24.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, fontSize = 16.sp, color = TextPrimary, fontWeight = FontWeight.Medium)
    }
}
