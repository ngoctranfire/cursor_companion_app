package com.vibecode.companion.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Typography with a stronger weight hierarchy than the Material default —
 * tighter, bolder display/title styles for a confident, branded feel.
 */
private val base = Typography()

val CompanionTypography = Typography(
    displaySmall = base.displaySmall.copy(
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.5).sp,
    ),
    headlineMedium = base.headlineMedium.copy(
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.5).sp,
    ),
    headlineSmall = base.headlineSmall.copy(
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.3).sp,
    ),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    labelSmall = base.labelSmall.copy(
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.6.sp,
    ),
    // Monospace for code/identifier rows in the timeline.
    bodySmall = base.bodySmall,
)

/** Monospace style for tool names, paths, branches, and keys. */
val MonoLabel = TextStyle(fontFamily = FontFamily.Monospace)
