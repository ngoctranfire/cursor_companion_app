package com.vibecode.companion.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF82B1FF),
    onPrimary = Color(0xFF00254D),
    primaryContainer = Color(0xFF1A3A6B),
    onPrimaryContainer = Color(0xFFD6E3FF),
    secondary = Color(0xFF8BD0BA),
    onSecondary = Color(0xFF003828),
    tertiary = Color(0xFFE5B8F4),
    background = Color(0xFF0E1116),
    onBackground = Color(0xFFE2E2E8),
    surface = Color(0xFF12151C),
    onSurface = Color(0xFFE2E2E8),
    surfaceVariant = Color(0xFF1C2129),
    onSurfaceVariant = Color(0xFFAEB4C0),
    outline = Color(0xFF3A4150),
    error = Color(0xFFFFB4AB),
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF2B5CB0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E3FF),
    onPrimaryContainer = Color(0xFF001B3F),
    secondary = Color(0xFF1F6B52),
    background = Color(0xFFFAF9FC),
    onBackground = Color(0xFF1A1B20),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1B20),
    surfaceVariant = Color(0xFFE7E8EE),
    onSurfaceVariant = Color(0xFF44474F),
    outline = Color(0xFF757780),
)

@Composable
fun CompanionTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        content = content,
    )
}
