package com.sx4scanpro

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.min

private val SpeedOrange = Color(0xFFFF4A1C)
private val SpeedAmber = Color(0xFFFFA726)
private val SpeedDim = Color(0x66FFFFFF)

/**
 * Digital speed readout drawn over the top-right corner of the car image: a large italic monospace number, an orange KM/H label and a
 * thin segmented bar. It shows only [readout], which comes from the ECU's 010D reading; an invalid or stale speed is a dash and an empty bar.
 * The number eases between real readings over 300 ms; it never invents a value between updates.
 */
@Composable
internal fun SxDigitalSpeed(readout: SpeedReadout, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier) {
        // Scales with the room the car image gives it: tablet landscape and phone layouts both stay inside the image.
        val numberDp = min(56f, min(maxWidth.value * 0.15f, maxHeight.value * 0.36f)).coerceAtLeast(26f)
        val numberSp = with(LocalDensity.current) { numberDp.dp.toSp() }
        val labelSp = with(LocalDensity.current) { (numberDp * 0.22f).coerceAtLeast(9f).dp.toSp() }
        val description = readout.kmh?.let { "Vehicle speed $it kilometres per hour" } ?: "Vehicle speed unavailable"
        Column(Modifier.semantics { contentDescription = description }, horizontalAlignment = Alignment.End) {
            if (readout.valid) ValidSpeed(readout.kmh!!, numberSp, labelSp, numberDp)
            else SpeedRow("—", "", numberSp, labelSp, numberDp, 0)
        }
    }
}

/** Only in composition while a valid speed exists, so after a dash it starts at the new reading instead of counting up from an old one. */
@Composable
private fun ValidSpeed(kmh: Int, numberSp: androidx.compose.ui.unit.TextUnit, labelSp: androidx.compose.ui.unit.TextUnit, numberDp: Float) {
    val shown by animateIntAsState(kmh, tween(300, easing = FastOutSlowInEasing), label = "speed")
    val r = SpeedReadout(shown)
    SpeedRow(r.digits, r.dimPrefix, numberSp, labelSp, numberDp, r.litSegments())
}

@Composable
private fun SpeedRow(digits: String, dimPrefix: String, numberSp: androidx.compose.ui.unit.TextUnit, labelSp: androidx.compose.ui.unit.TextUnit, numberDp: Float, lit: Int) {
    Row(verticalAlignment = Alignment.Bottom) {
        if (dimPrefix.isNotEmpty()) Text(dimPrefix, color = SpeedDim.copy(alpha = 0.2f), fontSize = numberSp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic, fontFamily = FontFamily.Monospace)
        Text(
            digits.removePrefix(dimPrefix), color = if (digits == "—") SpeedDim else Color.White,
            fontSize = numberSp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic, fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.width(6.dp))
        Text("KM/H", color = SpeedOrange, fontSize = labelSp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = (numberDp * 0.2f).dp))
    }
    Canvas(Modifier.width((numberDp * 2.7f).dp).height(5.dp).padding(top = 1.dp)) {
        val segments = SpeedReadout.SEGMENTS
        val gap = 3.dp.toPx()
        val w = (size.width - gap * (segments - 1)) / segments
        repeat(segments) { i ->
            val on = i < lit
            drawRoundRect(
                brush = if (on) Brush.horizontalGradient(listOf(SpeedAmber, SpeedOrange)) else Brush.horizontalGradient(listOf(SpeedDim.copy(alpha = 0.12f), SpeedDim.copy(alpha = 0.12f))),
                topLeft = Offset(i * (w + gap), 0f), size = Size(w, size.height), cornerRadius = CornerRadius(2f)
            )
        }
    }
}
