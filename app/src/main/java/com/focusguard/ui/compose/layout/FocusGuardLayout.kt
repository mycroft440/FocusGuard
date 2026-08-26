package com.focusguard.ui.compose.layout

import kotlin.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.R
import com.focusguard.ui.compose.theme.AccentCyan
import com.focusguard.ui.compose.theme.FocusGuardAmbientBackground
import com.focusguard.ui.compose.theme.FocusGuardBackButton
import com.focusguard.ui.compose.theme.FocusSectionLabel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FocusGuardScreenScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    containerColor: Color = MaterialTheme.colorScheme.background,
    content: @Composable (PaddingValues) -> Unit
) {
    // O halo fica por fora do Scaffold para cobrir barra e conteúdo de uma vez:
    // pintado só na área de conteúdo, apareceria uma emenda logo abaixo do
    // título, justamente onde o degradê é mais claro.
    FocusGuardAmbientBackground(
        modifier = Modifier.fillMaxSize(),
        baseColor = containerColor
    ) {
        Scaffold(
            topBar = {
                FocusGuardTopBar(
                    title = title,
                    onBack = onBack
                )
            },
            containerColor = Color.Transparent,
            content = content
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FocusGuardTopBar(
    title: String,
    onBack: (() -> Unit)? = null
) {
    TopAppBar(
        title = {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.2).sp
            )
        },
        navigationIcon = {
            if (onBack != null) {
                FocusGuardBackButton(
                    onBack = onBack,
                    contentDescription = stringResource(R.string.action_back),
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            titleContentColor = MaterialTheme.colorScheme.onBackground,
            navigationIconContentColor = MaterialTheme.colorScheme.onBackground
        )
    )
}

@Composable
fun FocusGuardScrollableContent(
    paddingValues: PaddingValues,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    // Sem cor própria: quem pinta o fundo é o [FocusGuardScreenScaffold] em
    // volta, então o halo do topo continua visível atrás do conteúdo rolável.
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(paddingValues)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        content = content
    )
}

@Composable
fun FocusGuardSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    color: Color = AccentCyan
) {
    FocusSectionLabel(
        text = title,
        modifier = modifier.padding(start = 4.dp),
        accent = color
    )
}
