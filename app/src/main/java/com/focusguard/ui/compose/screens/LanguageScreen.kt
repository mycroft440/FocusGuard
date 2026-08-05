package com.focusguard.ui.compose.screens

import kotlin.OptIn
import androidx.compose.material3.ExperimentalMaterial3Api
import com.focusguard.R
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.os.LocaleListCompat
import com.focusguard.ui.compose.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguageScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.idioma_language), color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.action_back), tint = TextPrimary)
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
        ) {
            LanguageItem("Automático do Sistema / System default", "")
            Divider(color = CardBorder)
            LanguageItem("Português", "pt")
            Divider(color = CardBorder)
            LanguageItem("English", "en")
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
                    val appLocale = LocaleListCompat.forLanguageTags(langCode)
                    AppCompatDelegate.setApplicationLocales(appLocale)
                }
            }
            .padding(24.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, fontSize = 16.sp, color = TextPrimary, fontWeight = FontWeight.Medium)
    }
}