package com.sx4scanpro

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Red-and-black cockpit tokens, converted from the OKLCH palette of the android-ui-refresh prototype. */
/** The five colours that change with the drive mode. */
internal data class SxPalette(val accent: Color, val background: Color, val card: Color, val foreground: Color, val mutedForeground: Color) {
    companion object {
        fun of(mode: DriveMode) = when (mode) {
            DriveMode.SPORT -> SxPalette(Color(0xFFFD2A37), Color(0xFF050404), Color(0xFF0E0A0A), Color(0xFFF4F1F1), Color(0xFF9B9393))
            DriveMode.ECO -> SxPalette(Color(0xFF38C6E8), Color(0xFF050707), Color(0xFF0C1011), Color(0xFFF1F5F6), Color(0xFF7D8A8E))
            DriveMode.HUD -> SxPalette(Color(0xFFB4122B), Color(0xFF000000), Color(0xFF070707), Color(0xFFC0303F), Color(0xFF5A1C22))
        }
    }
}

internal object Sx {
    /** Current (animated) palette; written from the root composable so every reader recomposes. */
    var palette by mutableStateOf(SxPalette.of(DriveMode.SPORT))

    val Background: Color get() = palette.background
    val Foreground: Color get() = palette.foreground
    val Card: Color get() = palette.card
    val Primary: Color get() = palette.accent
    val Secondary = Color(0xFF1C1717)
    val Muted = Color(0xFF241F1F)
    val MutedForeground: Color get() = palette.mutedForeground
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
