package com.vibecode.companion.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vibecode.companion.data.api.RunStatus

/** The signature accent gradient used for the brand mark and primary actions. */
val BrandGradient = Brush.linearGradient(listOf(Color(0xFF8B5CFF), Color(0xFF4F8BFF)))
val BrandGradientVivid = Brush.linearGradient(listOf(VioletBright, Teal))

/** Glow-friendly translucent gradient for hero backdrops. */
fun brandGlow(alpha: Float = 0.18f): Brush = Brush.radialGradient(
    listOf(Violet.copy(alpha = alpha), Color.Transparent),
)

/** Expressive color for a run status, used by status pills and chips everywhere. */
fun runStatusColor(status: String?): Color = when (status) {
    RunStatus.RUNNING, RunStatus.CREATING -> Violet
    RunStatus.FINISHED -> Emerald
    RunStatus.ERROR -> Rose
    else -> TextMuted // CANCELLED / EXPIRED / unknown
}

fun runStatusIsActive(status: String?): Boolean =
    status == RunStatus.RUNNING || status == RunStatus.CREATING

/** The app's logo mark: a gradient tile with a sparkle. Sized by [size]. */
@Composable
fun BrandMark(modifier: Modifier = Modifier, size: Dp = 64.dp) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size / 3.4f))
            .background(BrandGradientVivid),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.AutoAwesome,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(size * 0.56f),
        )
    }
}

/**
 * Primary call-to-action with the brand gradient fill. Disabled and loading
 * states dim the gradient and (optionally) show a spinner.
 */
@Composable
fun GradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    leading: @Composable (() -> Unit)? = null,
) {
    val interaction = rememberMutableInteractionSource()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 52.dp)
            .clip(RoundedCornerShape(16.dp))
            .graphicsLayer { this.alpha = if (enabled && !loading) 1f else 0.45f }
            .background(BrandGradient)
            .clickable(
                enabled = enabled && !loading,
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides Color.White) {
            ProvideTextStyle(MaterialTheme.typography.titleMedium) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = Color.White,
                        )
                        Spacer(Modifier.width(10.dp))
                    } else if (leading != null) {
                        leading()
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(text)
                }
            }
        }
    }
}

/** Subtle outlined secondary action that pairs with [GradientButton]. */
@Composable
fun OutlineActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: @Composable (() -> Unit)? = null,
) {
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .clip(RoundedCornerShape(14.dp))
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun rememberMutableInteractionSource() = androidx.compose.runtime.remember { MutableInteractionSource() }
