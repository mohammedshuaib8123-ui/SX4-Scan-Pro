package com.sx4scanpro

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** What a flanking gauge shows once the calibration sweep is over. [fraction] null = no signal. */
internal data class IgnitionGauge(val caption: String, val fraction: Float?, val readout: String)

private const val HEADLINE = "WELCOME ABOARD SX4 AIRLINES 2874"
private const val RIBBON = "SX4 FLIGHT SYSTEMS"

private fun typedText(text: String, shown: Int, color: Color) = buildAnnotatedString {
    withStyle(SpanStyle(color = color)) { append(text.take(shown)) }
    withStyle(SpanStyle(color = Color.Transparent)) { append(text.drop(shown)) }
}

/**
 * The cinematic ignition sequence (timeline in [IgnitionTimeline]). Shown only after the ECU has
 * answered; [linkActive] false is the preview case and ends on ECU OFFLINE. During the calibration
 * sweep the readouts are labelled CALIBRATING because they are animation, not data.
 */
@Composable
internal fun IgnitionSequence(
    linkActive: Boolean,
    left: IgnitionGauge,
    right: IgnitionGauge,
    modifier: Modifier = Modifier
) {
    val clock = remember { Animatable(0f) }
    LaunchedEffect(Unit) { clock.animateTo(IgnitionTimeline.TOTAL, tween((IgnitionTimeline.TOTAL * 1000).toInt(), easing = LinearEasing)) }
    val t = clock.value
    val laser = IgnitionTimeline.laser(t)
    val split = IgnitionTimeline.split(t)
    val unfold = IgnitionTimeline.unfold(t)
    val calibrating = IgnitionTimeline.calibrating(t)
    val offlineNow = IgnitionTimeline.finished(t) && !linkActive

    Box(modifier.fillMaxSize().background(Holo.Void), contentAlignment = Alignment.Center) {
        // Laser line, then it splits into the top and bottom frame lines.
        Canvas(Modifier.fillMaxSize()) {
            val cy = size.height / 2f
            val half = size.width * 0.46f
            val frameDy = size.height * 0.34f * split
            val core = 1.5.dp.toPx()
            val centreAlpha = 1f - split
            if (centreAlpha > 0f) {
                drawLine(Holo.Cyan.copy(alpha = 0.25f * centreAlpha), Offset(size.width / 2f - half * laser, cy), Offset(size.width / 2f + half * laser, cy), strokeWidth = core * 6f, cap = StrokeCap.Round)
                drawLine(Holo.Cyan.copy(alpha = centreAlpha), Offset(size.width / 2f - half * laser, cy), Offset(size.width / 2f + half * laser, cy), strokeWidth = core, cap = StrokeCap.Round)
            }
            if (split > 0f) {
                listOf(cy - frameDy, cy + frameDy).forEach { y ->
                    drawLine(Holo.Cyan.copy(alpha = 0.18f), Offset(size.width / 2f - half, y), Offset(size.width / 2f + half, y), strokeWidth = core * 5f, cap = StrokeCap.Round)
                    drawLine(Brush.horizontalGradient(listOf(Holo.Cyan, Holo.Violet)), Offset(size.width / 2f - half, y), Offset(size.width / 2f + half, y), strokeWidth = core, cap = StrokeCap.Round)
                }
            }
        }
        // Holographic car pulses into view.
        HoloCar(Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 72.dp).graphicsLayer { alpha = unfold }, alpha = 0.34f)

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 40.dp).graphicsLayer { alpha = unfold },
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
        ) {
            IgnitionArc(left, IgnitionTimeline.sweep(t, left.fraction ?: 0f), calibrating, Holo.Cyan)
            Column(
                Modifier.widthIn(max = 520.dp).crystalPanel(14.dp).padding(horizontal = 28.dp, vertical = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(typedText(RIBBON, IgnitionTimeline.typed(t, RIBBON.length, 1.2f, 1.8f), Holo.Muted), fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 6.sp, textAlign = TextAlign.Center)
                Text(typedText(HEADLINE, IgnitionTimeline.typed(t, HEADLINE.length, 1.5f, 2.5f), Holo.White), fontSize = 30.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp, textAlign = TextAlign.Center, lineHeight = 38.sp)
                Crossfade(targetState = offlineNow, animationSpec = tween(500, easing = Holo.Ease), label = "ignition-status") { offline ->
                    val text = if (!IgnitionTimeline.finished(t)) "" else if (offline) "ECU OFFLINE" else "ECU LINK ESTABLISHED  •  SYSTEMS ONLINE"
                    Text(text, color = if (offline) Holo.Crimson else Holo.Cyan, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, textAlign = TextAlign.Center)
                }
                Text("TAP ANYWHERE TO SKIP", color = Holo.Muted.copy(alpha = 0.7f), fontSize = 13.sp, letterSpacing = 2.sp)
            }
            IgnitionArc(right, IgnitionTimeline.sweep(t, right.fraction ?: 0f), calibrating, Holo.Violet)
        }
    }
}

@Composable
private fun IgnitionArc(gauge: IgnitionGauge, shown: Float, calibrating: Boolean, accent: Color) {
    Box(Modifier.size(210.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 11.dp.toPx()
            val pad = stroke
            val arc = Size(size.width - pad * 2, size.height - pad * 2)
            val tl = Offset(pad, pad)
            drawArc(Color.White.copy(alpha = 0.08f), 135f, 270f, false, tl, arc, style = Stroke(stroke, cap = StrokeCap.Round))
            if (shown > 0.001f) {
                drawArc(accent.copy(alpha = 0.22f), 135f, 270f * shown, false, tl, arc, style = Stroke(stroke * 2f, cap = StrokeCap.Round))
                drawArc(Brush.sweepGradient(listOf(Holo.Cyan, Holo.Violet, Holo.Cyan)), 135f, 270f * shown, false, tl, arc, style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // The calibration move is an animation: the number is shown only once it is live data.
            Text(if (calibrating) "—" else gauge.readout, color = Holo.White, fontSize = 34.sp, fontWeight = FontWeight.Black)
            Text(if (calibrating) "CALIBRATING" else gauge.caption, color = if (gauge.fraction == null && !calibrating) Holo.Muted else accent, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        }
    }
}
