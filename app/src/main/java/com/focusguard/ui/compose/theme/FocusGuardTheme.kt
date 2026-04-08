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

val FocusGuardTypography = Typography(
    headlineLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        color = TextPrimary
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        color = TextPrimary
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        color = TextPrimary
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 17.sp,
        color = TextPrimary
    ),
    bodyLarge = TextStyle(
        fontSize = 16.sp,
        color = TextPrimary
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        color = TextSecondary
    ),
    bodySmall = TextStyle(
        fontSize = 13.sp,
        color = TextSecondary
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp,
        color = TextHint
    ),
)

@Composable
fun FocusGuardTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = FocusGuardDarkColorScheme,
        typography = FocusGuardTypography,
        content = content
    )
}
