package com.focusguard.ui.compose.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Dark Theme Base — unified with the Pomodoro visual language.
val DarkBg = Color(0xFF05080B)
val DarkSurface = Color(0xFF080C11)
val DarkCard = Color(0xFF0A0F16)
val DarkCardElevated = Color(0xFF080C11)

// Accent Colors
val AccentCyan = Color(0xFF5CCFE6)
val AccentCyanDark = Color(0xFF3AAFC5)
val AccentPurple = Color(0xFF7C4DFF)
val AccentPurpleDark = Color(0xFF5E35B1)
val AccentCyanInk = Color(0xFF04222A)
val AccentCyanTint = Color(0x1F5CCFE6)
val AccentCyanLine = Color(0x475CCFE6)

// Ciano estendido — o mesmo #5CCFE6 em opacidades diferentes, para os halos,
// realces e bordas. Nenhum troca a cor de identidade do app: são o próprio
// AccentCyan com mais ou menos presença.
//
// O halo (AccentCyanWash) foi baixado de 7% para 5% junto com as superfícies:
// numa tela mais escura, a mesma opacidade de antes voltava a clarear o topo
// mais do que o resto da tela suporta.
val AccentCyanGlow = Color(0x385CCFE6)
val AccentCyanWash = Color(0x0C5CCFE6)
val AccentCyanEdge = Color(0x2E5CCFE6)

// Superfícies com leve profundidade: o topo do card recebe um pouco mais de
// luz que a base, o que dá volume sem precisar de sombra pesada.
//
// O nível de luz foi baixado em dois passos, depois de ver o app rodando: um
// aplicativo que existe para tirar distração da frente não combina com uma tela
// clara, e os cartões destacavam demais do fundo. Saíram de 1.21:1 contra o
// fundo para 1.07:1.
//
// A essa altura quem desenha o cartão é a borda, não o preenchimento: a borda
// está a 1.33:1 do cartão e a 1.42:1 do fundo, enquanto o preenchimento quase
// encosta no fundo. É de propósito — é o que faz a tela parecer apagada sem
// perder onde um cartão começa e termina. Mexer na borda, aí sim, apaga tudo.
//
// O texto por cima melhorou nos dois passos em vez de piorar: apoio de 6.3:1
// para 7.1:1, dica de 4.4:1 para 4.9:1.
val SurfaceRaisedTop = Color(0xFF0C1219)
val SurfaceRaisedBottom = Color(0xFF080C11)

// O segundo nível de profundidade, para o cartão que precisa recuar em relação
// a outro na mesma tela — a etapa ainda por vir da jornada de recuperação, ao
// lado da etapa em curso.
//
// Com as superfícies tão perto do fundo, não sobrou degrau abaixo do cartão
// normal: qualquer tom mais escuro que ele já é o próprio fundo. Então o recuo
// deixou de ser uma cor e passou a ser a ausência dela — a etapa futura fica
// sem preenchimento, e só a borda a desenha. Ao lado de uma etapa em curso que
// tem preenchimento e borda no acento, a diferença se lê de imediato, e sem
// depender de dois cinzas quase iguais que ninguém distingue no escuro.
val SurfaceSunkenTop = DarkBg
val SurfaceSunkenBottom = DarkBg

// Semantic Colors
val SuccessGreen = Color(0xFF4CAF50)
val WarningAmber = Color(0xFFFFC107)
val DangerRed = Color(0xFFE53935)
val DangerRedDark = Color(0xFFC62828)
val InfoBlue = Color(0xFF2196F3)

// Text Colors
val TextPrimary = Color(0xFFEDF2F7)
val TextSecondary = Color(0xFF93A1AD)
// Clareado de #64717D: no card com o topo iluminado o tom antigo caía para
// 3.2:1, abaixo do mínimo de leitura; agora fica em 4.2:1 no card e 5.2:1 no
// fundo do app, e continua visivelmente mais apagado que TextSecondary.
val TextHint = Color(0xFF78848F)
val TextDisabled = Color(0xFF46515A)

// Borders & Dividers
val Border = Color(0xFF222C36)
val BorderSubtle = Color(0xFF18212A)
val Divider = Color(0xFF222C36)
val CardBorder = Color(0xFF222C36)

// Light Theme Base
val LightBg = Color(0xFFF8F9FA)
val LightSurface = Color(0xFFFFFFFF)
val LightCard = Color(0xFFF1F3F4)
val LightCardElevated = Color(0xFFE8EAED)

// Light Text Colors
val LightTextPrimary = Color(0xFF202124)
val LightTextSecondary = Color(0xFF5F6368)
val LightTextHint = Color(0xFF80868B)

// Light Borders
val LightBorder = Color(0xFFDADCE0)
val LightBorderSubtle = Color(0xFFE8EAED)
val LightCardBorder = Color(0xFFE0E0E0)

private val FocusGuardDarkColorScheme = darkColorScheme(
    primary = AccentCyan,
    onPrimary = AccentCyanInk,
    primaryContainer = Color(0xFF16343C),
    onPrimaryContainer = AccentCyan,
    secondary = AccentCyan,
    onSecondary = AccentCyanInk,
    secondaryContainer = Color(0xFF16343C),
    onSecondaryContainer = AccentCyan,
    tertiary = AccentCyan,
    background = DarkBg,
    onBackground = TextPrimary,
    surface = DarkSurface,
    onSurface = TextPrimary,
    surfaceVariant = DarkCard,
    onSurfaceVariant = TextSecondary,
    outline = CardBorder,
    outlineVariant = BorderSubtle,
    error = DangerRed,
    onError = Color.White,
)

private val FocusGuardLightColorScheme = lightColorScheme(
    primary = AccentCyanDark,
    onPrimary = Color.White,
    primaryContainer = AccentCyan,
    onPrimaryContainer = AccentCyanInk,
    secondary = AccentCyanDark,
    onSecondary = Color.White,
    secondaryContainer = AccentCyan,
    onSecondaryContainer = AccentCyanInk,
    tertiary = AccentCyanDark,
    background = LightBg,
    onBackground = LightTextPrimary,
    surface = LightSurface,
    onSurface = LightTextPrimary,
    surfaceVariant = LightCard,
    onSurfaceVariant = LightTextSecondary,
    outline = LightCardBorder,
    outlineVariant = LightBorderSubtle,
    error = DangerRedDark,
    onError = Color.White,
)

/**
 * Cantos arredondados em escala única.
 *
 * Antes cada tela escolhia o próprio raio (12, 14, 16, 18, 20 dp espalhados
 * pelo código) e componentes vizinhos terminavam com curvas diferentes. Com a
 * escala no tema, os componentes do Material 3 já nascem alinhados entre si.
 */
val FocusGuardShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * Tipografia com ritmo de leitura definido.
 *
 * Os tamanhos permanecem os mesmos de antes para não deslocar nenhuma tela; o
 * que muda é o acabamento — títulos com espaçamento entre letras levemente
 * negativo (mais compactos e firmes) e os estilos que faltavam preenchidos,
 * para os componentes do Material 3 pararem de cair no padrão da biblioteca no
 * meio de uma tela nossa.
 *
 * A entrelinha só entra nos estilos que são pedidos pelo nome, junto com o
 * tamanho que vem com eles. O `bodyLarge` fica de fora — o porquê está anotado
 * nele.
 */
val FocusGuardTypography = Typography(
    headlineLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.6).sp
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.4).sp
    ),
    headlineSmall = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.3).sp
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.2).sp
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 17.sp,
        lineHeight = 23.sp,
        letterSpacing = (-0.1).sp
    ),
    titleSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp
    ),
    // bodyLarge fica SEM lineHeight de propósito. Ele é o estilo ambiente do
    // MaterialTheme, herdado por todo `Text` que não declara estilo próprio —
    // e este app escreve `Text(..., fontSize = 11.sp)` em centenas de lugares.
    // Um lineHeight fixo aqui seria herdado por esses textos junto com o
    // tamanho que eles trocaram, e um texto de 11sp passaria a ocupar a altura
    // de um de 16sp: telas apertadas como a do Pomodoro estourariam. Sem valor,
    // cada texto continua usando a entrelinha natural da própria fonte.
    bodyLarge = TextStyle(
        fontSize = 16.sp
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodySmall = TextStyle(
        fontSize = 13.sp,
        lineHeight = 18.sp
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.2.sp
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.3.sp
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.6.sp
    ),
)

@Composable
fun FocusGuardTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S -> {
            val context = androidx.compose.ui.platform.LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> FocusGuardDarkColorScheme
        else -> FocusGuardLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = FocusGuardTypography,
        shapes = FocusGuardShapes,
        content = content
    )
}
