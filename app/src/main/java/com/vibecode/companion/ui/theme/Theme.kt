package com.vibecode.companion.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkColors = darkColorScheme(
    primary = Violet,
    onPrimary = Color_onViolet,
    primaryContainer = VioletContainer,
    onPrimaryContainer = OnVioletContainer,
    inversePrimary = VioletDeep,
    secondary = Teal,
    onSecondary = Color_onTeal,
    secondaryContainer = TealContainer,
    onSecondaryContainer = OnTealContainer,
    tertiary = Pink,
    onTertiary = Color_onPink,
    background = Ink,
    onBackground = TextHigh,
    surface = InkSurface,
    onSurface = TextHigh,
    surfaceVariant = InkSurfaceVariant,
    onSurfaceVariant = TextMuted,
    surfaceContainer = InkElevated,
    surfaceContainerHigh = InkSurfaceVariant,
    outline = InkOutline,
    outlineVariant = InkOutlineFaint,
    error = Rose,
    onError = Color_onRose,
    errorContainer = RoseContainer,
    onErrorContainer = OnRoseContainer,
    scrim = Ink,
)

private val LightColors = lightColorScheme(
    primary = VioletDeep,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    primaryContainer = VioletContainer.copy(alpha = 1f),
    onPrimaryContainer = Color_onVioletContainerLight,
    secondary = TealDeep,
    onSecondary = androidx.compose.ui.graphics.Color.White,
    tertiary = Pink,
    background = LightBackground,
    onBackground = Color_inkText,
    surface = LightSurface,
    onSurface = Color_inkText,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = Color_mutedLight,
    outline = Color_outlineLight,
    error = Rose,
)

@Composable
fun CompanionTheme(
    // Dark-first by design — the brand identity is built for the dark canvas.
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = CompanionTypography,
        content = content,
    )
}
