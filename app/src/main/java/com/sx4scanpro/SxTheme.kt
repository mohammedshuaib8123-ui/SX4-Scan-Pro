package com.sx4scanpro

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Red-and-black cockpit tokens, converted from the OKLCH palette of the android-ui-refresh prototype. */
internal object Sx {
    val Background = Color(0xFF050404)
    val Foreground = Color(0xFFF4F1F1)
    val Card = Color(0xFF0E0A0A)
    val Primary = Color(0xFFFD2A37)
    val Secondary = Color(0xFF1C1717)
    val Muted = Color(0xFF241F1F)
    val MutedForeground = Color(0xFF9B9393)
    val Accent = Color(0xFF2B1D1D)
    val Destructive = Color(0xFFFF6A65)
    val Warning = Color(0xFFF3AE58)
    val Border = Color(0xFF292222)
    val Stage = Color(0xFF150D0D)

    val Ease = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)
}

/** Flat dark plate with a hairline border and a faint top-down sheen. */
internal fun Modifier.sxPanel(radius: Dp = 14.dp, border: Color = Sx.Border): Modifier {
    val shape = RoundedCornerShape(radius)
    return this
        .background(Brush.verticalGradient(listOf(Sx.Card, Sx.Background)), shape)
        .border(1.dp, border, shape)
}
