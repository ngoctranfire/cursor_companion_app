package com.vibecode.companion.ui.common

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vibecode.companion.ui.theme.runStatusColor
import com.vibecode.companion.ui.theme.runStatusIsActive

/**
 * A pulsing dot — used to signal a live/active run. Idle (non-active) statuses
 * render a static dot.
 */
@Composable
fun StatusDot(color: Color, active: Boolean, modifier: Modifier = Modifier, size: Dp = 8.dp) {
    val pulse by if (active) {
        val transition = rememberInfiniteTransition(label = "pulse")
        transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(750),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "pulseAlpha",
        )
    } else {
        androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(1f) }
    }
    Canvas(modifier = modifier.size(size)) {
        if (active) {
            drawCircle(color = color.copy(alpha = 0.25f * pulse), radius = this.size.minDimension / 2f)
            drawCircle(color = color.copy(alpha = pulse), radius = this.size.minDimension / 3.4f)
        } else {
            drawCircle(color = color, radius = this.size.minDimension / 3.4f)
        }
    }
}

/**
 * Expressive run-status pill: tonal background in the status color, a leading
 * dot that pulses while the run is active, and the status label.
 */
@Composable
fun StatusPill(status: String?, modifier: Modifier = Modifier) {
    val color = runStatusColor(status)
    val active = runStatusIsActive(status)
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.14f),
        contentColor = color,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
        ) {
            StatusDot(color = color, active = active, size = 9.dp)
            Spacer(Modifier.width(6.dp))
            Text(
                text = status ?: "UNKNOWN",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
