package com.sx4scanpro

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Legacy token names, now remapped to the red-and-black palette (see Sx) so older widgets match until each is rebuilt. `Cyan` is the red accent. */
internal object Holo {
    val Void = Color(0xFF050404)
    val Cyan = Color(0xFFFD2A37)
    val Violet = Color(0xFFFF6A65)
    val White = Color(0xFFFFFFFF)
    val Crimson = Color(0xFFFF6A65)
    val Muted = Color(0xFF9B9393)

    /** The premium physics curve of the spec: cubic-bezier(0.16, 1, 0.3, 1). */
    val Ease = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)
}

/**
 * The crystal plate. Compose has no backdrop blur, so the 25px frosted refraction of the web spec
 * is approximated with the same translucent gradient fill, a brighter top-left rim, a dim
 * bottom-right rim, an inner top highlight and a deep black drop shadow.
 */
internal fun Modifier.crystalPanel(radius: Dp = 12.dp, glow: Color = Holo.Cyan): Modifier {
    val shape = RoundedCornerShape(radius)
    return this
        .shadow(24.dp, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.8f), spotColor = Color.Black.copy(alpha = 0.8f))
        .background(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.04f), Color.White.copy(alpha = 0.005f))), shape)
        .background(glow.copy(alpha = 0.02f), shape)
        .border(
            1.dp,
            Brush.linearGradient(listOf(Color.White.copy(alpha = 0.12f), Color.White.copy(alpha = 0.12f), Color.White.copy(alpha = 0.04f))),
            shape
        )
        .drawBehind {
            val inset = radius.toPx()
            drawLine(Color.White.copy(alpha = 0.2f), Offset(inset, 1.dp.toPx()), Offset(size.width - inset, 1.dp.toPx()), strokeWidth = 1.dp.toPx())
        }
}
