package com.sx4scanpro

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A reading above the owner's limit makes its gauge "pop": a red border that pulses with a small
 * scale throb. It is purely a reaction to a limit the owner set; nothing here decides what is normal.
 */
internal fun Modifier.alertPulse(active: Boolean, radius: Dp = 14.dp): Modifier = if (!active) this else composed {
    val t = rememberInfiniteTransition(label = "alert-pulse")
    val beat by t.animateFloat(0f, 1f, infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "alert-beat")
    val shape = RoundedCornerShape(radius)
    this
        .graphicsLayer { val s = 1f + 0.03f * beat; scaleX = s; scaleY = s }
        .drawBehind { drawRoundRect(Sx.Primary.copy(alpha = 0.10f + 0.22f * beat), cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius.toPx())) }
        .border(3.dp, Sx.Primary.copy(alpha = 0.45f + 0.55f * beat), shape)
}
