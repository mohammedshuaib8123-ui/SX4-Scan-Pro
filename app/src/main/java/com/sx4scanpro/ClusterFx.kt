package com.sx4scanpro

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

/*
 * Instrument-cluster home page, modelled on a modern digital cluster: one large tachometer in
 * the middle with a glowing ring, a boost dial on the left, coolant and intake dials on the right
 * and the road speed top right. Every number is a real ECU reading or "—". Dial scales are only
 * drawing ranges: this file draws no redline and no warning zone because none has been verified
 * for the SX4 ZDi.
 */

private object CL {
    val Glow = Color(0xFFE0245E)
    val RingHot = Color(0xFFFF7A8A)
    val Face = Color(0xFF07090F)
    val Muted = Color(0xFF9AA9BA)
    val TickDim = Color(0xFF5A6678)
    val Needle = Color(0xFFFFB84D)
    val Cyan = Color(0xFF42DDF5)
    val Amber = Color(0xFFFFB84D)
    const val STALE_MS = 5_000L
    const val START = 135f
    const val SWEEP = 270f
}

private val TNUM = TextStyle(fontFeatureSettings = "tnum")

/** Draws one cluster dial. [ignite] 0..1 lights the ring and numbers (used by the welcome). */
internal fun DrawScope.drawClusterDial(
    fraction: Float,
    scale: DialScale,
    glow: Float,
    ignite: Float,
    hasSignal: Boolean,
    hero: Boolean,
    measurer: TextMeasurer
) {
    val side = size.minDimension
    val c = Offset(size.width / 2f, size.height / 2f)
    val r = side / 2f * 0.94f
    fun pt(deg: Float, rad: Float): Offset {
        val a = Math.toRadians(deg.toDouble())
        return Offset(c.x + (cos(a) * rad).toFloat(), c.y + (sin(a) * rad).toFloat())
    }
    fun ang(v: Float): Float = CL.START + CL.SWEEP * (v / scale.max)
    val g = glow * ignite

    drawCircle(
        brush = Brush.radialGradient(
            listOf(CL.Glow.copy(alpha = (if (hero) 0.50f else 0.28f) * g), CL.Glow.copy(alpha = 0.10f * g), Color.Transparent),
            center = c, radius = r
        ),
        radius = r, center = c
    )
    drawCircle(CL.Face, r * 0.9f, c)
    drawCircle(
        brush = Brush.radialGradient(
            listOf(CL.Glow.copy(alpha = (if (hero) 0.42f else 0.20f) * g), Color.Transparent),
            center = c, radius = r * 0.62f
        ),
        radius = r * 0.62f, center = c
    )
    drawCircle(CL.Glow.copy(alpha = 0.14f * g), r * 0.94f, c, style = Stroke(side * 0.07f))
    drawCircle(CL.Glow.copy(alpha = 0.30f * g), r * 0.94f, c, style = Stroke(side * 0.035f))
    drawCircle(CL.RingHot.copy(alpha = 0.35f + 0.65f * ignite), r * 0.94f, c, style = Stroke(side * 0.010f))

    val labelPx = maxOf(side * 0.06f, 12.sp.toPx())
    val majors = scale.majorTicks()
    majors.forEachIndexed { i, tick ->
        drawLine(Color.White.copy(alpha = 0.9f * ignite), pt(ang(tick), r * 0.84f), pt(ang(tick), r * 0.74f), strokeWidth = side * 0.008f)
        if (i < majors.size - 1) {
            for (m in 1 until scale.minorPerMajor) {
                val v = tick + scale.majorStep * m / scale.minorPerMajor
                if (v <= scale.max) {
                    drawLine(CL.TickDim.copy(alpha = ignite), pt(ang(v), r * 0.84f), pt(ang(v), r * 0.79f), strokeWidth = side * 0.004f)
                }
            }
        }
        val layout = measurer.measure(
            text = scale.tickLabel(tick),
            style = TextStyle(color = Color.White.copy(alpha = 0.92f * ignite), fontSize = labelPx.toSp(), fontWeight = FontWeight.SemiBold)
        )
        val p = pt(ang(tick), r * 0.62f)
        drawText(layout, topLeft = Offset(p.x - layout.size.width / 2f, p.y - layout.size.height / 2f))
    }

    if (hasSignal && fraction > 0f && ignite > 0f) {
        val sweep = CL.SWEEP * fraction
        val arcR = r * 0.88f
        val topLeft = Offset(c.x - arcR, c.y - arcR)
        val arcSize = Size(arcR * 2f, arcR * 2f)
        drawArc(CL.Needle.copy(alpha = 0.25f), CL.START, sweep, false, topLeft, arcSize, style = Stroke(side * 0.03f))
        drawArc(CL.Needle, CL.START, sweep, false, topLeft, arcSize, style = Stroke(side * 0.012f))
    }

    if (ignite > 0f) {
        val na = CL.START + CL.SWEEP * fraction
        drawLine(CL.Needle.copy(alpha = 0.30f), pt(na + 180f, r * 0.10f), pt(na, r * 0.80f), strokeWidth = side * 0.03f, cap = StrokeCap.Round)
        drawLine(CL.Needle, pt(na + 180f, r * 0.10f), pt(na, r * 0.80f), strokeWidth = side * 0.012f, cap = StrokeCap.Round)
        drawCircle(CL.Face, side * 0.035f, c)
        drawCircle(CL.Needle, side * 0.035f, c, style = Stroke(side * 0.008f))
    }
}

private fun readingOf(gauge: GaugeValue): Double? = if (gauge.available) gauge.value.toDoubleOrNull() else null

/** The large central tachometer. */
@Composable
internal fun HeroTach(gauge: GaugeValue, scale: DialScale, nowMs: Long, modifier: Modifier = Modifier) {
    val number = readingOf(gauge)
    val stale = number != null && nowMs - gauge.capturedAtMs > CL.STALE_MS
    val fraction by animateFloatAsState(scale.fraction(number), spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMediumLow), label = "hero-needle")
    val pulse by rememberInfiniteTransition(label = "hero-glow").animateFloat(
        0.55f, 1f, infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "hero-pulse"
    )
    val measurer = rememberTextMeasurer()
    val status = if (number == null) "NO SIGNAL" else if (stale) "STALE" else "RPM"
    val description = if (number == null) "Engine speed: no signal" else "Engine speed: ${gauge.value} rpm" + if (stale) ", stale" else ""
    BoxWithConstraints(modifier.semantics(mergeDescendants = true) { contentDescription = description }, contentAlignment = Alignment.Center) {
        val side = minOf(maxWidth, maxHeight)
        Canvas(Modifier.size(side)) { drawClusterDial(fraction, scale, pulse, 1f, number != null, true, measurer) }
        Text(
            "SX4", color = Color.White.copy(alpha = 0.85f), fontSize = (side.value * 0.06f).coerceAtLeast(14f).sp,
            fontWeight = FontWeight.Black, letterSpacing = 4.sp, modifier = Modifier.align(Alignment.Center).padding(bottom = side * 0.30f)
        )
        Column(Modifier.align(Alignment.BottomCenter).padding(bottom = side * 0.17f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (number == null) "—" else gauge.value,
                color = if (number != null && !stale) Color.White else CL.Muted,
                style = TNUM, fontSize = (side.value * 0.12f).coerceAtLeast(20f).sp, fontWeight = FontWeight.Black
            )
            Text(status, color = if (stale) CL.Amber else CL.Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        }
    }
}

/** A small side dial (boost, coolant, intake...). [gauge] is already in the display unit. */
@Composable
internal fun MiniDial(title: String, gauge: GaugeValue, scale: DialScale, nowMs: Long, modifier: Modifier = Modifier) {
    val number = readingOf(gauge)
    val stale = number != null && nowMs - gauge.capturedAtMs > CL.STALE_MS
    val fraction by animateFloatAsState(scale.fraction(number), spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow), label = "mini-needle")
    val pulse by rememberInfiniteTransition(label = "mini-glow").animateFloat(
        0.6f, 1f, infiniteRepeatable(tween(3000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "mini-pulse"
    )
    val measurer = rememberTextMeasurer()
    val description = if (number == null) "$title: no signal" else "$title: ${gauge.value} ${gauge.unit}" + if (stale) ", stale" else ""
    Column(modifier.semantics(mergeDescendants = true) { contentDescription = description }, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Black, letterSpacing = 1.2.sp)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val side = minOf(maxWidth, maxHeight)
            Canvas(Modifier.size(side)) { drawClusterDial(fraction, scale, pulse, 1f, number != null, false, measurer) }
            Column(Modifier.align(Alignment.BottomCenter).padding(bottom = side * 0.13f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (number == null) "—" else gauge.value,
                    color = if (number != null && !stale) Color.White else CL.Muted,
                    style = TNUM, fontSize = (side.value * 0.14f).coerceAtLeast(16f).sp, fontWeight = FontWeight.Black
                )
                Text(
                    if (number == null) "NO SIGNAL" else if (stale) "STALE" else gauge.unit,
                    color = if (stale) CL.Amber else CL.Muted, fontSize = 13.sp
                )
            }
        }
    }
}

/** Shift-light style bar above the tachometer: lights in proportion to the real RPM reading. */
@Composable
internal fun ShiftBar(fraction: Float, active: Boolean, modifier: Modifier = Modifier) {
    val total = 12
    val lit = ClusterMath.litSegments(fraction, total)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(total) { i ->
            val on = active && i < lit
            val color by animateColorAsState(if (on) CL.Cyan else Color(0xFF1B2733), tween(120), label = "shift-seg")
            Box(Modifier.weight(1f).fillMaxHeight().background(color, RoundedCornerShape(2.dp)))
        }
    }
}

/**
 * The home page. [speed], [boost] arrive already converted to the display unit; the scales
 * match those units. Nothing here invents a value.
 */
@Composable
internal fun ClusterHome(
    rpm: GaugeValue,
    speed: GaugeValue,
    speedUnitLabel: String,
    boost: GaugeValue,
    boostScale: DialScale,
    coolant: GaugeValue,
    intake: GaugeValue,
    load: GaugeValue,
    maf: GaugeValue,
    nowMs: Long,
    modifier: Modifier = Modifier
) {
    val rpmScale = DialScale.forLabel("Engine RPM")
    val rpmNumber = readingOf(rpm)
    val speedNumber = readingOf(speed)
    val loadFraction = DialScale(100f, 20f).fraction(readingOf(load))
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ShiftBar(rpmScale.fraction(rpmNumber), rpmNumber != null, Modifier.fillMaxWidth(0.5f).height(10.dp).align(Alignment.CenterHorizontally))
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            MiniDial("BOOST", boost, boostScale, nowMs, Modifier.weight(0.85f).fillMaxHeight())
            HeroTach(rpm, rpmScale, nowMs, Modifier.weight(1.5f).fillMaxHeight())
            Column(Modifier.weight(1.15f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.End) {
                    Text(
                        if (speedNumber == null) "—" else speed.value, color = if (speedNumber == null) CL.Muted else Color.White,
                        style = TNUM, fontSize = 56.sp, fontWeight = FontWeight.Black
                    )
                    Text(speedUnitLabel.uppercase(), color = CL.Muted, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp, bottom = 10.dp))
                }
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MiniDial("COOLANT", coolant, DialScale.forLabel("Coolant temperature"), nowMs, Modifier.weight(1f).fillMaxHeight())
                    MiniDial("INTAKE", intake, DialScale.forLabel("Intake air temperature"), nowMs, Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("LOAD", color = CL.Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Box(Modifier.weight(1f).height(6.dp).background(Color(0xFF1B2733), RoundedCornerShape(3.dp))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(loadFraction).background(CL.Cyan, RoundedCornerShape(3.dp)))
            }
            Text(if (readingOf(load) == null) "—" else load.value + " %", color = Color.White, fontSize = 14.sp, style = TNUM)
            Text("MAF", color = CL.Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Text(if (readingOf(maf) == null) "—" else maf.value + " g/s", color = Color.White, fontSize = 14.sp, style = TNUM)
        }
    }
}
