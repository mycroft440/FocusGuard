package com.focusguard.ui.compose.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Dark Theme Base
val DarkBg = Color(0xFF0D0D0D)
val DarkSurface = Color(0xFF161616)
val DarkCard = Color(0xFF1C1C1E)
val DarkCardElevated = Color(0xFF252528)

// Accent Colors
val AccentCyan = Color(0xFF00BCD4)
val AccentCyanDark = Color(0xFF0097A7)
val AccentPurple = Color(0xFF7C4DFF)
val AccentPurpleDark = Color(0xFF5E35B1)

// Semantic Colors
val SuccessGreen = Color(0xFF4CAF50)
val WarningAmber = Color(0xFFFFC107)
val DangerRed = Color(0xFFE53935)
val DangerRedDark = Color(0xFFC62828)
val InfoBlue = Color(0xFF2196F3)

// Text Colors
val TextPrimary = Color(0xFFFAFAFA)
val TextSecondary = Color(0xFFB0B0B0)
val TextHint = Color(0xFF6B6B6B)
val TextDisabled = Color(0xFF4A4A4A)

// Borders & Dividers
val Border = Color(0xFF2A2A2E)
val BorderSubtle = Color(0xFF1F1F23)
val Divider = Color(0xFF2A2A2E)
val CardBorder = Color(0xFF303035)

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
    onPrimary = Color.White,
    primaryContainer = AccentCyanDark,
    onPrimaryContainer = Color.White,
    secondary = AccentPurple,
    onSecondary = Color.White,
    secondaryContainer = AccentPurpleDark,
    onSecondaryContainer = Color.White,
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
    onPrimaryContainer = Color.White,
    secondary = AccentPurpleDark,
    onSecondary = Color.White,
    secondaryContainer = AccentPurple,
    onSecondaryContainer = Color.White,
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
    dynamicColor: Boolean = true,
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
