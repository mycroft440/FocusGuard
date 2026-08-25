package com.focusguard.ui.compose.theme

import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Dark Theme Base — unified with the Pomodoro visual language.
val DarkBg = Color(0xFF05080B)
val DarkSurface = Color(0xFF0E141B)
val DarkCard = Color(0xFF141B23)
val DarkCardElevated = Color(0xFF10161D)

// Accent Colors
val AccentCyan = Color(0xFF5CCFE6)
val AccentCyanDark = Color(0xFF3AAFC5)
val AccentPurple = Color(0xFF7C4DFF)
val AccentPurpleDark = Color(0xFF5E35B1)
val AccentCyanInk = Color(0xFF04222A)
val AccentCyanTint = Color(0x1F5CCFE6)
val AccentCyanLine = Color(0x475CCFE6)

// Semantic Colors
val SuccessGreen = Color(0xFF4CAF50)
val WarningAmber = Color(0xFFFFC107)
val DangerRed = Color(0xFFE53935)
val DangerRedDark = Color(0xFFC62828)
val InfoBlue = Color(0xFF2196F3)

// Text Colors
val TextPrimary = Color(0xFFEDF2F7)
val TextSecondary = Color(0xFF93A1AD)
val TextHint = Color(0xFF64717D)
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

val FocusGuardTypography = Typography(
    headlineLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 17.sp
    ),
    bodyLarge = TextStyle(
        fontSize = 16.sp
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp
    ),
    bodySmall = TextStyle(
        fontSize = 13.sp
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp
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
        content = content
    )
}
