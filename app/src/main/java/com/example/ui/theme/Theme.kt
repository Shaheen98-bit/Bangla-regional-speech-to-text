package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val ElegantDarkColorScheme = darkColorScheme(
    primary = LavenderPrimary,
    onPrimary = LavenderOnPrimary,
    primaryContainer = LavenderContainer,
    onPrimaryContainer = LavenderPrimary,
    secondary = LavenderPrimary,
    onSecondary = LavenderOnPrimary,
    tertiary = LavenderPrimary,
    onTertiary = LavenderOnPrimary,
    background = ElegantDarkBackground,
    onBackground = TextPrimary,
    surface = ElegantDarkSurface,
    onSurface = TextPrimary,
    surfaceVariant = ElegantDarkSurfaceCard,
    onSurfaceVariant = TextSecondary,
    outline = ElegantDarkBorder,
    outlineVariant = ElegantDarkBorderSubtle,
    error = ErrorRed,
    onError = ErrorContainerDark,
    errorContainer = ErrorContainer,
    onErrorContainer = ErrorRed
)

private val LightColorScheme = lightColorScheme(
    primary = LavenderContainer,
    onPrimary = Color.White,
    primaryContainer = LavenderPrimary,
    onPrimaryContainer = Color.Black,
    secondary = LavenderContainer,
    onSecondary = Color.White,
    background = Color(0xFFF4F2F7),
    onBackground = Color(0xFF1C1B1F),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1C1B1F),
    surfaceVariant = Color(0xFFE7E0EC),
    onSurfaceVariant = Color(0xFF49454F),
    outline = Color(0xFF79747E),
    error = Color(0xFFBA1A1A),
    onError = Color.White
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true, // Elegant Dark theme is default
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) ElegantDarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
